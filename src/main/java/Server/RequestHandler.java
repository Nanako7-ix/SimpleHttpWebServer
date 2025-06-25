package Server;

import util.HttpRequest;
import util.HttpResponse;
import util.Session;
import util.User;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.text.SimpleDateFormat;
import java.net.URLDecoder;

public class RequestHandler implements Runnable {
    private final Socket clientSocket;
    private final HttpWebServer server;
    private BufferedReader in;
    private PrintWriter out;
    private OutputStream outputStream;

    public RequestHandler(Socket socket, HttpWebServer server) {
        this.clientSocket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            outputStream = clientSocket.getOutputStream();
            out = new PrintWriter(outputStream, true);

            HttpRequest request = parseRequest();
            if (request != null) {
                HttpResponse response = processRequest(request);
                sendResponse(response);

                server.getLogger().log(request, response, clientSocket.getInetAddress());
            }

        } catch (IOException e) {
            System.err.println("Error handling request: " + e.getMessage());
        } finally {
            server.getActiveConnections().decrementAndGet();
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    /**
     * 解析 HTTP 请求报文
     * Body 是 String 类型, 不能上传二进制数据 (例如图片等)
     * @return 一个HttpRequest 对象, 即 Http 请求报文
     */
    private HttpRequest parseRequest() throws IOException {
        String requestLine = in.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            return null;
        }

        String method = parts[0];
        String path = parts[1];
        String version = parts[2];

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(key, value);
            }
        }

        String body = "";
        if ("POST".equals(method) && headers.containsKey("content-length")) {
            int contentLength = Integer.parseInt(headers.get("content-length"));
            char[] bodyChars = new char[contentLength];
            in.read(bodyChars, 0, contentLength);
            body = new String(bodyChars);
        }

        return new HttpRequest(method, path, version, headers, body);
    }

    private HttpResponse processRequest(HttpRequest request) {
        try {
            String path = request.path();

            return switch (path) {
                case "/login" -> handleLogin(request);
                case "/logout" -> handleLogout(request);
                case "/search" -> handleSearch(request);
                case "/admin" -> handleAdmin(request);
                case "/admin/shutdown" -> handleShutdown(request);
                default -> handleStaticFile(request);
            };

        } catch (Exception e) {
            // TODO: 换一下这个默认界面
            return new HttpResponse(500, "Internal Server Error",
                    "text/html", "<h1>500 Internal Server Error</h1><p>" + e.getMessage() + "</p>");
        }
    }

    private HttpResponse handleLogin(HttpRequest request) {
        if ("GET".equals(request.method())) {
            return handleStaticFile(new HttpRequest("GET", "/login.html", request.version(),
                    request.headers(), ""));
        } else if ("POST".equals(request.method())) {
            // Process login
            Map<String, String> params = parseFormData(request.body());
            String username = params.get("username");
            String password = params.get("password");

            if (username != null && password != null) {
                User user = server.getUsers().get(username);
                if (user != null && user.password().equals(password)) {
                    // Create session
                    String sessionId = generateSessionId();
                    Session session = new Session(sessionId, username);
                    server.getSessions().put(sessionId, session);

                    String response = "<h1>Login Successful</h1><p>Welcome, " + user.name() + "!</p>" +
                            "<p><a href='/admin'>Admin Panel</a> | <a href='/logout'>Logout</a></p>";

                    HttpResponse httpResponse = new HttpResponse(200, "OK", "text/html", response);
                    httpResponse.addCookie("sessionId", sessionId);
                    return httpResponse;
                } else {
                    return new HttpResponse(401, "Unauthorized", "text/html",
                            "<h1>Login Failed</h1><p>Invalid credentials</p><a href='/login'>Try again</a>");
                }
            }
        }

        return new HttpResponse(400, "Bad Request", "text/html", "<h1>400 Bad Request</h1>");
    }

    /**
     * 处理 /logout 请求
     * 需要删除 sessionId 这个会话
     * 同时清空 cookie
     */
    private HttpResponse handleLogout(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        if (sessionId != null) {
            server.getSessions().remove(sessionId);
        }

        HttpResponse response = new HttpResponse(200, "OK", "text/html",
                "<h1>Logged Out</h1><p><a href='/login'>Login again</a></p>");
        response.eraseCookie("sessionId");
        return response;
    }

    private HttpResponse handleSearch(HttpRequest request) {
        if ("POST".equals(request.method())) {
            Map<String, String> params = parseFormData(request.body());
            String query = params.get("query");

            String response = "<h1>Search Results</h1><p>You searched for: <strong>" +
                    (query != null ? query : "nothing") + "</strong></p>" +
                    "<p>This is a demo search. In a real application, you would query a database.</p>" +
                    "<p><a href='/'>Back to home</a></p>";

            return new HttpResponse(200, "OK", "text/html", response);
        }

        return new HttpResponse(405, "Method Not Allowed", "text/html", "<h1>405 Method Not Allowed</h1>");
    }

    /**
     * 处理 /admin 请求
     * 当访问 /admin 时, 需要验证用户是否为 admin
     * 如果是, 则返回一个管理界面, 显示服务器状态和操作链接
     * 如果不是, 则返回 403 Forbidden
     */
    private HttpResponse handleAdmin(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;

        if (session == null || !session.getUsername().equals("admin")) {
            // TODO：换一下这个默认界面
            return new HttpResponse(403, "Forbidden", "text/html",
                    "<h1>403 Forbidden</h1><p>Admin access required</p><a href='/login'>Login</a>");
        }

        long uptime = System.currentTimeMillis() - server.getStartTime().get();
        // TODO：换一下这个默认界面
        // 从后端发送数据到前端可能还要学一下怎么写
        String response = "<h1>Server Administration</h1>" +
                "<h2>Server Statistics</h2>" +
                "<ul>" +
                "<li>Active Connections: " + server.getActiveConnections().get() + "</li>" +
                "<li>Total Requests: " + server.getTotalRequests().get() + "</li>" +
                "<li>Uptime: " + (uptime / 1000) + " seconds</li>" +
                "<li>Active Sessions: " + server.getSessions().size() + "</li>" +
                "</ul>" +
                "<h2>Actions</h2>" +
                "<p><a href='/admin/shutdown'>Shutdown Server</a></p>" +
                "<p><a href='/logout'>Logout</a></p>";

        return new HttpResponse(200, "OK", "text/html", response);
    }

    /**
     * 处理 /admin/shutdown 请求
     * 当访问 /admin/shutdown 时, 需要验证用户是否为 admin
     * 如果是, 则返回一个确认页面, 并在 2 秒后关闭服务器
     * 如果不是, 则返回 403 Forbidden
     */
    private HttpResponse handleShutdown(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;

        if (session == null || !session.getUsername().equals("admin")) {
            return new HttpResponse(403, "Forbidden", "text/html",
                    "<h1>403 Forbidden</h1><p>Admin access required</p>");
        }

        // 增加一个自动关闭服务器的线程, 5s 后调用 server.stop() 函数服务器
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                server.stop();
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        // TODO：换一下这个默认界面
        // 返回一个操作成功的页面
        return new HttpResponse(200, "OK", "text/html",
                "<h1>Server Shutting Down</h1><p>The server will shutdown in 2 seconds...</p>");
    }

    /**
     * 根据请求的路径处理静态文件
     * @param request 请求报文
     * @return 响应报文
     */
    private HttpResponse handleStaticFile(HttpRequest request) {
        String path = request.path();
        if (path.equals("/")) {
            path = "/index.html";
        }

        File file = new File("static" + path);
        if (!file.exists() || file.isDirectory()) {
            // TODO：换一下这个默认界面
            return new HttpResponse(404, "Not Found", "text/html",
                    "<h1>404 Not Found</h1><p>The requested resource was not found.</p>");
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            String mimeType = getMimeType(file.getName());

            return new HttpResponse(200, "OK", mimeType, content);
        } catch (IOException e) {
            // TODO: 换一下这个默认界面
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    "<h1>500 Internal Server Error</h1><p>Error reading file: " + e.getMessage() + "</p>");
        }
    }

    /**
     * 发送 HTTP 响应报文
     * 这里默认使用 HTTP/1.1 协议, 根据加分项, 这里需要支持 HTTPS
     * @param response 需要发送的 HTTP 响应对象
     */
    private void sendResponse(HttpResponse response) throws IOException {
        // 状态行
        out.println("HTTP/1.1 " + response.getStatusCode() + " " + response.getStatusText());

        // 响应头
        out.println("Content-Type: " + response.getContentType());
        out.println("Content-Length: " + response.getContentLength());
        out.println("Server: CustomHTTPServer/1.0");
        out.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).format(new Date()));
        // 支持 Cookie
        for (String cookie : response.getCookies()) {
            out.println("Set-Cookie: " + cookie);
        }

        out.println();
        out.flush();

        // 响应体 其中 getContent() 返回的是 byte[] 类型
        if (response.getContent() != null) {
            outputStream.write(response.getContent());
            outputStream.flush();
        }
    }

    /**
     * 解析表单数据
     * @param body 请求体
     * @return 解析好的参数键值对
     */
    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        if (body != null && !body.isEmpty()) {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    /**
     * 获取指定 cookieName=cookie 的 cookie 值
     * 感觉这个似乎没什么改的必要
     * @param request 解析的 HTTP 请求对象
     * @param cookieName 需要的 cookie 名称
     * @return cookie 的值
     */
    private String getCookieValue(HttpRequest request, String cookieName) {
        String cookieHeader = request.headers().get("cookie");
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(cookieName)) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    /**
     * 简单地为每个 session 生成一个唯一的 ID
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     *  简单的类型猜测 感觉可以完善一点？
     * @param fileName 需要猜测的文件名
     * @return 猜测的 MIME 类型
     */
    private String getMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
}
