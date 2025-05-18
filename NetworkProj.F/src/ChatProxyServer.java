import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatProxyServer {
    private ServerSocket proxySocket;
    private String mainServerHost;
    private int mainServerPort;

    public ChatProxyServer(int proxyPort, String mainHost, int mainPort) {
        this.mainServerHost = mainHost;
        this.mainServerPort = mainPort;

        try {
            proxySocket = new ServerSocket(proxyPort);
            System.out.println("Proxy server started on port " + proxyPort);
        } catch (IOException e) {
            System.err.println("Could not start proxy server");
            e.printStackTrace();
        }
    }

    public void start() {
        while (true) {
            try {
                Socket clientSocket = proxySocket.accept();
                System.out.println("New client connected to proxy");

                // Connect to main server
                Socket serverSocket = new Socket(mainServerHost, mainServerPort);

                // Start two threads to handle bidirectional communication
                new Thread(new ProxyHandler(clientSocket, serverSocket)).start();

            } catch (IOException e) {
                System.err.println("Error in proxy server");
                e.printStackTrace();
            }
        }
    }

    private static class ProxyHandler implements Runnable {
        private Socket clientSocket;
        private Socket serverSocket;

        public ProxyHandler(Socket clientSocket, Socket serverSocket) {
            this.clientSocket = clientSocket;
            this.serverSocket = serverSocket;
        }

        @Override
        public void run() {
            try (
                    BufferedReader clientIn = new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter clientOut = new PrintWriter(
                            clientSocket.getOutputStream(), true);
                    BufferedReader serverIn = new BufferedReader(
                            new InputStreamReader(serverSocket.getInputStream()));
                    PrintWriter serverOut = new PrintWriter(
                            serverSocket.getOutputStream(), true)
            ) {
                // Client to server
                new Thread(() -> {
                    String line;
                    try {
                        while ((line = clientIn.readLine()) != null) {
                            serverOut.println(line);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).start();

                // Server to client
                String line;
                while ((line = serverIn.readLine()) != null) {
                    clientOut.println(line);
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}