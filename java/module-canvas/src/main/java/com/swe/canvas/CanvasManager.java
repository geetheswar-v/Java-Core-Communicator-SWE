package com.swe.canvas;

import com.swe.core.Context;
import com.swe.core.ClientNode;
import com.swe.core.RPCinterface.AbstractRPC;
import com.swe.networking.ModuleType;
import com.swe.networking.AbstractNetworking;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import com.swe.networking.Networking;
import com.swe.aiinsights.aiinstance.AiInstance;
import com.swe.aiinsights.apiendpoints.AiClientService;
import java.util.concurrent.CompletableFuture;


/**
 * ============================================================================
 * BACKEND - CanvasManager
 * ============================================================================
 *
 * Responsibilities:
 *   1. Receive canvas actions from frontend (create, update, delete)
 *   2. Validate shapes (owner, format, coordinates)
 *   3. Store VALIDATED shapes in memory
 *   4. Broadcast validated changes to remote peers
 *   5. Receive remote changes and forward to frontend
 *
 * Backend NEVER draws anything.
 */
public class CanvasManager {

    private final AbstractRPC rpc;
    private final Networking network;
    private ClientNode hostClientNode;

    private AiClientService aiInstance;

    public CanvasManager(Networking network, ClientNode hostClientNode) {
        // Constructor implementation (if needed)
        Context context = Context.getInstance();
        this.rpc = context.rpc;
        this.network = network;
        this.aiInstance = AiInstance.getInstance();
        this.hostClientNode = hostClientNode;
        
        this.rpc.subscribe("canvas:regularize", this::handleRegularize);
        this.rpc.subscribe("canvas:describe", this::handleDescribe);
        this.rpc.subscribe("canvas:getHostIp", this::handleGetHostIp);

    }


    private byte[] handleRegularize(byte[] data) {
        // byte is the serialized action

        String json = new String(data, StandardCharsets.UTF_8);
        CompletableFuture<String> resp = aiInstance.regularise(json);
        
        String response = resp.join();
        return response.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] handleDescribe(byte[] data) {
        String path = new String(data, StandardCharsets.UTF_8);
        CompletableFuture<String> resp = aiInstance.describe(path);

        String response = resp.join();
        return response.getBytes(StandardCharsets.UTF_8);
    }


    String serializeHost(ClientNode host){
        return host.hostName() + ":" + host.port();
    }

    private byte[] handleGetHostIp(byte[] data) {
        System.out.println("[CanvasManager] handleGetHostIp called");
        String hostString = serializeHost(this.hostClientNode);
        System.out.println("[CanvasManager] Returning host IP: " + hostString);
        return hostString.getBytes(StandardCharsets.UTF_8);
    }

}
