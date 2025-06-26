package Server;

import java.io.IOException;

public class ServerRunner {
    public static void main(String[] args) {
        System.out.println("=== Multi-threaded HTTP Web Server ===");
        System.out.println("Starting server...");

        int port = 8080;
        boolean enableHttps = true;

        System.out.println("Configuration:");
        System.out.println("- HTTP Port: " + port);
        System.out.println("- HTTPS Enabled: " + enableHttps);
        System.out.println("- Thread Pool Size: 50");
        System.out.println("- Static Files Directory: static/");
        System.out.println("- Access Log: access.log");
        System.out.println();

        HttpWebServer server = new HttpWebServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start(port, enableHttps);
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}
