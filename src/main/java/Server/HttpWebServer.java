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
    private static final String LOG_FILE = "access.log";
    static final String RECOURSES_DIR = "static/recourses";
    private static final String SESSIONS_FILE = "sessions.dat";
    
    private ServerSocket serverSocket;
    private SSLServerSocket sslServerSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();

    private final RequestLogger logger;
    
    public HttpWebServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.logger = new RequestLogger(LOG_FILE);
        
        users.put("admin", new User("admin", "password", "Administrator"));
        users.put("user", new User("user", "123456", "Regular User"));
    }

    private void loadSessions() {
        File file = new File(SESSIONS_FILE);
        if (!file.exists()) {
            System.out.println("No session file found, start with empty sessions.");
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Session> loadedSessions = (Map<String, Session>) obj;
                sessions.putAll(loadedSessions);
                System.out.println("Loaded " + loadedSessions.size() + " sessions from file.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load sessions: " + e.getMessage());
        }
    }

    private void saveSessions() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(SESSIONS_FILE))) {
            oos.writeObject(sessions);
            System.out.println("Saved " + sessions.size() + " sessions to file.");
        } catch (IOException e) {
            System.err.println("Failed to save sessions: " + e.getMessage());
        }
    }

    public void start(int port, boolean enableHttps) throws IOException {
        loadSessions();

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("::0",port));
        running = true;
        
        System.out.println("HTTP Server started on port " + port);

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

        handleConnections();
    }
    
    private void setupSSL() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream keyStoreStream = Files.newInputStream(Paths.get("keystore.p12"))) {
            keyStore.load(keyStoreStream, "123456".toCharArray());
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "123456".toCharArray());
        
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        sslServerSocket = (SSLServerSocket) sslContext.getServerSocketFactory()
                            .createServerSocket(HTTPS_PORT);
    }
    
    private void handleConnections() {
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
        saveSessions();
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

    public Map<String, Session> getSessions() { return sessions; }
    public Map<String, User> getUsers() { return users; }
    public RequestLogger getLogger() { return logger; }
    public AtomicInteger getActiveConnections() { return activeConnections; }
    public AtomicLong getTotalRequests() { return totalRequests; }
    public AtomicLong getStartTime() { return startTime; }
}
