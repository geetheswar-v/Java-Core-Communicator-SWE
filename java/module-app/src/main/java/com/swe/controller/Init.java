package com.swe.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.swe.ScreenNVideo.MediaCaptureManager;
import com.swe.canvas.CanvasManager;
import com.swe.chat.ChatManager;
import com.swe.core.Auth.AuthService;
import com.swe.core.ClientNode;
import com.swe.core.Meeting.MeetingSession;
import com.swe.core.Meeting.SessionMode;
import com.swe.core.Meeting.UserProfile;
import com.swe.core.RPC;
import com.swe.core.serialize.DataSerializer;
import com.swe.networking.Networking;
import functionlibrary.CloudFunctionLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutionException;

public class Init {
    public static void main(String[] args) throws Exception {
        int portNumber = 6942;

        if (args.length > 0) {
            String port = args[0];
            portNumber = Integer.parseInt(port);
        }

        RPC rpc = new RPC();
        CloudFunctionLibrary cloud = new CloudFunctionLibrary();

        ControllerServices controllerServices = ControllerServices.getInstance();
        controllerServices.context.rpc = rpc;
        controllerServices.cloud = cloud;

        // Provide RPC somehow here
        NetworkingInterface networking = new NetworkingAdapter(Networking.getNetwork());
        networking.consumeRPC(rpc);

        controllerServices.networking = networking;
        MeetingNetworkingCoordinator.initialize(networking);

        new ChatManager(Networking.getNetwork());
        controllerServices.canvasManager = new CanvasManager(Networking.getNetwork(), Utils.getLocalClientNode());

        MediaCaptureManager mediaCaptureManager = new MediaCaptureManager(Networking.getNetwork(), 6943);
        Thread mediaCaptureManagerThread = new Thread(() -> {
            try {
                mediaCaptureManager.startCapture();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        mediaCaptureManagerThread.start();

        addRPCSubscriptions(rpc);

        // We need to get all subscriptions from frontend to also finish before this
        Thread rpcThread = rpc.connect(portNumber);

        rpcThread.join();
        mediaCaptureManagerThread.join();
    }

    private static void addRPCSubscriptions(RPC rpc) {
        ControllerServices controllerServices = ControllerServices.getInstance();

        rpc.subscribe("core/register", (byte[] userData) -> {
            System.out.println("Registering user");
            UserProfile RegisteredUser = null;
            try {
                RegisteredUser = AuthService.register();
                System.out.println("Registered user with emailId: " + RegisteredUser.getEmail());
            } catch (GeneralSecurityException | IOException e) {
                // throw new RuntimeException(e);
                System.out.println("Error registering user: " + e.getMessage());
                return new byte[0];
            }

            controllerServices.context.self = RegisteredUser;

            try {
                return DataSerializer.serialize(RegisteredUser);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        rpc.subscribe("core/createMeeting", (byte[] meetMode) -> {
            System.out.println("[CONTROLLER] Creating meeting");
            final MeetingSession meetingSession = MeetingServices.createMeeting(controllerServices.context.self,
                    SessionMode.CLASS);
            controllerServices.context.meetingSession = meetingSession;

            try {
                final ClientNode localClientNode = Utils.getLocalClientNode();
                Utils.setServerClientNode(meetingSession.getMeetingId(), controllerServices.cloud);
                controllerServices.networking.addUser(localClientNode, localClientNode);
                
                // Update CanvasManager with local node as host
                controllerServices.canvasManager.setHostClientNode(localClientNode);

                MeetingNetworkingCoordinator.handleMeetingCreated(meetingSession);
            } catch (Exception e) {
                System.out.println("Error initializing networking for meeting host: " + e.getMessage());
                throw new RuntimeException(e);
            }

            try {
                System.out.println("Returning meeting session");
                byte[] serializedMeetingSession = DataSerializer.serialize(meetingSession);
                System.out.println(
                        "Serialized meeting session: " + new String(serializedMeetingSession, StandardCharsets.UTF_8));
                return serializedMeetingSession;
            } catch (Exception e) {
                System.out.println("Error serializing meeting session: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });

        rpc.subscribe("core/joinMeeting", (byte[] meetId) -> {
            String id;
            try {
                id = DataSerializer.deserialize(meetId, String.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            System.out.println("Joining meeting with id: " + id);

            try {
                final ClientNode localClientNode = Utils.getLocalClientNode();
                final ClientNode serverClientNode = Utils.getServerClientNode(id, controllerServices.cloud);
                System.out.println("Server client node: " + serverClientNode);

                controllerServices.networking.addUser(localClientNode, serverClientNode);
                
                // Update CanvasManager with server node as host
                controllerServices.canvasManager.setHostClientNode(serverClientNode);
                
                MeetingNetworkingCoordinator.handleMeetingJoin(id, serverClientNode);
            } catch (Exception e) {
                System.out.println("Error getting server client node: " + e.getMessage());
                throw new RuntimeException(e);
            }

            return meetId;
        });

        rpc.subscribe("core/logout", (byte[] userData) -> {
            System.out.println("Logging out user");
            try {
                AuthService.logout();
                System.out.println("User logged out successfully");
                // Clear the current user profile from context
                controllerServices.context.self = null;
                return "Logged out successfully".getBytes(StandardCharsets.UTF_8);
            } catch (GeneralSecurityException | IOException e) {
                System.out.println("Error logging out user: " + e.getMessage());
                return ("Error logging out: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
        });

        rpc.subscribe("core/endMeeting", (byte[] data) -> {
            System.out.println("Ending meeting");
            try {
                Networking.getNetwork().closeNetworking();
                System.out.println("Meeting ended successfully");
                // Clear the meeting session from context
                controllerServices.context.meetingSession = null;
                return "Meeting ended successfully".getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.out.println("Error ending meeting: " + e.getMessage());
                return ("Error ending meeting: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
        });
    }
}
