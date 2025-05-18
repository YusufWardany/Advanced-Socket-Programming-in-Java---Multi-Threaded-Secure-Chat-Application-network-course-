import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {
    private ServerSocket loadBalancerSocket;
    private List<String> serverHosts;
    private List<Integer> serverPorts;
    private AtomicInteger currentServer = new AtomicInteger(0);

    public LoadBalancer(int lbPort, List<String> hosts, List<Integer> ports) {
        this.serverHosts = hosts;
        this.serverPorts = ports;

        try {
            loadBalancerSocket = new ServerSocket(lbPort);
            System.out.println("Load balancer started on port " + lbPort);
        } catch (IOException e) {
            System.err.println("Could not start load balancer");
            e.printStackTrace();
        }
    }

    public void start() {
        while (true) {
            try {
                Socket clientSocket = loadBalancerSocket.accept();
                System.out.println("New client connected to load balancer");

                // Round-robin selection
                int serverIndex = currentServer.getAndIncrement() % serverHosts.size();
                String host = serverHosts.get(serverIndex);
                int port = serverPorts.get(serverIndex);

                // Connect to selected server
                Socket serverSocket = new Socket(host, port);

                // Start two threads to handle bidirectional communication
                new Thread(new LoadBalancerHandler(clientSocket, serverSocket)).start();

            } catch (IOException e) {
                System.err.println("Error in load balancer");
                e.printStackTrace();
            }
        }
    }

    private static class LoadBalancerHandler implements Runnable {

        private Socket clientSocket;
        private Socket serverSocket;

        public LoadBalancerHandler(Socket clientSocket, Socket serverSocket) {
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