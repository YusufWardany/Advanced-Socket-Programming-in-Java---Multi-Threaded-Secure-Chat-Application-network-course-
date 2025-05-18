import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class SecureChatServer {
    private SSLServerSocket serverSocket;
    private ExecutorService threadPool;
    private Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());
    private static final int MAX_THREADS = 20;

    char[] passphrase = "password".toCharArray();

    public SecureChatServer(int port) throws Exception {
        // Initialize SSL context with keystore
        char[] passphrase = "password".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream("keystore.jks"), passphrase);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, passphrase);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory ssf = context.getServerSocketFactory();
        serverSocket = (SSLServerSocket) ssf.createServerSocket(port);

        // Configure SSL settings
        serverSocket.setEnabledCipherSuites(serverSocket.getSupportedCipherSuites());
        serverSocket.setNeedClientAuth(false); // Set to true for mutual authentication

        threadPool = Executors.newFixedThreadPool(MAX_THREADS);
        System.out.println("Secure chat server started on port " + port);
    }

    public void start() {
        try {
            while (!serverSocket.isClosed()) {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clients.add(clientHandler);
                threadPool.execute(clientHandler);
            }
        } catch (IOException e) {
            shutdown();
        }
    }

    public void broadcast(String message, ClientHandler excludeClient) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient) {
                    client.sendMessage(message);
                }
            }
        }
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
        broadcast(client.getUsername() + " has left the chat!", null);
    }

    public void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdown();
            clients.forEach(ClientHandler::close);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ClientHandler implements Runnable {
        private SSLSocket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private String username;

        public ClientHandler(SSLSocket socket) {
            this.socket = socket;
            try {
                this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                this.writer = new PrintWriter(socket.getOutputStream(), true);

                // First message from client is username
                this.username = reader.readLine();
                broadcast(username + " has joined the chat!", this);
            } catch (IOException e) {
                close();
            }
        }

        @Override
        public void run() {
            try {
                String message;
                while ((message = reader.readLine()) != null) {
                    if (message.equalsIgnoreCase("/quit")) {
                        break;
                    }
                    broadcast(username + ": " + message, this);
                }
            } catch (IOException e) {
                // Client disconnected unexpectedly
            } finally {
                removeClient(this);
                close();
            }
        }

        public void sendMessage(String message) {
            writer.println(message);
        }

        public String getUsername() {
            return username;
        }

        public void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("Usage: java SecureChatServer <port>");
                return;
            }

            int port = Integer.parseInt(args[0]);
            SecureChatServer server = new SecureChatServer(port);

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

            server.start();
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}