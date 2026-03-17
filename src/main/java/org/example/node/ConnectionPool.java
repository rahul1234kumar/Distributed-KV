//package org.example.node;
//
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ArrayBlockingQueue;
//
///**
// * Connection pool with multiple connections per peer node.
// * 3 connections per peer → 3 concurrent replications per target
// * without blocking each other.
// */
//public class ConnectionPool {
//
//    private static final int POOL_SIZE = 3;
//
//    // key = "localhost:7002"
//    // value = queue of available connections
//    private static final Map<String, ArrayBlockingQueue<NodeConnection>> pools
//        = new ConcurrentHashMap<>();
//
//    public static String send(String nodeAddress, String command) {
//        ArrayBlockingQueue<NodeConnection> pool = pools.computeIfAbsent(
//            nodeAddress, addr -> {
//                ArrayBlockingQueue<NodeConnection> q =
//                    new ArrayBlockingQueue<>(POOL_SIZE);
//                String[] parts = addr.split(":");
//                for (int i = 0; i < POOL_SIZE; i++) {
//                    q.offer(new NodeConnection(
//                        parts[0], Integer.parseInt(parts[1])));
//                }
//                return q;
//            });
//
//        NodeConnection conn = null;
//        try {
//            // grab an available connection (blocks if all in use)
//            conn = pool.poll(2, java.util.concurrent.TimeUnit.SECONDS);
//            if (conn == null) return "ERROR connection pool timeout";
//            return conn.send(command);
//        } catch (InterruptedException e) {
//            return "ERROR interrupted";
//        } finally {
//            // always return connection to pool
//            if (conn != null) pool.offer(conn);
//        }
//    }
//}