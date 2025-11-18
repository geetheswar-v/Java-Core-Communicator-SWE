package com.swe.ScreenNVideo;


import com.swe.core.RPCinterface.AbstractRPC;
import com.swe.ScreenNVideo.Capture.BackgroundCaptureManager;
import com.swe.ScreenNVideo.Codec.ADPCMDecoder;
import com.swe.ScreenNVideo.PatchGenerator.CompressedPatch;
import com.swe.ScreenNVideo.Playback.AudioPlayer;
import com.swe.ScreenNVideo.Serializer.APackets;
import com.swe.ScreenNVideo.Serializer.CPackets;
import com.swe.ScreenNVideo.Serializer.NetworkPacketType;
import com.swe.ScreenNVideo.Serializer.NetworkSerializer;
import com.swe.ScreenNVideo.Serializer.RImage;
import com.swe.ScreenNVideo.Synchronizer.FeedData;
import com.swe.ScreenNVideo.Synchronizer.ImageSynchronizer;
import com.swe.networking.SimpleNetworking.AbstractNetworking;
import com.swe.core.ClientNode;
import com.swe.networking.SimpleNetworking.MessageListener;
import com.swe.networking.ModuleType;

import javax.sound.sampled.LineUnavailableException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Media Manager for Screen N Video.
 * - Manages Screen Capture and Video Capture
 */
public class MediaCaptureManager implements CaptureManager {
    /**
     * Port for the server.
     */
    private final int port;

    /**
     * VideoComponent object : manages the video overlay and diffing.
     */
    private final VideoComponents videoComponent;
    /**
     * Image synchronizer object.
     */
    private final HashMap<String, ImageSynchronizer> imageSynchronizers;

    /**
     * Networking object.
     */
    private final AbstractNetworking networking;
    /**
     * RPC object.
     */
    private final AbstractRPC rpc;

    /**
     * Cached IP for this machine to avoid repeated socket calls.
     */
    private final String localIp;

    /**
     * List of viewers to send the video Feed.
     */
    private final HashSet<ClientNode> viewers;

    /**
     * Client handler for incoming messages.
     */
    private final ClientHandler clientHandler;

    /**
     * Audio Player object.
     */
    private final AudioPlayer audioPlayer;

    /**
     * Audio Decoder object.
     */
    private final ADPCMDecoder audioDecoder;

    /**
     * Constructor for the MediaCaptureManager.
     *
     * @param argNetworking Networking object
     * @param argRpc        RPC object
     * @param portArgs      Port for the server
     */
    public MediaCaptureManager(final AbstractNetworking argNetworking, final AbstractRPC argRpc, final int portArgs) {
        this.rpc = argRpc;
        this.port = portArgs;
        this.networking = argNetworking;
        final CaptureComponents captureComponents = new CaptureComponents(networking, rpc, port);
        videoComponent = new VideoComponents(Utils.FPS, rpc, captureComponents);
        audioPlayer = new AudioPlayer(Utils.DEFAULT_SAMPLE_RATE, Utils.DEFAULT_CHANNELS, Utils.DEFAULT_SAMPLE_SIZE);
        audioDecoder = new ADPCMDecoder();
        final BackgroundCaptureManager backgroundCaptureManager = new BackgroundCaptureManager(captureComponents);

        captureComponents.startAudioLoop();
        backgroundCaptureManager.start();
        try {
            audioPlayer.init();
        } catch (LineUnavailableException e) {
            System.err.println("Unable to connect to Line");
        }


        imageSynchronizers = new HashMap<>();
        viewers = new HashSet<>();

        // Cache local IP once to avoid repeated socket operations during capture
        this.localIp = Utils.getSelfIP();
         System.out.println(this.localIp);

//        addParticipant(localIp);

        clientHandler = new MediaCaptureManager.ClientHandler();

        networking.subscribe(ModuleType.SCREENSHARING, clientHandler);
    }

    /**
     * Broadcast join meeting to available IPs.
     * Only till broadcast is supported, multicast not supported yet.
     *
     * @param availableIPs list of available IPs
     */
    public void broadcastJoinMeeting(final List<String> availableIPs) {
        final ClientNode[] clientNodes =
            availableIPs.stream().map(ip -> new ClientNode(ip, port)).toArray(ClientNode[]::new);

        // System.out.println("Broadcasting join meeting to : " + Arrays.toString(clientNodes));
        final byte[] subscribeData = NetworkSerializer.serializeIP(NetworkPacketType.SUBSCRIBE_AS_VIEWER, localIp);
        networking.sendData(subscribeData, clientNodes, ModuleType.SCREENSHARING, 2);
    }

