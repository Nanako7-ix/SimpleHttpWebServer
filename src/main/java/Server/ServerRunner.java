package Server;

import java.io.IOException;

public class ServerRunner {
    static final int HTTP_PORT = 8080;
    static final int HTTPS_PORT = 8443;
    public static void main(String[] args) {
        System.out.println("=== Multi-threaded HTTP Web Server ===");
        System.out.println("Starting server...");

        System.out.println("Configuration:");
        System.out.println("- HTTP Port: " + HTTP_PORT);
        System.out.println("- HTTPS Port: " + HTTPS_PORT);
        System.out.println("- Thread Pool Size: 50");
        System.out.println("- Static Files Directory: static/");
        System.out.println("- Access Log: access.log");
        System.out.println();

        HttpWebServer server = new HttpWebServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start(HTTP_PORT, HTTPS_PORT);
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}
