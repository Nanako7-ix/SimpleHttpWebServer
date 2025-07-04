package Server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
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
        // 更新后端服务器状态
    private void updateServerStats() {
        for (BackendServer server : backendServers) {
            try (Socket socket = new Socket(TARGET_HOST, server.port)) {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                // 发送状态查询请求
                out.println("GET /admin/connections HTTP/1.1");
                out.println();
                
                // 解析响应
                String line;
                while ((line = in.readLine()) != null) {
                    Pattern pattern = Pattern.compile("^content-length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        server.activeConnections = Integer.parseInt(matcher.group(1));
                        System.out.println(server.activeConnections);
                        break;
                   }
                }
            } catch (Exception e) {
                System.err.println("Error updating stats for " + server.port + ": " + e.getMessage());
            }
        }
    }
    
    // 选择最优后端服务器
    private BackendServer selectBestServer() {
        return Collections.min(backendServers, Comparator.comparingInt(s -> s.activeConnections));
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
    
    private void handleClientRequest(Socket clientSocket) {
        BackendServer target = selectBestServer();
        System.out.println("Routing to server: " + target.port + " (Connections: " + target.activeConnections + ")");
            // 1. 连接到目标服务器
        try (Socket targetSocket = new Socket(TARGET_HOST, target.port)) {
            
            // 2. 获取输入输出流
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream targetIn = targetSocket.getInputStream();
            OutputStream targetOut = targetSocket.getOutputStream();
            
            // 3. 启动两个线程进行双向数据转发
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
            System.err.println("Error connecting to target server: " + e.getMessage());
        } finally {
            // 4. 关闭连接
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
        try {
            if (proxySocket != null) proxySocket.close();
            threadPool.shutdown();
            System.out.println("Reverse Proxy stopped");
        } catch (IOException e) {
            System.err.println("Error stopping proxy: " + e.getMessage());
        }
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