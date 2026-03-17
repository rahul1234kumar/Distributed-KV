package org.example;

// Press Shift twice to open the Search Everywhere dialog and type `show whitespaces`,
// then press Enter. You can now see whitespace characters in your code.

import org.example.node.Node;

import java.util.Arrays;
import java.util.List;

public class Main {
    /**
     * Run with: java Main <port> <peer1> <peer2> ...
     * Example:  java Main 7001 localhost:7001 localhost:7002 localhost:7003
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Main <port> <peer1> <peer2> ...");
            System.out.println("Example: java Main 7001 localhost:7001 localhost:7002 localhost:7003");
            return;
        }

        int port = Integer.parseInt(args[0]);

        // args[1], args[2], args[3] are the peer addresses
        List<String> peers = Arrays.asList(
                Arrays.copyOfRange(args, 1, args.length)
        );

        new Node(port, peers).start();
    }
}