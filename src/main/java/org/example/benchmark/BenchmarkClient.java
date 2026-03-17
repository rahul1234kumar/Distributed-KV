package org.example.benchmark;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkClient {

    private static final String   HOST           = "localhost";
    private static final int[]    PORTS          = {7001, 7002, 7003};
    private static final int      TOTAL_OPS      = 10_000;
    private static final int      THREAD_COUNT   = 10;
    private static final int      OPS_PER_THREAD = TOTAL_OPS / THREAD_COUNT;

    // Keep connections alive across all benchmark phases
    // Thread i → persistent socket to PORTS[i % PORTS.length]
    private static Socket[]        sockets;
    private static PrintWriter[]   writers;
    private static BufferedReader[] readers;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  KVraft Benchmark");
        System.out.println("  " + THREAD_COUNT + " threads x "
                + OPS_PER_THREAD + " ops = " + TOTAL_OPS + " total ops");
        System.out.println("  Nodes: 7001, 7002, 7003 (round-robin)");
        System.out.println("========================================\n");

        // Open all connections ONCE — reuse across all phases
        // This completely avoids TIME_WAIT issues on Windows
        openConnections();

        // Warmup
        System.out.println("Warming up...");
        runBenchmark("PUT", 500, 2, false);
        System.out.println("Warmup done.\n");

        // PUT benchmark
        System.out.println("--- PUT Benchmark (writes) ---");
        runBenchmark("PUT", TOTAL_OPS, THREAD_COUNT, true);
        System.out.println();

        // GET benchmark — no seeding needed, PUT already wrote the keys
        System.out.println("--- GET Benchmark (reads) ---");
        runBenchmark("GET", TOTAL_OPS, THREAD_COUNT, true);

        // Close all connections at the very end
        closeConnections();

        System.out.println("\n========================================");
        System.out.println("  Benchmark complete.");
        System.out.println("========================================");
    }

    /**
     * Open one persistent TCP connection per thread.
     * Distributed across nodes in round-robin.
     * Thread 0,3,6,9 → 7001
     * Thread 1,4,7   → 7002
     * Thread 2,5,8   → 7003
     */
    private static void openConnections() throws IOException {
        sockets = new Socket[THREAD_COUNT];
        writers  = new PrintWriter[THREAD_COUNT];
        readers  = new BufferedReader[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            int port = PORTS[i % PORTS.length];
            sockets[i] = new Socket(HOST, port);
            writers[i] = new PrintWriter(sockets[i].getOutputStream(), true);
            readers[i] = new BufferedReader(
                    new InputStreamReader(sockets[i].getInputStream()));
            System.out.println("Thread-" + i
                    + " connected to port " + port);
        }
        System.out.println();
    }

    private static void closeConnections() {
        for (int i = 0; i < THREAD_COUNT; i++) {
            try {
                if (sockets[i] != null) sockets[i].close();
            } catch (IOException ignored) {}
        }
    }

    private static void runBenchmark(String type, int totalOps,
                                     int threads, boolean print)
            throws InterruptedException {
        int opsPerThread = totalOps / threads;

        List<Long> latencies     = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        ExecutorService executor  = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    // Use pre-opened connection — no new socket
                    PrintWriter out   = writers[threadId];
                    BufferedReader in = readers[threadId];

                    for (int i = 0; i < opsPerThread; i++) {
                        String key   = "key-" + threadId + "-" + i;
                        String value = "value-" + i;

                        String command = type.equals("PUT")
                                ? "PUT " + key + " " + value
                                : "GET " + key;

                        long opStart = System.nanoTime();
                        out.println(command);
                        String response = in.readLine();
                        long latencyMicros = (System.nanoTime() - opStart) / 1000;

                        latencies.add(latencyMicros);

                        if (response != null && !response.startsWith("ERROR")) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                            if (failCount.get() <= 3) {
                                System.out.println("FAIL: " + response);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Thread-" + threadId
                            + " error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        doneLatch.await();
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        if (print) {
            printResults(type, totalOps, elapsed,
                    successCount.get(), failCount.get(), latencies);
        }
    }

    private static void printResults(String type, int totalOps,
                                     long elapsedMs, int success,
                                     int failures, List<Long> latencies) {
        double throughput = (success * 1000.0) / elapsedMs;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        long min = sorted.isEmpty() ? 0 : sorted.get(0);
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

        System.out.println("Type        : " + type);
        System.out.println("Total ops   : " + totalOps);
        System.out.println("Successful  : " + success);
        System.out.println("Failed      : " + failures);
        System.out.println("Time        : " + elapsedMs + "ms");
        System.out.printf ("Throughput  : %.0f ops/sec%n", throughput);
        System.out.println("Latency:");
        System.out.println("  min       : " + min + "μs");
        System.out.println("  P50       : " + p50 + "μs ("
                + (p50 / 1000) + "ms)");
        System.out.println("  P95       : " + p95 + "μs ("
                + (p95 / 1000) + "ms)");
        System.out.println("  P99       : " + p99 + "μs ("
                + (p99 / 1000) + "ms)");
        System.out.println("  max       : " + max + "μs ("
                + (max / 1000) + "ms)");
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}