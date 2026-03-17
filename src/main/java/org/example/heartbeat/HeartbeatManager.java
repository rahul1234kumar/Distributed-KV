package org.example.heartbeat;

import org.example.ring.ConsistentHashRing;
import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HeartbeatManager runs in the background on every node.
 *
 * Every HEARTBEAT_INTERVAL seconds it pings all peer nodes.
 * If a node misses FAILURE_THRESHOLD consecutive heartbeats
 * it is marked DEAD and removed from the consistent hash ring.
 * If a dead node responds again it is marked ALIVE and
 * added back to the ring.
 *
 * This gives us automatic failure detection without any
 * human intervention.
 */
public class HeartbeatManager {

    private static final int HEARTBEAT_INTERVAL  = 2;  // seconds between pings
    private static final int FAILURE_THRESHOLD   = 3;  // missed pings before dead
    private static final int CONNECT_TIMEOUT_MS  = 1000; // 1 second socket timeout

    private final String myAddress;
    private final List<String> peers;         // all nodes except myself
    private final ConsistentHashRing ring;

    // Tracks consecutive missed heartbeats per peer
    // key = "localhost:7002", value = miss count (0 = alive)
    private final Map<String, Integer> missedHeartbeats = new ConcurrentHashMap<>();

    // Tracks current alive/dead status per peer
    private final Map<String, Boolean> nodeStatus = new ConcurrentHashMap<>();

    // ScheduledExecutorService is like a background cron job in Java.
    // We give it a task and it runs it every N seconds automatically.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    public HeartbeatManager(String myAddress, List<String> peers,
                            ConsistentHashRing ring) {
        this.myAddress = myAddress;
        this.peers = peers;
        this.ring = ring;

        // Initialise all peers as alive with 0 missed heartbeats
        for (String peer : peers) {
            missedHeartbeats.put(peer, 0);
            nodeStatus.put(peer, true); // assume alive at start
        }
    }

    /**
     * Start the heartbeat loop.
     * Runs pingAllPeers() every HEARTBEAT_INTERVAL seconds
     * in a background thread — doesn't block the main node.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::pingAllPeers,
                HEARTBEAT_INTERVAL,  // initial delay
                HEARTBEAT_INTERVAL,  // period
                TimeUnit.SECONDS
        );
        System.out.println("[Heartbeat] Started on " + myAddress
                + " — pinging peers every " + HEARTBEAT_INTERVAL + "s");
    }

    /**
     * Ping every peer node once.
     * Called automatically every HEARTBEAT_INTERVAL seconds.
     */
    private void pingAllPeers() {
        for (String peer : peers) {
            boolean alive = ping(peer);

            if (alive) {
                handleAlive(peer);
            } else {
                handleMiss(peer);
            }
        }
    }

    /**
     * Send a PING command to a node.
     * Returns true if node responds with PONG within timeout.
     * Returns false if connection refused or timeout.
     */
    private boolean ping(String nodeAddress) {
        String[] parts = nodeAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try (Socket socket = new Socket()) {
            // connect with timeout — don't wait forever for dead nodes
            socket.connect(
                    new java.net.InetSocketAddress(host, port),
                    CONNECT_TIMEOUT_MS
            );

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            out.println("PING");
            String response = in.readLine();
            return "PONG".equals(response);

        } catch (IOException e) {
            return false; // connection refused or timeout = dead
        }
    }

    /**
     * Node responded to ping — it's alive.
     * If it was previously dead, add it back to the ring.
     */
    private void handleAlive(String peer) {
        missedHeartbeats.put(peer, 0); // reset miss counter

        boolean wasDead = !nodeStatus.getOrDefault(peer, true);
        if (wasDead) {
            // Node has come back from the dead
            nodeStatus.put(peer, true);
            ring.addNode(peer);
            System.out.println("[Heartbeat] Node RECOVERED: " + peer
                    + " — added back to ring ✓");
        }
    }

    /**
     * Node did not respond to ping.
     * Increment miss counter. If threshold reached, mark dead.
     */
    private void handleMiss(String peer) {

        boolean alreadyDead = !nodeStatus.getOrDefault(peer, true);
        if (alreadyDead) return;
        int misses = missedHeartbeats.merge(peer, 1, Integer::sum);
        // merge(key, 1, Integer::sum) = get current value, add 1, store back

        System.out.println("[Heartbeat] No response from " + peer
                + " (miss " + misses + "/" + FAILURE_THRESHOLD + ")");

        if (misses >= FAILURE_THRESHOLD) {
            boolean wasAlive = nodeStatus.getOrDefault(peer, true);
            if (wasAlive) {
                // First time crossing threshold — mark dead
                nodeStatus.put(peer, false);
                ring.removeNode(peer);
                System.out.println("[Heartbeat] Node DEAD: " + peer
                        + " — removed from ring ✗");
            }
        }
    }

    /**
     * Print current status of all peers.
     * Called by STATUS command from CLI.
     */
    public void printStatus() {
        System.out.println("\n--- Cluster Status ---");
        System.out.println(myAddress + " → ALIVE (me)");
        for (String peer : peers) {
            boolean alive = nodeStatus.getOrDefault(peer, true);
            int misses = missedHeartbeats.getOrDefault(peer, 0);
            System.out.println(peer + " → "
                    + (alive ? "ALIVE" : "DEAD (missed " + misses + " heartbeats)"));
        }
        System.out.println("----------------------\n");
    }

    public boolean isAlive(String nodeAddress) {
        return nodeStatus.getOrDefault(nodeAddress, true);
    }
}