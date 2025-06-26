package Server;

import java.io.IOException;

public class ReverseProxyServerRunner {
    public static void main(String[] args) {
        // 启动第一个后端服务器 (7070端口)
        new Thread(() -> {
            HttpWebServer server1 = new HttpWebServer();
            try {
                server1.start(7070, false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // 启动第二个后端服务器 (8080端口)
        new Thread(() -> {
            HttpWebServer server2 = new HttpWebServer();
            try {
                server2.start(8080, false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // 启动反向代理 (4040端口)
        ReverseProxyServer.main(args);
    }
}