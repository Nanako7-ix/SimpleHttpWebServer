package Server;

import util.Session;
import util.User;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.security.SecureRandom;
import java.util.*;
import javax.net.ssl.*;
import java.security.KeyStore;

public class HttpWebServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int THREAD_POOL_SIZE = 50;
    private static final String STATIC_DIR = "static";
    private static final String LOG_FILE = "access.log";
    static final String RECOURSES_DIR = "static/recourses";
    
    private ServerSocket serverSocket;
    private SSLServerSocket sslServerSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;
    
    // Server statistics
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    // Session management
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    
    // Request logger
    private final RequestLogger logger;
    
    public HttpWebServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.logger = new RequestLogger(LOG_FILE);
        
        // Initialize some demo users
        users.put("admin", new User("admin", "password", "Administrator"));
        users.put("user", new User("user", "123456", "Regular User"));
        
        // Create static directory if it doesn't exist
        new File(STATIC_DIR).mkdirs();
//        createSampleStaticFiles();
    }
    
    public void start(int port, boolean enableHttps) throws IOException {
        // Start HTTP server
        serverSocket = new ServerSocket(port);
        running = true;
        
        System.out.println("HTTP Server started on port " + port);
        
        // Start HTTPS server if enabled
        if (enableHttps) {
            try {
                setupSSL();
                new Thread(() -> {
                    try {
                        handleHttpsConnections();
                    } catch (IOException e) {
                        System.err.println("HTTPS server error: " + e.getMessage());
                    }
                }).start();
                System.out.println("HTTPS Server started on port " + HTTPS_PORT);
            } catch (Exception e) {
                System.err.println("Failed to start HTTPS server: " + e.getMessage());
            }
        }
        
        // Handle HTTP connections
        handleConnections();
    }
    
    private void setupSSL() throws Exception {
        // 加载现有的keystore文件
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream keyStoreStream = Files.newInputStream(Paths.get("keystore.p12"))) {
            keyStore.load(keyStoreStream, "123456".toCharArray());
        }
    
        // 配置SSL上下文
        SSLContext sslContext = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "123456".toCharArray());
        
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        sslServerSocket = (SSLServerSocket) sslContext.getServerSocketFactory()
                            .createServerSocket(HTTPS_PORT);
    }
    
    private void handleConnections() throws IOException {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                activeConnections.incrementAndGet();
                totalRequests.incrementAndGet();
                
                threadPool.submit(new RequestHandler(clientSocket, this));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }
    
    private void handleHttpsConnections() throws IOException {
        while (running && sslServerSocket != null) {
            try {
                Socket clientSocket = sslServerSocket.accept();
                activeConnections.incrementAndGet();
                totalRequests.incrementAndGet();
                
                threadPool.submit(new RequestHandler(clientSocket, this));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting HTTPS connection: " + e.getMessage());
                }
            }
        }
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (sslServerSocket != null) sslServerSocket.close();
            threadPool.shutdown();
            logger.close();
            System.out.println("Server stopped");
        } catch (IOException e) {
            System.err.println("Error stopping server: " + e.getMessage());
        }
    }

    // Getters for request handler
    public Map<String, Session> getSessions() { return sessions; }
    public Map<String, User> getUsers() { return users; }
    public RequestLogger getLogger() { return logger; }
    public AtomicInteger getActiveConnections() { return activeConnections; }
    public AtomicLong getTotalRequests() { return totalRequests; }
    public AtomicLong getStartTime() { return startTime; }
    
    public static void main(String[] args) {
        HttpWebServer server = new HttpWebServer();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
            boolean enableHttps = args.length > 1 && Boolean.parseBoolean(args[1]);

            server.start(port, enableHttps);
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}
