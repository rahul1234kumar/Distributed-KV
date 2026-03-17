package org.example.node;

import java.io.*;
import java.net.Socket;

/**
 * Router forwards a command to another node via TCP
 * and returns its response.
 *
 * This is how Node 7001 talks to Node 7002:
 *   1. Open a TCP connection to 7002
 *   2. Send the command ("PUT city Bangalore")
 *   3. Read the response ("OK")
 *   4. Close connection
 *   5. Return response to original client
 */
public class Router {

    /**
     * Forward a raw command string to another node.
     *
     * @param nodeAddress "localhost:7002"
     * @param command     "PUT city Bangalore"
     * @return            "OK" or value or "NOT_FOUND"
     */
    public static String forward(String nodeAddress, String command) {
        // Split "localhost:7002" into host and port
        String[] parts = nodeAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        // Open a socket to the target node
        // try-with-resources closes socket automatically
        try (
            Socket socket = new Socket(host, port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))
        ) {
            out.println(command);        // send command
            return in.readLine();        // read one line response

        } catch (IOException e) {
            return "ERROR node unreachable: " + nodeAddress;
        }
    }
}