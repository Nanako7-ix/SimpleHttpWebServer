package Server;

public class ServerRunner {
    public static void main(String[] args) {
        System.out.println("=== Multi-threaded HTTP Web Server ===");
        System.out.println("Starting server...");

        // Default configuration
        int port = 8080;
        boolean enableHttps = true;

        // Parse command line arguments
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default: " + port);
            }
        }

        if (args.length > 1) {
            enableHttps = Boolean.parseBoolean(args[1]);
        }

        System.out.println("Configuration:");
        System.out.println("- HTTP Port: " + port);
        System.out.println("- HTTPS Enabled: " + enableHttps);
        System.out.println("- Thread Pool Size: 50");
        System.out.println("- Static Files Directory: static/");
        System.out.println("- Access Log: access.log");
        System.out.println();

        // Start the server
        HttpWebServer.main(new String[]{String.valueOf(port), String.valueOf(enableHttps)});
    }
}
