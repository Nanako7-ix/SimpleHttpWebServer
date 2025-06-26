package Server;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ReverseProxyServer {
    private static final String TARGET_HOST = "localhost";
    private static final int THREAD_POOL_SIZE = 50;
    private static int Proxy_port = 8080;
    private static int Target_port = 8080;
    
    private ServerSocket proxySocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;
    
    public ReverseProxyServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }
    
    public void start(int proxy_port, int target_port) throws IOException {
        Target_port = target_port;
        Proxy_port = proxy_port;
        proxySocket = new ServerSocket(Proxy_port);
        running = true;
        System.out.println("Reverse Proxy started on port " + Proxy_port);
        System.out.println("Forwarding all requests to " + TARGET_HOST + ":" + Target_port);
        handleConnections();
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
            // 1. 连接到目标服务器
        try (Socket targetSocket = new Socket(TARGET_HOST, Target_port)) {
            
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
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop));
        
        try {
            proxy.start(4040, 8080);
        } catch (IOException e) {
            System.err.println("Failed to start reverse proxy: " + e.getMessage());
        }
    }
}