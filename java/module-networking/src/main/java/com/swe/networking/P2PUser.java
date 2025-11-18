package com.swe.networking;

import com.swe.core.ClientNode;

/**
 * Common interface to be used by client and server.
 */
public interface P2PUser {
    /**
     * Function to send data by the user.
     * 
     * @param data   the data to be sent
     * @param destIp the destination to send the data
     */
    void send(byte[] data, ClientNode[] destIp);

    /**
     * Function to send data by the user.
     * 
     * @param data   the data to be sent
     * @param destIp the one destination to send the data
     */
    void send(byte[] data, ClientNode destIp);

    /**
     * Function to receive data from other users.
     */
    void receive();

    /**
     * Function to handle socket closing at termination.
     */
    void close();
}
