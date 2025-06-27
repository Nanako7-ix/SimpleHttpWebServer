package Server;

public class ServerRunner {
    static final int HTTP_PORT = 8080;
    static final int HTTP_PORT2 = 7070;
    static final int HTTPS_PORT = 8443;
    public static void main(String[] args) {
        if (args.length > 0) {
            System.out.println("=== Multi-threaded HTTP Web Server ===");
            System.out.println("Starting server1...");

            System.out.println("Configuration:");
            System.out.println("- HTTP Port: " + HTTP_PORT);
            System.out.println("- HTTPS Port: " + HTTPS_PORT);
            System.out.println("- Thread Pool Size: 50");
            System.out.println("- Static Files Directory: static/");
            System.out.println("- Access Log: access.log");
            System.out.println();

            HttpWebServer server = new HttpWebServer();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            server.start(HTTP_PORT, HTTPS_PORT);

            System.out.println("=== Multi-threaded HTTP Web Server ===");
            System.out.println("Starting server2...");

            System.out.println("Configuration:");
            System.out.println("- HTTP Port: " + HTTP_PORT);
            System.out.println("- HTTPS Port: " + HTTPS_PORT);
            System.out.println("- Thread Pool Size: 50");
            System.out.println("- Static Files Directory: static/");
            System.out.println("- Access Log: access.log");
            System.out.println();

            HttpWebServer server2 = new HttpWebServer();
            Runtime.getRuntime().addShutdownHook(new Thread(server2::stop));

            server2.start(HTTP_PORT, HTTPS_PORT);
        }
        else {
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

            server.start(HTTP_PORT, HTTPS_PORT);
        }
    }
}
