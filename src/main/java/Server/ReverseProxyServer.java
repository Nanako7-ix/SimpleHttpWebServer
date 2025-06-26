package Server;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;
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
            System.out.println(" - " + server.host() + ":" + server.port());
        }
        
        handleConnections();
    }
    
    private void updateServerStatus() {
        for (BackendServer server : backendServers) {
            try (Socket statusSocket = new Socket(server.host(), server.port())) {
                PrintWriter out = new PrintWriter(statusSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(statusSocket.getInputStream()));
                
                // 发送状态查询请求
                out.println("GET /status HTTP/1.1");
                out.println("Host: " + server.host());
                out.println("Connection: close");
                out.println();
                
                // 读取响应
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("Content-Length: ")) {
                        int contentLength = Integer.parseInt(line.substring(16).trim());
                        char[] content = new char[contentLength];
                        in.read(content, 0, contentLength);
                        int connections = Integer.parseInt(new String(content));
                        server.setActiveConnections(connections);
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                // 标记服务器为不可用
                server.setActiveConnections(Integer.MAX_VALUE);
                System.err.println("Error checking status of " + server.host() + ":" + server.port() + ": " + e.getMessage());
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
        BackendServer bestServer = selectBestServer();
        
        try (Socket targetSocket = new Socket(bestServer.host(), bestServer.port())) {
            // 获取输入输出流
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream targetIn = targetSocket.getInputStream();
            OutputStream targetOut = targetSocket.getOutputStream();
            
            // 启动两个线程进行双向数据转发
            ExecutorService pipePool = Executors.newFixedThreadPool(2);
            
            // 客户端 -> 代理 -> 目标服务器
            pipePool.submit(() -> pipeData(clientIn, targetOut, clientSocket, targetSocket));
            
            // 目标服务器 -> 代理 -> 客户端
            pipePool.submit(() -> pipeData(targetIn, clientOut, targetSocket, clientSocket));
            
            // 等待转发任务完成
            pipePool.shutdown();
            try {
                pipePool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            System.err.println("Error connecting to target server " + bestServer.host() + 
                              ":" + bestServer.port() + ": " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
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
            if (!e.getMessage().contains("reset") && !e.getMessage().contains("closed")) {
                System.err.println("Socket error in pipe: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("I/O error in pipe: " + e.getMessage());
        } finally {
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