    @Override
    public void newParticipantJoined(final String ip) {
        clientHandler.addUserNFullImageRequest(ip);
    }

    private void addParticipant(final String ip) {
        if (ip == null) {
            return;
        }
//        if (localIp != null && ip == localIp) {
//            return;
//        }
        final ClientNode node = new ClientNode(ip, port);
        viewers.add(node);
        imageSynchronizers.put(ip, new ImageSynchronizer(videoComponent.getVideoCodec()));
        rpc.call(Utils.SUBSCRIBE_AS_VIEWER, ip.getBytes());
    }

    /**
     * Server-side of the ScreenNVideo.
     */
    @Override
    public void startCapture() throws ExecutionException, InterruptedException {

        // System.out.println("Starting capture");
        int[][] feed = null;
        while (true) {
            final byte[] encodedPatches = videoComponent.captureScreenNVideo();
            final int[][] newFeed = videoComponent.getFeed();
            if (encodedPatches == null) {
                if (feed != null && newFeed == null) {
                    final byte[] subscribeData = NetworkSerializer.serializeIP(NetworkPacketType.STOP_SHARE, localIp);
                    sendDataToViewers(subscribeData);
                    feed = newFeed;
                }
            } else {
                feed = newFeed;
                sendDataToViewers(encodedPatches);
            }
            // get audio Feed
            final byte[] encodedAudio = videoComponent.captureAudio();
            if (encodedAudio == null) {
                continue;
            }
//            System.err.println("Sending audio");
            sendDataToViewers(encodedAudio);
        }
    }

    private void sendDataToViewers(final byte[] feed) {

        // System.out.println("Size : " + feed.length / Utils.KB + " KB");
        CompletableFuture.runAsync(() -> {
            viewers.forEach(v -> // System.out.println("Viewer IP : " + v.hostName()));
            networking.sendData(feed, viewers.toArray(new ClientNode[0]), ModuleType.SCREENSHARING, 2));

            // System.out.println("Sent to viewers " + viewers.size());
//        try {
//            Thread.sleep(30000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
        });
    }


    class ClientHandler implements MessageListener {
        /**
         * Cache for NetworkType enum.
         */
        private final NetworkPacketType[] enumVals;

        ClientHandler() {
            enumVals = NetworkPacketType.values();
        }

