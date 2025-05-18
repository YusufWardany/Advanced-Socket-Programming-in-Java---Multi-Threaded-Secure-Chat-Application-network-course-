import javax.net.ssl.*;
import java.io.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

public class SecureChatClient {
    private SSLSocket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private String username;

    public SecureChatClient(String host, int port, String username) throws Exception {
        this.username = username;

        // Warning: This trust manager accepts all certificates - UNSAFE for production!
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    public void checkClientTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
        };

        // Initialize SSL context
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAllCerts, new java.security.SecureRandom());

        // Create socket
        SSLSocketFactory ssf = context.getSocketFactory();
        socket = (SSLSocket) ssf.createSocket(host, port);

        // Enable all supported cipher suites
        socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());

        // Create I/O streams
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);

        System.out.println("Connected to secure chat server at " + host + ":" + port);

        // Send username first
        writer.println(username);
    }

    public void start() {
        new Thread(this::receiveMessages).start();
        sendMessages();
    }

    private void receiveMessages() {
        try {
            String message;
            while ((message = reader.readLine()) != null) {
                System.out.println(message);
            }
        } catch (IOException e) {
            System.out.println("Disconnected from server");
        } finally {
            closeResources();
        }
    }

    private void sendMessages() {
        Scanner scanner = new Scanner(System.in);
        try {
            while (true) {
                String message = scanner.nextLine();
                if ("/quit".equalsIgnoreCase(message)) {
                    writer.println(message);
                    break;
                }
                writer.println(message);
            }
        } finally {
            closeResources();
            scanner.close();
        }
    }

    private void closeResources() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.out.println("Usage: java SecureChatClient <host> <port> <username>");
                return;
            }

            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String username = args[2];

            SecureChatClient client = new SecureChatClient(host, port, username);
            client.start();
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}