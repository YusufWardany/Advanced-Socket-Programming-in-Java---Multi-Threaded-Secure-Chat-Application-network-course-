import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class MultiThreadedServer {
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public MultiThreadedServer(int port) {
        try {
            serverSocket = new ServerSocket(port);
            threadPool = Executors.newCachedThreadPool();
            System.out.println("Multi-threaded server started on port " + port);
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port);
            e.printStackTrace();
        }
    }

    public void start() {
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());

                // Handle client connection in a new thread
                threadPool.execute(new ClientHandler(clientSocket));

            } catch (IOException e) {
                System.err.println("Error accepting client connection");
                e.printStackTrace();
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
            ) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    System.out.println("Received from client " + clientSocket.getInetAddress() + ": " + inputLine);

                    // Parse JSON message
                    try {
                        JSONObject message = new JSONObject(inputLine);
                        String response = processMessage(message);
                        out.println(response);
                    } catch (JSONException e) {
                        out.println("{\"error\": \"Invalid message format\"}");
                    }
                }
            } catch (IOException e) {
                System.err.println("Error handling client connection");
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket");
                }
            }
        }

        private String processMessage(JSONObject message) {
            JSONObject response = new JSONObject();
            try {
                response.put("status", "success");
                response.put("received_at", System.currentTimeMillis());
                response.put("original_message", message);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return response.toString();
        }
    }

    public static void main(String[] args) {
        MultiThreadedServer server = new MultiThreadedServer(8081);
        server.start();
    }
}