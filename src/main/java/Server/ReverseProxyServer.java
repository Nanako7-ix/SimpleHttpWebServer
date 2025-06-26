package Server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import util.HttpRequest;
import util.HttpResponse;

public class ReverseProxyServer {
    private static final int THREAD_POOL_SIZE = 50;
    private final int proxyPort;
    private final List<BackendServer> backendServers;
    
    private ServerSocket proxySocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;
    private final ScheduledExecutorService statusChecker;
    
    public ReverseProxyServer(int proxyPort, List<BackendServer> backendServers) {
        this.proxyPort = proxyPort;
        this.backendServers = backendServers;
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.statusChecker = Executors.newSingleThreadScheduledExecutor();
    }
    
    public void start() throws IOException {
        proxySocket = new ServerSocket(proxyPort);
        running = true;
        
        // 启动服务器状态检查
        statusChecker.scheduleAtFixedRate(this::updateServerStatus, 
            0, 1, TimeUnit.SECONDS);
        
        System.out.println("Reverse Proxy started on port " + proxyPort);
        System.out.println("Backend servers:");
        for (BackendServer server : backendServers) {
            System.out.println(" - " + server.host() + ":" + server.port() + 
                              " (current connections: " + server.activeConnections() + ")");
        }
        
        handleConnections();
    }
    
    private void updateServerStatus() {
        for (BackendServer server : backendServers) {
            try (Socket statusSocket = new Socket(server.host(), server.port())) {
                statusSocket.setSoTimeout(1000); // 设置超时时间
                
                // 发送状态查询请求
                PrintWriter out = new PrintWriter(statusSocket.getOutputStream(), true);
                out.println("GET /admin/connections HTTP/1.1");
                out.println("Host: " + server.host());
                out.println("Connection: close");
                out.println();
                out.flush();
                
                // 读取响应
                BufferedReader in = new BufferedReader(new InputStreamReader(statusSocket.getInputStream()));
                String line;
                int contentLength = -1;
                StringBuilder responseBuilder = new StringBuilder();
                
                // 读取响应头
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    responseBuilder.append(line).append("\n");
                    
                    // 检查Content-Length头
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                
                // 读取响应体（如果有）
                String content = "";
                if (contentLength > 0) {
                    char[] contentChars = new char[contentLength];
                    in.read(contentChars, 0, contentLength);
                    content = new String(contentChars);
                }
                
                // 检查响应状态码
                if (responseBuilder.toString().startsWith("HTTP/1.1 200 OK")) {
                    int connections = Integer.parseInt(content.trim());
                    server.setActiveConnections(connections);
                    System.out.println("[" + new Date() + "] Server " + server.host() + 
                                    ":" + server.port() + " connections: " + connections);
                } else {
                    server.setActiveConnections(Integer.MAX_VALUE);
                    System.err.println("[" + new Date() + "] Server " + server.host() + 
                                    ":" + server.port() + " returned non-200 status");
                }
            } catch (IOException | NumberFormatException | StringIndexOutOfBoundsException e) {
                server.setActiveConnections(Integer.MAX_VALUE);
                System.err.println("[" + new Date() + "] Error checking status of " + 
                                server.host() + ":" + server.port() + ": " + e.getMessage());
            }
        }
    }
    
    private BackendServer selectBestServer() {
        return backendServers.stream()
            .min(Comparator.comparingInt(BackendServer::activeConnections))
            .orElse(backendServers.get(0));
    }
    
    private void handleConnections() throws IOException {
        while (running) {
            try {
                Socket clientSocket = proxySocket.accept();
                threadPool.submit(() -> handleClientRequest(clientSocket));
            } catch (SocketException e) {
                if (running) System.err.println("Socket error: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Error accepting connection: " + e.getMessage());
            }
        }
    }
    
    private void handleClientRequest(Socket clientSocket) {
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
        try {
            if (proxySocket != null) proxySocket.close();
            threadPool.shutdown();
            statusChecker.shutdown();
            System.out.println("Reverse Proxy stopped");
        } catch (IOException e) {
            System.err.println("Error stopping proxy: " + e.getMessage());
        }
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
        // 创建后端服务器列表
        List<BackendServer> backendServers = new ArrayList<>();
        backendServers.add(new BackendServer("localhost", 7070));
        backendServers.add(new BackendServer("localhost", 8080));
        
        ReverseProxyServer proxy = new ReverseProxyServer(4040, backendServers);
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop));
        
        try {
            proxy.start();
        } catch (IOException e) {
            System.err.println("Failed to start reverse proxy: " + e.getMessage());
        }
    }
}