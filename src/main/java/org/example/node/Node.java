package org.example.node;

import org.example.heartbeat.HeartbeatManager;
import org.example.ring.ConsistentHashRing;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.stream.Collectors;

public class Node {
    private final int port;
    private final Store store;
    private final ConsistentHashRing ring;
    private final String myAddress;
    private final HeartbeatManager heartbeatManager;

    public Node(int port, List<String> peers) {
        this.port = port;
        this.myAddress = "localhost:" + port;
        this.store = new Store();
        this.ring = new ConsistentHashRing(100);

        // Add all peers to ring
        for (String peer : peers) {
            ring.addNode(peer);
        }

        // Peers to monitor = everyone except myself
        List<String> otherNodes = peers.stream()
                .filter(p -> !p.equals(myAddress))
                .collect(Collectors.toList());

        this.heartbeatManager = new HeartbeatManager(
                myAddress, otherNodes, ring
        );
    }

    public void start() {
        // Start heartbeat BEFORE accepting client connections
        heartbeatManager.start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Node started: " + myAddress);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread thread = new Thread(
                        new ClientHandler(clientSocket, store,
                                ring, myAddress, heartbeatManager)
                );
                thread.start();
            }
        } catch (IOException e) {
            System.err.println("Node failed: " + e.getMessage());
        }
    }
}