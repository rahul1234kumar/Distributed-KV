package org.example.node;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replicator sends REPLICATE commands to follower nodes.
 *
 * Quorum write (W=2):
 *   - Leader already wrote locally (that's 1)
 *   - We need 1 more ack from followers to satisfy W=2
 *   - We replicate to ALL followers but return OK after first ack
 *   - If a follower is down, we skip it and try the next one
 */
public class Replicator {

    /**
     * Replicate to followers and return how many succeeded.
     * Caller uses this to check if quorum was reached.
     */
    public static int replicate(List<String> followers,
                                String key, String value, long version) {
        AtomicInteger successCount = new AtomicInteger(0);

        for (String node : followers) {
            String result = sendReplicate(node, key, value, version);
            if (result.equals("OK")) {
                successCount.incrementAndGet();
                System.out.println("Replicated [" + key + "=" + value
                        + "] to " + node + " ✓");
            } else {
                System.out.println("Replication FAILED to " + node
                        + " → " + result);
            }
        }

        return successCount.get();
    }

    private static String sendReplicate(String nodeAddress,
                                        String key, String value, long version) {
        String[] parts = nodeAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try (
                Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()))
        ) {
            // Send key, value AND version so follower stores same version
            out.println("REPLICATE " + key + " " + value + " " + version);
            return in.readLine();

        } catch (IOException e) {
            return "ERROR unreachable: " + nodeAddress;
        }
    }
}