        @Override
        public void receiveData(final byte[] data) {

//            // System.out.println("Recieved");
            if (data.length == 0) {
                return;
            }
//            // System.out.println("first 40 bytes:" + (Arrays.toString(Arrays.copyOf(data, 40))));
            final byte packetType = data[0];
            if (packetType > enumVals.length) {
                final int printLen = 34;
                System.err.println("Error: Invalid packet type: " + packetType + "  " + data.length);
                System.err.println("Error: Packet data: " + (Arrays.toString(Arrays.copyOf(data, printLen))));
                return;
            }
            final NetworkPacketType type = enumVals[packetType];
            switch (type) {
                case NetworkPacketType.LIST_CPACKETS -> {
//                    // System.out.println(Arrays.toString(Arrays.copyOf(data, 10)));
                    final CPackets networkPackets = CPackets.deserialize(data);
                     System.out.println("Received CPackets : " + data.length / Utils.KB + " KB " + networkPackets.packetNumber());
                     System.out.println("Height: " + networkPackets.height() + " Width: " + networkPackets.width());

                    ImageSynchronizer imageSynchronizer = imageSynchronizers.get(networkPackets.ip());
                    if (imageSynchronizer == null) {
                        // add new participant if not already present
                        addParticipant(networkPackets.ip());
                        imageSynchronizer = imageSynchronizers.get(networkPackets.ip());
                    }


//                    // System.out.println("Recieved " + networkPackets.packetNumber() + "; Expected : " +
//                        imageSynchronizer.getExpectedFeedNumber());

                    if (networkPackets.isFullImage()) {
                        // System.out.println("Full Image");
                        // reset expected feed number
                        imageSynchronizer.setExpectedFeedNumber(networkPackets.packetNumber());

                        // drop all entries older than this full image
                        while (!imageSynchronizer.getHeap().isEmpty() &&
                            imageSynchronizer.getHeap().peek().getFeedNumber() <
                                imageSynchronizer.getExpectedFeedNumber()) {
                            imageSynchronizer.getHeap().poll();
                        }

                    } else {

                        // if heap is growing too large, request a full frame to resync
                        if (imageSynchronizer.getHeap().size() >= Utils.MAX_HEAP_SIZE) {
                            askForFullImage(networkPackets.ip());
                            imageSynchronizer.getHeap().clear();
                            return;
                        }
                    }

                    imageSynchronizer.getHeap().add(new FeedData(networkPackets.packetNumber(), networkPackets));

                    int[][] image = null;
                    while (true) {

                        // If the next expected patch hasn't arrived yet, wait
                        final FeedData feedData = imageSynchronizer.getHeap().peek();
                        if (feedData == null || feedData.getFeedNumber() != imageSynchronizer.getExpectedFeedNumber()) {
                            break;
                        }

                        final FeedData minFeedNumPacket = imageSynchronizer.getHeap().poll();

                        if (minFeedNumPacket == null) {
                            break;
                        }

                        final CPackets minFeedCPacket = minFeedNumPacket.getFeedPackets();
//                        // System.out.println("Min Feed Packet " + minFeedCPacket.packetNumber());
                        final List<CompressedPatch> patches = minFeedCPacket.packets();
                        final int newHeight = minFeedCPacket.height();
                        final int newWidth = minFeedCPacket.width();

                        imageSynchronizer.setExpectedFeedNumber(imageSynchronizer.getExpectedFeedNumber() + 1);

                        try {
                            image = imageSynchronizer.synchronize(newHeight, newWidth, patches,
                                networkPackets.compress());
                        } catch (Exception e) {
                            e.printStackTrace();
                            askForFullImage(networkPackets.ip());
                            imageSynchronizer.getHeap().clear();
                            return;
                        }
                    }

                    if (image == null) {
                        return;
                    }


                    final RImage rImage = new RImage(image, networkPackets.ip());
                    final byte[] serializedImage = rImage.serialize();
                     System.out.println("Sending to UI" + ("; Expected : "
                    + imageSynchronizer.getExpectedFeedNumber()));
                    try {
                        final byte[] res = rpc.call(Utils.UPDATE_UI, serializedImage).get();
                        if (res.length == 0) {
                            return;
                        }
                        final boolean success = res[0] == 1;
                        if (!success) {
                            addParticipant(networkPackets.ip());
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace(System.out);
                    }

                }
                case NetworkPacketType.SUBSCRIBE_AS_VIEWER -> {
                    final String viewerIP = NetworkSerializer.deserializeIP(data);
                    // System.out.println("Viewer joined" + viewerIP);
                    addUserNFullImageRequest(viewerIP);
                    // System.out.println("Handled packet type: " + type);
                }
                case STOP_SHARE -> {
                    final String viewerIP = NetworkSerializer.deserializeIP(data);
                    rpc.call(Utils.STOP_SHARE, viewerIP.getBytes());
                }
                case APACKETS -> {
                    final APackets audioPackets = APackets.deserialize(data);
//                    // System.out.println("Audio" + audioPackets.packetNumber());
                    final byte[] audioBytes = audioDecoder.decode(audioPackets.data());
                    audioPlayer.play(audioBytes);
                }
                default -> {
                }
            }
        }

        private void askForFullImage(final String ip) {
            System.err.println("Asking for data...");
            final byte[] subscribeData = NetworkSerializer.serializeIP(NetworkPacketType.SUBSCRIBE_AS_VIEWER, localIp);
            final ClientNode destNode = new ClientNode(ip, port);
            networking.sendData(subscribeData, new ClientNode[] {destNode}, ModuleType.SCREENSHARING, 2);
        }

        public void addUserNFullImageRequest(final String ip) {
            addParticipant(ip);
            final byte[] fullImageEncoded = videoComponent.captureFullImage();
            if (fullImageEncoded == null) {
                return;
            }
            networking.sendData(fullImageEncoded, new ClientNode[] {new ClientNode(ip, port)},
                ModuleType.SCREENSHARING, 2);
        }
    }
}
