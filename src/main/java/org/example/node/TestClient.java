package org.example.node;

import java.io.*;
import java.net.Socket;

public class TestClient {
    public static void main(String[] args) throws Exception {
        Socket socket = new Socket("localhost", 7001);
        BufferedReader in = new BufferedReader(
            new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        // Test PUT
        out.println("PUT name Rahul");
        System.out.println("PUT name Rahul → " + in.readLine());

        // Test GET
        out.println("GET name");
        System.out.println("GET name → " + in.readLine());

        // Test DELETE
        out.println("DELETE name");
        System.out.println("DELETE name → " + in.readLine());

        // Test GET after delete
        out.println("GET name");
        System.out.println("GET name (after delete) → " + in.readLine());

        socket.close();
    }
}
