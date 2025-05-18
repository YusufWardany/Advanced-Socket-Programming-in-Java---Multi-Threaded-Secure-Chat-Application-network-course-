import org.json.JSONObject;

import static java.lang.System.out;

public class Main {
    public static void main(String[] args) {
        // Example message construction
        JSONObject message = new JSONObject();
        message.put("sender", "Alice");
        message.put("recipient", "Bob");
        message.put("content", "Hello, how are you?");
        message.put("timestamp", System.currentTimeMillis());


//


        ChatServer server = new ChatServer(8081, 10); // Try ports 8080-8089
        if (server.isRunning()) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            server.start();
        } else {
            System.err.println("Failed to start server");
        }
// Send the message
            out.println(message.toString());


        }
    }