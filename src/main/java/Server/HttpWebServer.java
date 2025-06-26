package Server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import util.Session;
import util.User;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;
import javax.net.ssl.*;
import java.security.KeyStore;

public class HttpWebServer {
    private static final String LOG_FILE = "access.log";
    static final String RECOURSES_DIR = "static/recourses";
    private static final String SESSIONS_FILE = "sessions.dat";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel httpServerChannel;
    private Channel httpsServerChannel;
    private volatile boolean running = false;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();

    private final RequestLogger logger;
    
    public HttpWebServer() {
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

    public void start(int http_port, int https_port) {
        loadSessions();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        running = true;
        
        // Start HTTP server
        ServerBootstrap httpBootstrap = new ServerBootstrap();
        try {
            httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                              .addLast(new HttpServerCodec())
                              .addLast(new HttpObjectAggregator(65536))
                              .addLast(new RequestHandler(HttpWebServer.this));
                        }
                    });
            httpServerChannel = httpBootstrap.bind(http_port).sync().channel();
            System.out.println("HTTP Server started on port " + http_port);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        ServerBootstrap httpsBootstrap = new ServerBootstrap();
        try {
            SslContext sslCtx = setupSSL();
            httpsBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                              .addLast(sslCtx.newHandler(ch.alloc()))
                              .addLast(new HttpServerCodec())
                              .addLast(new HttpObjectAggregator(65536))
                              .addLast(new RequestHandler(HttpWebServer.this));
                        }
                    });
            httpsServerChannel = httpsBootstrap.bind(https_port).sync().channel();
            System.out.println("HTTPS Server started on port " + https_port);
        } catch (Exception e) {
            System.out.println("Failed to start HTTPS server: " + e.getMessage());
        }
    }
    
    private SslContext setupSSL() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream keyStoreStream = Files.newInputStream(Paths.get("keystore.p12"))) {
            keyStore.load(keyStoreStream, "123456".toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "123456".toCharArray());

        return SslContextBuilder.forServer(kmf).build();
    }

    public void stop() {
        if (!running) return;

        running = false;
        saveSessions();
        try {
            if (httpServerChannel != null) httpServerChannel.close().sync();
            if (httpsServerChannel != null) httpsServerChannel.close().sync();
            if (workerGroup != null) workerGroup.shutdownGracefully().sync();
            if (bossGroup != null) bossGroup.shutdownGracefully().sync();
            logger.close();
            System.out.println("Server stopped");
        } catch (InterruptedException e) {
            System.out.println("Error stopping server: " + e.getMessage());
        }
    }

    public Map<String, Session> getSessions() { return sessions; }
    public Map<String, User> getUsers() { return users; }
    public RequestLogger getLogger() { return logger; }
    public AtomicInteger getActiveConnections() { return activeConnections; }
    public AtomicLong getTotalRequests() { return totalRequests; }
    public AtomicLong getStartTime() { return startTime; }
}
