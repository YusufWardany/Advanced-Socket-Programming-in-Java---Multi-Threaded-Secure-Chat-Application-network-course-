import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

public class ChatClient {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner consoleReader;
    private String nickname;
    private Map<String, FileTransfer> activeTransfers = new HashMap<>();
    private String host;
    private int port;

    public ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        try {
            // Initialize connection
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            consoleReader = new Scanner(System.in);

            System.out.println("Connected to chat server at " + host + ":" + port);

            // Set nickname first
            setNickname();

            // Start message receiver thread
            new Thread(this::receiveMessages).start();

            // Start message sender loop
            sendMessages();

        } catch (IOException e) {
            System.err.println("Error connecting to server: " + e.getMessage());
        } finally {
            closeResources();
        }
    }

    private void setNickname() {
        System.out.print("Enter your nickname: ");
        nickname = consoleReader.nextLine();
        JSONObject message = new JSONObject();
        message.put("type", "set_nickname");
        message.put("nickname", nickname);
        out.println(message.toString());
    }

    private void receiveMessages() {
        try {
            String response;
            while ((response = in.readLine()) != null) {
                processServerResponse(response);
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("Error receiving messages: " + e.getMessage());
            }
        }
    }

    private void sendMessages() {
        System.out.println("Enter messages (type '/file <recipient> <path>' to send a file):");
        while (true) {
            String input = consoleReader.nextLine();

            if (input.equalsIgnoreCase("/exit")) {
                closeResources();
                System.exit(0);
            }
            else if (input.startsWith("/file ")) {
                handleFileCommand(input);
            }
            else {
                sendChatMessage(input);
            }
        }
    }

    private void handleFileCommand(String input) {
        String[] parts = input.split(" ", 3);
        if (parts.length == 3) {
            startFileTransfer(parts[1], parts[2]);
        } else {
            System.out.println("Invalid format. Use: /file recipient path");
        }
    }

    private void sendChatMessage(String message) {
        JSONObject json = new JSONObject();
        json.put("type", "public_message");
        json.put("content", message);
        out.println(json.toString());
    }

    private void processServerResponse(String response) {
        try {
            JSONObject json = new JSONObject(response);
            String type = json.optString("type", "");

            switch (type) {
                case "public_message":
                    System.out.println(json.getString("sender") + ": " + json.getString("content"));
                    break;

                case "private_message":
                    System.out.println("[Private from " + json.getString("sender") + "]: " +
                            json.getString("content"));
                    break;

                case "file_transfer_request":
                    handleFileTransferRequest(json);
                    break;

                case "file_chunk":
                    handleFileChunk(json);
                    break;

                case "file_transfer_progress":
                    System.out.println("Transfer progress: " + json.getInt("progress") + "%");
                    break;

                case "file_transfer_complete":
                    handleTransferCompletion(json);
                    break;

                case "system_message":
                    System.out.println("[System] " + json.getString("content"));
                    break;

                case "error":
                    System.err.println("Error: " + json.getString("message"));
                    break;

                default:
                    System.out.println("Unknown message: " + response);
            }
        } catch (Exception e) {
            System.out.println("Server: " + response); // Fallback for non-JSON messages
        }
    }

    private void startFileTransfer(String recipient, String filePath) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "file_transfer");
            message.put("recipient", recipient);
            message.put("file_path", filePath);
            out.println(message.toString());
        } catch (JSONException e) {
            System.err.println("Error creating file transfer message: " + e.getMessage());
        }
    }

    private void handleFileTransferRequest(JSONObject request) throws JSONException {
        String transferId = request.getString("transfer_id");
        String sender = request.getString("sender");
        String fileName = request.getString("file_name");
        long fileSize = request.getLong("file_size");
        int totalChunks = request.getInt("chunk_count");

        System.out.println("\nIncoming file from " + sender + ":");
        System.out.println("File: " + fileName);
        System.out.println("Size: " + fileSize + " bytes");
        System.out.print("Accept this file? (y/n): ");

        String answer = consoleReader.nextLine();
        JSONObject response = new JSONObject();
        response.put("type", "file_transfer_response");
        response.put("transfer_id", transferId);
        response.put("accepted", answer.equalsIgnoreCase("y"));
        out.println(response.toString());

        if (answer.equalsIgnoreCase("y")) {
            activeTransfers.put(transferId, new FileTransfer(fileName, fileSize, totalChunks));
            System.out.println("Ready to receive file...");
        }
    }

    private void handleFileChunk(JSONObject chunk) throws JSONException {
        String transferId = chunk.getString("transfer_id");
        FileTransfer transfer = activeTransfers.get(transferId);
        if (transfer != null) {
            int chunkIndex = chunk.getInt("chunk_index");
            byte[] data = Base64.getDecoder().decode(chunk.getString("chunk_data"));
            transfer.addChunk(chunkIndex, data);
        }
    }

    private void handleTransferCompletion(JSONObject json) throws JSONException {
        String transferId = json.getString("transfer_id");
        FileTransfer transfer = activeTransfers.get(transferId);

        if (transfer != null) {
            try {
                if (transfer.isComplete()) {
                    transfer.assembleFile();
                    System.out.println("File transfer complete. Saved as: " + transfer.getFileName());
                } else {
                    System.out.println("Warning: File transfer incomplete");
                }
            } catch (IOException e) {
                System.err.println("Error saving file: " + e.getMessage());
            } finally {
                activeTransfers.remove(transferId);
            }
        }
    }


    private void closeResources() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
            if (consoleReader != null) consoleReader.close();

            // Clean up incomplete transfers
            for (FileTransfer transfer : activeTransfers.values()) {
                transfer.cleanup();
            }
            activeTransfers.clear();
        } catch (IOException e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.out.println("Starting chat client...");
        ChatClient client = new ChatClient("localhost", 8080);
        client.start();
    }
}