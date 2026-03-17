package org.example.node;

import org.example.heartbeat.HeartbeatManager;
import org.example.ring.ConsistentHashRing;

import java.io.*;
import java.net.Socket;
import java.util.List;

public class ClientHandler implements Runnable {

    // Quorum constants
    private static final int N = 3; // replication factor
    private static final int W = 2; // write quorum
    private static final int R = 2; // read quorum

    private final Socket socket;
    private final Store store;
    private final ConsistentHashRing ring;
    private final String myAddress;

    private final HeartbeatManager heartbeatManager;  // ← ADD THIS

    public ClientHandler(Socket socket, Store store,
                         ConsistentHashRing ring, String myAddress,
                         HeartbeatManager heartbeatManager) {  // ← ADD THIS
        this.socket = socket;
        this.store = store;
        this.ring = ring;
        this.myAddress = myAddress;
        this.heartbeatManager = heartbeatManager;  // ← ADD THIS
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                String response = handleCommand(line.trim());
                out.println(response);
            }
        } catch (IOException e) {
            System.out.println("Client disconnected");
        }
    }

    private String handleCommand(String
                                         line) {
        if (line.isEmpty()) return "ERROR empty command";
        String[] parts = line.split("\\s+");
        String command = parts[0].toUpperCase();

        // ── PING: heartbeat check from another node ────────────────
        // Just respond PONG immediately. No routing, no store access.
                if (command.equals("PING")) {
                    return "PONG";
                }

        // ── STATUS: print cluster health ───────────────────────────
        if (command.equals("STATUS")) {
            heartbeatManager.printStatus();
            return "OK";
        }

        // ── REPLICATE: internal write from leader ──────────────────────
        // Bypasses ring routing entirely — just store directly.
        // Called by Replicator when leader replicates to followers.
        if (command.equals("REPLICATE")) {
            if (parts.length < 4) return "ERROR usage: REPLICATE key value version";
            String key   = parts[1];
            String value = parts[2];
            long version = Long.parseLong(parts[3]);
            store.put(key, value, version);
            System.out.println("Replica stored: [" + key + "=" + value
                    + " v" + version + "] on " + myAddress);
            return "OK";
        }
        if (command.equals("REPLICATE_DELETE")) {
            if (parts.length < 2) return "ERROR usage: REPLICATE_DELETE key";
            store.delete(parts[1]);
            System.out.println("Replica deleted: [" + parts[1] + "] on " + myAddress);
            return "OK";
        }

        // ── INTERNAL_GET: internal read for quorum ─────────────────────
        // Bypasses routing — always reads from local store directly.
        // Returns "version:value" so caller can compare versions.
        // Called by quorumRead() on other nodes.
        if (command.equals("INTERNAL_GET")) {
            if (parts.length < 2) return "NOT_FOUND";
            Store.VersionedValue vv = store.get(parts[1]);
            if (vv == null) return "NOT_FOUND";
            return vv.serialize(); // e.g. "1710234567891:Bangalore"
        }

        if (parts.length < 2) return "ERROR missing key";
        String key = parts[1];

        // Ask ring: who is the leader for this key?
        String responsibleNode = ring.getNode(key);

        // ── NOT ME: forward to correct leader ─────────────────────────
        if (!myAddress.equals(responsibleNode)) {
            System.out.println("[" + myAddress + "] Routing "
                    + command + " " + key + " → " + responsibleNode);

            String response = Router.forward(responsibleNode, line);

            // Leader is unreachable — for GET, step up and run
            // quorum read ourselves. We have a replica of this key.
            if (response.startsWith("ERROR") && command.equals("GET")) {
                System.out.println("[" + myAddress + "] Leader "
                        + responsibleNode + " is down, running quorum read");
                return quorumRead(key);
            }

            return response;
        }

        // ── I AM THE LEADER for this key ──────────────────────────────
        return switch (command) {

            // ── QUORUM WRITE ──────────────────────────────────────────
            case "PUT" -> {
                if (parts.length < 3) yield "ERROR usage: PUT key value";
                String value = parts[2];

                // Step 1: write locally with timestamp as version
                long version = System.currentTimeMillis();
                store.put(key, value, version);
                System.out.println("Leader stored: [" + key + "="
                        + value + " v" + version + "] on " + myAddress);

                // Step 2: find followers (nodes after leader on ring)
                List<String> allNodes = ring.getNodes(key, N);
                List<String> followers = allNodes.subList(1, allNodes.size());

                // Step 3: replicate and count acks
                // leader already wrote = 1 ack
                // need W=2 total so need at least 1 follower ack
                int followerAcks = Replicator.replicate(followers, key, value, version);
                int totalAcks = 1 + followerAcks;

                if (totalAcks >= W) {
                    System.out.println("Quorum write reached: "
                            + totalAcks + "/" + N + " nodes acked (W=" + W + ")");
                    yield "OK";
                } else {
                    System.out.println("Quorum write FAILED: only "
                            + totalAcks + "/" + N + " nodes acked (W=" + W + ")");
                    yield "ERROR quorum not reached";
                }
            }

            // ── QUORUM READ ───────────────────────────────────────────
            case "GET" -> quorumRead(key);

            // ── DELETE ────────────────────────────────────────────────
            case "DELETE" -> {
                // Step 1: delete locally
                store.delete(key);
                System.out.println("Deleted: [" + key + "] on " + myAddress + " (leader)");

                // Step 2: replicate delete to followers
                List<String> allNodes = ring.getNodes(key, N);
                List<String> followers = allNodes.subList(1, allNodes.size());

                for (String follower : followers) {
                    String result = Router.forward(follower, "REPLICATE_DELETE " + key);
                    System.out.println("Delete replicated to " + follower + " → " + result);
                }

                yield "OK";
            }

            default -> "ERROR unknown command: " + command;
        };
    }

    // ── QUORUM READ ────────────────────────────────────────────────────
    // Reads from R=2 nodes, picks value with highest version.
    // Called by:
    //   1. Leader handling a GET normally
    //   2. Any node when leader is unreachable
    //
    // W + R > N (2 + 2 > 3) guarantees read and write sets
    // overlap by at least 1 node — so we always see latest write.
    private String quorumRead(String key) {
        List<String> allNodes = ring.getNodes(key, N);
        // allNodes = [leader, follower1, follower2]
        // if leader is dead, we skip it and read from followers

        Store.VersionedValue bestVal = null;
        int reads = 0;

        for (String node : allNodes) {

            if (reads >= R) break; // quorum reached, stop reading

            if (node.equals(myAddress)) {
                // Read from myself — no TCP needed, direct store access
                Store.VersionedValue local = store.get(key);
                if (local != null) {
                    reads++;
                    if (bestVal == null || local.version > bestVal.version) {
                        bestVal = local;
                    }
                    System.out.println("Quorum read local ["
                            + myAddress + "] → v" + local.version);
                }

            } else {
                // Read from another node via INTERNAL_GET over TCP
                String raw = Router.forward(node, "INTERNAL_GET " + key);

                if (raw != null && !raw.equals("NOT_FOUND")
                        && !raw.startsWith("ERROR")) {
                    reads++;
                    Store.VersionedValue followerVal =
                            Store.VersionedValue.deserialize(raw);

                    // Keep highest version — this is latest write wins
                    if (bestVal == null
                            || followerVal.version > bestVal.version) {
                        bestVal = followerVal;
                    }
                    System.out.println("Quorum read from " + node
                            + " → v" + followerVal.version);

                } else {
                    System.out.println("Quorum read FAILED from "
                            + node + " → " + raw);
                }
            }
        }

        // Full quorum achieved
        if (reads >= R && bestVal != null) {
            System.out.println("Quorum read succeeded: "
                    + reads + "/" + N + " (R=" + R + ")");
            return bestVal.value;
        }

        // Partial read — less than R nodes responded
        // Still return best known value rather than failing completely
        // This happens when 2 nodes are down simultaneously
        if (bestVal != null) {
            System.out.println("Partial read: only " + reads
                    + " nodes responded, returning best known value");
            return bestVal.value;
        }

        return "NOT_FOUND";
    }
}