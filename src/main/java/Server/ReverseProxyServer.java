package Server;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

public class ReverseProxyServer {
    private static final String TARGET_HOST = "localhost";
    private static final int THREAD_POOL_SIZE = 50;
    private static int proxyPort = 4040;
    private static final List<BackendServer> backendServers = new ArrayList<>();
    
    private ServerSocket proxySocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;
    private final ScheduledExecutorService monitorExecutor;

    private static class BackendServer {
        final int port;
        int activeConnections;
        long lastUpdated;

        BackendServer(int port) {
            this.port = port;
        }
    }
    public ReverseProxyServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        
        // 初始化后端服务器 (7070 和 8080)
        backendServers.add(new BackendServer(7070));
        backendServers.add(new BackendServer(8080));
    }
    
    private void updateServerStats() {
        for (BackendServer server : backendServers) {
            try (Socket socket = new Socket(TARGET_HOST, server.port)) {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                // 发送状态查询请求
                out.println("GET /admin/connections HTTP/1.1");
                out.println("Host: " + TARGET_HOST + ":" + server.port);
                out.println("Connection: close");
                out.println();
                
                // 解析响应
                String line;
                boolean inBody = false;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) inBody = true;
                    if (inBody) {
                        server.activeConnections = Integer.parseInt(line.trim());
                        server.lastUpdated = System.currentTimeMillis();
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error updating stats for " + server.port + ": " + e.getMessage());
            }
        }
    }
    
    public void start(int port) throws IOException {
        proxyPort = port;
        proxySocket = new ServerSocket(proxyPort);
        running = true;
        
        // 启动服务器状态监控
        monitorExecutor.scheduleAtFixedRate(this::updateServerStats, 0, 2, TimeUnit.SECONDS);
        
        System.out.println("Reverse Proxy started on port " + proxyPort);
        System.out.println("Backend servers: ");
        for (BackendServer server : backendServers) {
            System.out.println("  - " + TARGET_HOST + ":" + server.port);
        }
        handleConnections();
    }
    private BackendServer selectBestServer() {
        return Collections.min(backendServers, Comparator.comparingInt(s -> s.activeConnections));
    }
    
    private void handleClientRequest(Socket clientSocket) {
        BackendServer target = selectBestServer();
        System.out.println("Routing to server: " + target.port + " (Connections: " + target.activeConnections + ")");
        
        try (Socket targetSocket = new Socket(TARGET_HOST, target.port)) {
            // 数据传输逻辑保持不变...
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream targetIn = targetSocket.getInputStream();
            OutputStream targetOut = targetSocket.getOutputStream();
            
            ExecutorService pipePool = Executors.newFixedThreadPool(2);
            pipePool.submit(() -> pipeData(clientIn, targetOut, clientSocket, targetSocket));
            pipePool.submit(() -> pipeData(targetIn, clientOut, targetSocket, clientSocket));
            
            pipePool.shutdown();
            pipePool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            System.err.println("Proxy error: " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }
    private void handleConnections() throws IOException {
        while (running) {
            try {
                Socket clientSocket = proxySocket.accept();
                threadPool.submit(() -> handleClientRequest(clientSocket));
            } catch (SocketException e) {
                if (running) System.err.println("Socket error: " + e.getMessage());
            }
        }
    }
    
    private void pipeData(InputStream in, OutputStream out, Socket srcSocket, Socket destSocket) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        try {
            while ((bytesRead = in.read(buffer)) != -1) {
                if (bytesRead > 0) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            }
        } catch (SocketException e) {
            // 连接被关闭是正常情况，不打印错误
            if (!e.getMessage().contains("reset") && !e.getMessage().contains("closed")) {
                System.err.println("Socket error in pipe: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("I/O error in pipe: " + e.getMessage());
        } finally {
            // 关闭连接以终止另一端的传输
            closeQuietly(srcSocket);
            closeQuietly(destSocket);
        }
    }
    
    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // 忽略关闭错误
            }
        }
    }
    
    public void stop() {
        running = false;
        monitorExecutor.shutdown();
        // 关闭逻辑保持不变...
    }
    
    public static void main(String[] args) {
        ReverseProxyServer proxy = new ReverseProxyServer();
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop));
        
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 4040;
            proxy.start(port);
        } catch (IOException e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
        }
    }
}