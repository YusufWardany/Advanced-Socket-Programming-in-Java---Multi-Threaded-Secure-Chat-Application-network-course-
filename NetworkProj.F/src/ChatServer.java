import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class ChatServer {
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;
    private Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private Map<String, String> nicknames = new ConcurrentHashMap<>();

    // Message types
    private static final String TYPE_FILE_TRANSFER = "file_transfer";
    private static final String TYPE_FILE_INFO = "file_transfer_info";
    private static final String TYPE_FILE_CHUNK = "file_chunk";
    private static final String TYPE_PROGRESS = "file_transfer_progress";
    private static final String TYPE_COMPLETE = "file_transfer_complete";

    public ChatServer(int port) {
        try {
            serverSocket = new ServerSocket(port);
            threadPool = Executors.newCachedThreadPool();
            isRunning = true;
            System.out.println("Chat server started on port " + port);
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port);
            e.printStackTrace();
        }
    }

    public ChatServer(int preferredPort, int maxAttempts) {
        for (int port = preferredPort; port < preferredPort + maxAttempts; port++) {
            try {
                serverSocket = new ServerSocket(port);
                threadPool = Executors.newCachedThreadPool();
                isRunning = true;
                System.out.println("Server started on port " + port);
                return;
            } catch (IOException e) {
                System.out.println("Port " + port + " busy, trying next...");
            }
        }
        System.err.println("Could not start server on any port");
    }

    public boolean isRunning() {
        return isRunning && serverSocket != null && !serverSocket.isClosed();
    }

    public void start() {
        if (!isRunning()) {
            System.err.println("Server cannot start - socket not initialized");
            return;
        }

        System.out.println("Waiting for client connections...");
        while (isRunning()) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket);
                clients.put(handler.getClientId(), handler);
                threadPool.execute(handler);

            } catch (SocketException e) {
                if (isRunning()) {
                    System.err.println("Server socket closed");
                }
            } catch (IOException e) {
                if (isRunning()) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        // Disconnect all clients
        for (ClientHandler handler : clients.values()) {
            handler.cleanup();
        }
        clients.clear();
        nicknames.clear();

        System.out.println("Server stopped");
    }

    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private BufferedReader in;
        private PrintWriter out;
        private final String clientId;
        private String nickname;
        private final Map<String, FileTransfer> activeTransfers = new ConcurrentHashMap<>();

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
            this.clientId = socket.getInetAddress() + ":" + socket.getPort();
        }

        public String getClientId() {
            return clientId;
        }

        public String getNickname() {
            return nickname != null ? nickname : "unknown";
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out = new PrintWriter(clientSocket.getOutputStream(), true);

                String inputLine;
                while ((inputLine = in.readLine()) != null && isRunning()) {
                    processMessage(inputLine);
                }
            } catch (IOException e) {
                System.err.println("Error with client " + clientId + ": " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void processMessage(String message) {
            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type", "");

                switch (type) {
                    case "set_nickname":
                        handleSetNickname(json);
                        break;
                    case "public_message":
                        handlePublicMessage(json);
                        break;
                    case "private_message":
                        handlePrivateMessage(json);
                        break;
                    case TYPE_FILE_TRANSFER:
                        handleFileTransfer(json);
                        break;
                    case TYPE_FILE_INFO:
                        handleFileTransferInfo(json);
                        break;
                    case TYPE_FILE_CHUNK:
                        handleFileChunk(json);
                        break;
                    case "file_transfer_response":
                        handleFileTransferResponse(json);
                        break;
                    default:
                        sendError("Unknown message type: " + type);
                }
            } catch (JSONException e) {
                sendError("Invalid message format: " + e.getMessage());
            } catch (Exception e) {
                sendError("Error processing message: " + e.getMessage());
            }
        }

        private void handleFileTransfer(JSONObject message) throws JSONException {
            if (nickname == null) {
                sendError("You must set a nickname first");
                return;
            }

            String recipient = message.getString("recipient");
            String filePath = message.getString("file_path");

            File file = new File(filePath);
            if (!file.exists()) {
                sendError("File not found");
                return;
            }

            String transferId = UUID.randomUUID().toString();
            long fileSize = file.length();
            String fileName = file.getName();
            int chunkSize = 4096; // 4KB chunks
            long chunkCount = (fileSize + chunkSize - 1) / chunkSize;

            JSONObject fileInfo = new JSONObject();
            fileInfo.put("type", TYPE_FILE_INFO);
            fileInfo.put("sender", nickname);
            fileInfo.put("recipient", recipient);
            fileInfo.put("file_name", fileName);
            fileInfo.put("file_size", fileSize);
            fileInfo.put("chunk_size", chunkSize);
            fileInfo.put("chunk_count", chunkCount);
            fileInfo.put("transfer_id", transferId);

            ClientHandler recipientHandler = findClientByNickname(recipient);
            if (recipientHandler != null) {
                recipientHandler.sendMessage(fileInfo.toString());

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[chunkSize];
                    int bytesRead;
                    int chunkIndex = 0;

                    while ((bytesRead = fis.read(buffer)) != -1 && isRunning()) {
                        JSONObject chunkInfo = new JSONObject();
                        chunkInfo.put("type", TYPE_FILE_CHUNK);
                        chunkInfo.put("transfer_id", transferId);
                        chunkInfo.put("chunk_index", chunkIndex);
                        chunkInfo.put("chunk_data", Base64.getEncoder().encodeToString(
                                Arrays.copyOf(buffer, bytesRead)));

                        recipientHandler.sendMessage(chunkInfo.toString());
                        chunkIndex++;

                        JSONObject progress = new JSONObject();
                        progress.put("type", TYPE_PROGRESS);
                        progress.put("transfer_id", transferId);
                        progress.put("progress", (int) ((chunkIndex * 100) / chunkCount));
                        sendMessage(progress.toString());

                        Thread.sleep(10);
                    }

                    JSONObject completion = new JSONObject();
                    completion.put("type", TYPE_COMPLETE);
                    completion.put("transfer_id", transferId);
                    completion.put("status", "success");
                    sendMessage(completion.toString());
                    recipientHandler.sendMessage(completion.toString());

                } catch (IOException | InterruptedException e) {
                    sendError("File transfer failed: " + e.getMessage());
                }
            } else {
                sendError("Recipient not found");
            }
        }

        private ClientHandler findClientByNickname(String nickname) {
            for (Map.Entry<String, String> entry : nicknames.entrySet()) {
                if (entry.getValue().equals(nickname)) {
                    return clients.get(entry.getKey());
                }
            }
            return null;
        }

        private void handleFileTransferInfo(JSONObject message) throws JSONException {
            String transferId = message.getString("transfer_id");
            String sender = message.getString("sender");
            String fileName = message.getString("file_name");
            long fileSize = message.getLong("file_size");
            int chunkCount = message.getInt("chunk_count");

            JSONObject response = new JSONObject();
            response.put("type", "file_transfer_request");
            response.put("transfer_id", transferId);
            response.put("sender", sender);
            response.put("file_name", fileName);
            response.put("file_size", fileSize);
            sendMessage(response.toString());

            activeTransfers.put(transferId, new FileTransfer(fileName, fileSize, chunkCount));
        }

        private void handleFileChunk(JSONObject message) throws JSONException {
            String transferId = message.getString("transfer_id");
            int chunkIndex = message.getInt("chunk_index");
            String chunkData = message.getString("chunk_data");

            FileTransfer transfer = activeTransfers.get(transferId);
            if (transfer != null) {
                transfer.addChunk(chunkIndex, Base64.getDecoder().decode(chunkData));

                JSONObject progress = new JSONObject();
                progress.put("type", TYPE_PROGRESS);
                progress.put("transfer_id", transferId);
                progress.put("progress", transfer.getProgress());
                sendMessage(progress.toString());
            }
        }

        private void handleFileTransferResponse(JSONObject response) throws JSONException {
            String transferId = response.getString("transfer_id");
            boolean accepted = response.getBoolean("accepted");

            if (!accepted) {
                sendError("Recipient declined file transfer");
                activeTransfers.remove(transferId);
            }
        }

        private void handleSetNickname(JSONObject message) throws JSONException {
            String newNickname = message.getString("nickname");
            if (newNickname == null || newNickname.trim().isEmpty()) {
                sendError("Nickname cannot be empty");
                return;
            }

            if (nicknames.containsValue(newNickname)) {
                sendError("Nickname already in use");
                return;
            }

            if (this.nickname != null) {
                nicknames.remove(clientId);
            }

            this.nickname = newNickname;
            nicknames.put(clientId, newNickname);

            JSONObject response = new JSONObject();
            response.put("type", "nickname_set");
            response.put("status", "success");
            response.put("nickname", newNickname);
            sendMessage(response.toString());

            broadcastSystemMessage(nickname + " has joined the chat");
        }

        private void handlePublicMessage(JSONObject message) throws JSONException {
            if (nickname == null) {
                sendError("You must set a nickname first");
                return;
            }

            String content = message.getString("content");
            if (content == null || content.trim().isEmpty()) {
                sendError("Message cannot be empty");
                return;
            }

            JSONObject broadcast = new JSONObject();
            broadcast.put("type", "public_message");
            broadcast.put("sender", nickname);
            broadcast.put("content", content);
            broadcast.put("timestamp", System.currentTimeMillis());

            broadcastMessage(broadcast);
        }

        private void handlePrivateMessage(JSONObject message) throws JSONException {
            if (nickname == null) {
                sendError("You must set a nickname first");
                return;
            }

            String recipient = message.getString("recipient");
            String content = message.getString("content");

            ClientHandler recipientHandler = findClientByNickname(recipient);
            if (recipientHandler != null) {
                JSONObject privateMsg = new JSONObject();
                privateMsg.put("type", "private_message");
                privateMsg.put("sender", nickname);
                privateMsg.put("recipient", recipient);
                privateMsg.put("content", content);
                privateMsg.put("timestamp", System.currentTimeMillis());

                recipientHandler.sendMessage(privateMsg.toString());

                JSONObject confirmation = new JSONObject();
                confirmation.put("type", "message_sent");
                confirmation.put("status", "success");
                confirmation.put("recipient", recipient);
                sendMessage(confirmation.toString());
            } else {
                sendError("Recipient not found");
            }
        }

        private void broadcastMessage(JSONObject message) {
            String jsonMessage = message.toString();
            for (ClientHandler handler : clients.values()) {
                if (handler != this) {
                    handler.sendMessage(jsonMessage);
                }
            }
        }

        private void broadcastSystemMessage(String content) {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("type", "system_message");
            systemMessage.put("content", content);
            systemMessage.put("timestamp", System.currentTimeMillis());
            broadcastMessage(systemMessage);
        }

        public void sendMessage(String message) {
            out.println(message);
        }

        private void sendError(String error) {
            JSONObject errorMsg = new JSONObject();
            errorMsg.put("type", "error");
            errorMsg.put("message", error);
            sendMessage(errorMsg.toString());
        }

        private void cleanup() {
            clients.remove(clientId);
            if (nickname != null) {
                nicknames.remove(clientId);
                broadcastSystemMessage(nickname + " has left the chat");
            }

            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error cleaning up client " + clientId);
            }

            // Clean up any incomplete file transfers
            for (FileTransfer transfer : activeTransfers.values()) {
                transfer.cleanup();
            }
            activeTransfers.clear();
        }
    }}
