package com.swe.ScreenNVideo;


import com.swe.core.RPCinterface.AbstractRPC;
import com.swe.ScreenNVideo.Capture.AudioCapture;
import com.swe.ScreenNVideo.Codec.BilinearScaler;
import com.swe.ScreenNVideo.Codec.ImageScaler;
import com.swe.ScreenNVideo.PatchGenerator.ImageStitcher;
import com.swe.ScreenNVideo.PatchGenerator.Patch;
import com.swe.ScreenNVideo.Serializer.NetworkPacketType;
import com.swe.ScreenNVideo.Serializer.NetworkSerializer;
import com.swe.core.ClientNode;
import com.swe.networking.ModuleType;
import com.swe.networking.SimpleNetworking.AbstractNetworking;

import java.awt.image.BufferedImage;

/**
 * Class conatining Components to capture feed.
 */
public class CaptureComponents {


    public void setLatestScreenFrame(final BufferedImage latestScreenFrameArgs) {
        this.latestScreenFrame = latestScreenFrameArgs;
    }

    public void setLatestVideoFrame(final BufferedImage latestVideoFrameArgs) {
        this.latestVideoFrame = latestVideoFrameArgs;
    }

    /**
     * The most recent screen capture frame.
     */
    private volatile BufferedImage latestScreenFrame = null;
    /**
     * The most recent video capture frame.
     */
    private volatile BufferedImage latestVideoFrame = null;

    public boolean isVideoCaptureOn() {
        return isVideoCaptureOn;
    }

    public boolean isScreenCaptureOn() {
        return isScreenCaptureOn;
    }

    public boolean isAudioCaptureOn() {
        return isAudioCaptureOn;
    }


    /**
     * Flag for video capture.
     */
    private boolean isVideoCaptureOn;

    /**
     * Flag for screen capture.
     */
    private boolean isScreenCaptureOn;

    /**
     * Flag for audio capture.
     */
    private boolean isAudioCaptureOn;

    /**
     * Image scaler object.
     */
    private final ImageScaler scalar;

    /**
     * AudioCapture object.
     */
    private final AudioCapture audioCapture;


    /**
     * Image stitcher object.
     */
    private final ImageStitcher imageStitcher;

    /**
     * Networking object.
     */
    private final AbstractNetworking networking;

    /**
     * Local IP address.
     */
    private final String localIp = Utils.getSelfIP();

    /**
     * Constructor for CaptureComponents.
     * @param argNetworking Networking object
     * @param rpc RPC object
     * @param port Port number
     */

    CaptureComponents(final AbstractNetworking argNetworking, final AbstractRPC rpc, final int port) {
        isScreenCaptureOn = false;
        isVideoCaptureOn = false;
        isAudioCaptureOn = false;
        this.networking = argNetworking;
        scalar = new BilinearScaler();
        imageStitcher = new ImageStitcher();
        audioCapture = new AudioCapture();
        initializeHandlers(rpc, port);
    }

    public void startAudioLoop() {
        audioCapture.init();
    }

    private int[][] getFeedMatrix(final BufferedImage videoFeed, final BufferedImage screenFeed) {
        int[][] feed = null;
//        long prev = System.nanoTime();
        if (screenFeed != null) {
            feed = Utils.convertToRGBMatrix(screenFeed);
        }

        if (videoFeed != null) {
            final int[][] videoMatrix = Utils.convertToRGBMatrix(videoFeed);
            if (feed == null) {
                feed = videoMatrix;
            } else {
                final int height = feed.length;
                final int width = feed[0].length;
                final int targetHeight = height / Utils.SCALE_Y;
                final int targetWidth = width / Utils.SCALE_X;
//                    long curr = System.nanoTime();
                final int[][] scaledDownedFeed = scalar.scale(videoMatrix, targetHeight, targetWidth);
//                    System.out.println("Scalling Video :" + (curr - System.nanoTime()) / ((double) Utils.MSEC_IN_NS));
                final int videoPosY = height - Utils.VIDEO_PADDING_Y - targetHeight;
                final int videoPosX = width - Utils.VIDEO_PADDING_X - targetWidth;
                final Patch videoPatch = new Patch(scaledDownedFeed, videoPosX, videoPosY);
                imageStitcher.setCanvas(feed);
                imageStitcher.stitch(videoPatch);
                feed = imageStitcher.getCanvas();
            }
        }
//            long curr = System.nanoTime();
//            System.out.println("Stitching Time : " + (curr - prev) / ((double) Utils.MSEC_IN_NS));
//            prev = curr;
        if (feed != null && (feed.length > Utils.SERVER_HEIGHT || feed[0].length > Utils.SERVER_WIDTH )) {
            feed = scalar.scale(feed, Utils.SERVER_HEIGHT, Utils.SERVER_WIDTH);
        }
//            curr = System.nanoTime();
//            System.out.println("Scaling Time : " + (curr - prev) / ((double) Utils.MSEC_IN_NS));
        return feed;
    }


    /**
     * Capture the feed from screen and video.
     * @return 2D RGB matrix of the feed
     */
    public int[][] getFeed() {
        final BufferedImage videoFeed = latestVideoFrame;
        final BufferedImage screenFeed = latestScreenFrame;
        final int[][] feed;
//            long prev = System.nanoTime();

        if (!isScreenCaptureOn && !isVideoCaptureOn) {
            return null;
        }

//            long curr = System.nanoTime();
//            System.out.println("Capture Time : " + (curr - prev) / ((double) Utils.MSEC_IN_NS));
//            prev = curr;

        // get the feed to send
        feed = getFeedMatrix(videoFeed, screenFeed);
//            curr = System.nanoTime();
//            System.out.println("Processing Time : " + (curr - prev) / ((double) Utils.MSEC_IN_NS));
        return feed;
    }

    public byte[] getAudioFeed() {
        if (isAudioCaptureOn) {
            return audioCapture.getChunk();
        }
        return null;
    }

    private void initializeHandlers(final AbstractRPC rpc, final int port) {
        rpc.subscribe(Utils.START_VIDEO_CAPTURE, (final byte[] args) -> {
            isVideoCaptureOn = true;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.STOP_VIDEO_CAPTURE, (final byte[] args) -> {
            isVideoCaptureOn = false;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.START_SCREEN_CAPTURE, (final byte[] args) -> {
            isScreenCaptureOn = true;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.STOP_SCREEN_CAPTURE, (final byte[] args) -> {
            isScreenCaptureOn = false;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.START_AUDIO_CAPTURE, (final byte[] args) -> {
            isAudioCaptureOn = true;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.STOP_AUDIO_CAPTURE, (final byte[] args) -> {
            isAudioCaptureOn = false;
            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

        rpc.subscribe(Utils.SUBSCRIBE_AS_VIEWER, (final byte[] args) -> {
            // Get the destination user IP
            final String destIP = NetworkSerializer.deserializeIP(args);

            final ClientNode destNode = new ClientNode(destIP, port);

            // Get IP address as string
            final byte[] subscribeData = NetworkSerializer.serializeIP(NetworkPacketType.SUBSCRIBE_AS_VIEWER, localIp);
            networking.sendData(subscribeData, new ClientNode[] {destNode}, ModuleType.SCREENSHARING, 2);

            final byte[] res = new byte[1];
            res[0] = 1;
            return res;
        });

    }
}
