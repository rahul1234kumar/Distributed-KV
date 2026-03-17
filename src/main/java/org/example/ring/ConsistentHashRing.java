package org.example.ring;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent Hashing Ring.
 *
 * Mental model: imagine a clock face from 0 to 2^32.
 * Nodes sit at positions on this clock.
 * A key hashes to a position, then we go clockwise
 * until we hit a node — that node owns this key.
 *
 * We use "virtual nodes" — each physical node gets
 * multiple positions on the ring for better load balance.
 */
public class ConsistentHashRing {

    // TreeMap keeps entries SORTED by key automatically.
    // key   = position on the ring (a long, 0 to 2^32)
    // value = node address, e.g. "localhost:7001"
    private final SortedMap<Long, String> ring = new TreeMap<>();

    // How many virtual positions each physical node gets.
    // More vnodes = better balance, but more memory.
    // 100 is a reasonable default for a 3-node cluster.
    private final int virtualNodes;

    public ConsistentHashRing(int virtualNodes) {
        this.virtualNodes = virtualNodes;
    }

    /**
     * Add a node to the ring.
     * e.g. addNode("localhost:7001")
     *
     * We add `virtualNodes` positions for this node,
     * each with a slightly different hash input.
     */
    public void addNode(String nodeAddress) {
        for (int i = 0; i < virtualNodes; i++) {
            // "localhost:7001-vnode-0", "localhost:7001-vnode-1", etc.
            // Each gets a different position on the ring
            long position = hash(nodeAddress + "-vnode-" + i);
            ring.put(position, nodeAddress);
        }
        System.out.println("Added node: " + nodeAddress +
            " (" + virtualNodes + " virtual nodes)");
    }

    /**
     * Remove a node from the ring.
     * Called when a node dies or is decommissioned.
     */
    public void removeNode(String nodeAddress) {
        for (int i = 0; i < virtualNodes; i++) {
            long position = hash(nodeAddress + "-vnode-" + i);
            ring.remove(position);
        }
        System.out.println("Removed node: " + nodeAddress);
    }

    /**
     * The core method: given a key, which node owns it?
     *
     * Steps:
     * 1. Hash the key to a position on the ring
     * 2. Find the first node at or after that position (clockwise)
     * 3. If no node after it, wrap around to the first node
     */
    public String getNode(String key) {
        if (ring.isEmpty()) return null;

        long position = hash(key);

        // tailMap returns everything with position >= our hash.
        // This is "going clockwise from our position".
        SortedMap<Long, String> tail = ring.tailMap(position);

        if (tail.isEmpty()) {
            // We've gone past the end of the ring — wrap around
            // to the very first node (lowest position).
            return ring.get(ring.firstKey());
        }

        // First entry in tail = closest clockwise node
        return ring.get(tail.firstKey());
    }

    /**
     * Get N distinct physical nodes starting from the key's position.
     * Used for replication — we need to find the leader + 2 followers.
     *
     * e.g. getNodes("city", 3) returns ["localhost:7002", "localhost:7003", "localhost:7001"]
     */
    public List<String> getNodes(String key, int count) {
        List<String> result = new ArrayList<>();
        if (ring.isEmpty()) return result;

        long position = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(position);

        // Iterate clockwise from key's position
        // We go around the whole ring if needed (wrapping)
        for (String node : tail.values()) {
            if (!result.contains(node)) {
                result.add(node);
            }
            if (result.size() == count) return result;
        }

        // Wrap around: check from beginning of ring
        for (String node : ring.values()) {
            if (!result.contains(node)) {
                result.add(node);
            }
            if (result.size() == count) return result;
        }

        return result;
    }

    /**
     * MD5 hash of a string → a long position on the ring.
     *
     * Why MD5? Not for security — just uniform distribution.
     * We take the first 4 bytes and convert to a long (0 to 2^32).
     *
     * Why & 0xFFFFFFFFL? To ensure the result is always positive.
     * Java's int is signed (-2^31 to 2^31). We want 0 to 2^32.
     * The L suffix makes it a long. The mask strips the sign bit.
     */
    private long hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));

            // Take first 4 bytes, pack into a long
            long h = 0;
            for (int i = 0; i < 4; i++) {
                // digest[i] & 0xFF converts signed byte to unsigned int
                // (h << 8) shifts left to make room for next byte
                h = (h << 8) | (digest[i] & 0xFF);
            }

            // Ensure positive: mask to 32 bits
            return h & 0xFFFFFFFFL;

        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }

    // Handy for debugging — print the ring
    public void printRing() {
        System.out.println("\n--- Ring state ---");
        ring.forEach((pos, node) ->
            System.out.printf("  Position %10d → %s%n", pos, node));
        System.out.println("------------------\n");
    }
}