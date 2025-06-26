package Server;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import util.HttpRequest;
import util.HttpResponse;

public class ReverseProxyServer {
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
                        System.currentTimeMillis();
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
            // 读取客户端请求
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            StringBuilder requestBuilder = new StringBuilder();
            String line;
            while (!(line = in.readLine()).isEmpty()) {
                requestBuilder.append(line).append("\r\n");
            }
            
            // 解析请求
            String requestStr = requestBuilder.toString();
            String[] parts = requestStr.split("\r\n", 2);
            String[] requestLine = parts[0].split(" ");
            String method = requestLine[0];
            String path = requestLine[1];
            String version = requestLine[2];
            
            Map<String, String> headers = new HashMap<>();
            if (parts.length > 1) {
                for (String header : parts[1].split("\r\n")) {
                    int colonIndex = header.indexOf(':');
                    if (colonIndex > 0) {
                        String key = header.substring(0, colonIndex).trim().toLowerCase();
                        String value = header.substring(colonIndex + 1).trim();
                        headers.put(key, value);
                    }
                }
            }
            
            // 选择最佳服务器
            BackendServer bestServer = selectBestServer();
            // System.out.println("[" + new Date() + "] Selected server: " + 
            //                   bestServer.host() + ":" + bestServer.port() + 
            //                   " (connections: " + bestServer.activeConnections() + ")");
            
            // 转发请求到后端服务器
            try (Socket targetSocket = new Socket(bestServer.host(), bestServer.port())) {
                PrintWriter targetOut = new PrintWriter(targetSocket.getOutputStream(), true);
                targetOut.println(requestStr);
                targetOut.flush();
                
                // 获取后端响应
                BufferedReader targetIn = new BufferedReader(new InputStreamReader(targetSocket.getInputStream()));
                StringBuilder responseBuilder = new StringBuilder();
                while ((line = targetIn.readLine()) != null) {
                    responseBuilder.append(line).append("\r\n");
                }
                
                // 返回响应给客户端
                PrintWriter clientOut = new PrintWriter(clientSocket.getOutputStream(), true);
                clientOut.println(responseBuilder.toString());
                clientOut.flush();
            } catch (IOException e) {
                // 返回错误响应
                PrintWriter clientOut = new PrintWriter(clientSocket.getOutputStream(), true);
                clientOut.println("HTTP/1.1 502 Bad Gateway\r\n");
                clientOut.println("Content-Type: text/plain\r\n");
                clientOut.println("\r\n");
                clientOut.println("502 Bad Gateway: Unable to connect to backend server");
                clientOut.flush();
                
                System.err.println("Error connecting to target server " + bestServer.host() + 
                                  ":" + bestServer.port() + ": " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error handling client request: " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
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
    
    public static class BackendServer {
        private final String host;
        private final int port;
        private volatile int activeConnections;
        
        public BackendServer(String host, int port) {
            this.host = host;
            this.port = port;
            this.activeConnections = Integer.MAX_VALUE;
        }
        
        public String host() { return host; }
        public int port() { return port; }
        public int activeConnections() { return activeConnections; }
        public void setActiveConnections(int connections) { this.activeConnections = connections; }
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