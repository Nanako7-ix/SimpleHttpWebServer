package Server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import util.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.text.SimpleDateFormat;

public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final HttpWebServer server;
    private BufferedReader in;
    private PrintWriter out;
    private OutputStream outputStream;

    public RequestHandler(HttpWebServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response;
        try {
            String uri = request.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            System.out.println(uri);
            response = switch (path) {
                case "/login" -> handleLogin(request);
                case "/logout" -> handleLogout(request);
                case "/search" -> handleSearch(request);
                case "/repo" -> handleDownload(request);
                case "/admin" -> handleAdmin(request);
                case "/admin/shutdown" -> handleShutdown(request);
                case "/admin/connections" -> handleConnectionsCount(request);
                default -> handleStaticFile(request);
            }
        } catch (Exception e) {
            response = new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Internal Server Error: " + e.getMessage()));
        }
        
        ctx.writeAndFlush(response);
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
     * @return HttpRequest对象，即 Http 请求报文
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
    private HttpResponse handleConnectionsCount(HttpRequest request) {
        // 只允许本地访问
        if (!clientSocket.getInetAddress().isLoopbackAddress()) {
            return new HttpResponse(403, "Forbidden", "text/plain", "Access denied".getBytes());
        }
        int count = server.getActiveConnections().get();
        return new HttpResponse(200, "OK", "text/plain", String.valueOf(count).getBytes());
    }
    /**
     * 处理 HTTP 请求, 根据请求的路径调用不同的处理方法
     * @param request 请求报文
     * @return 响应报文
     */
    private HttpResponse processRequest(HttpRequest request) {
        try {
            String path = request.path();
            System.out.println(path);
            return switch (path.contains("?") ? path.substring(0, path.indexOf('?')) : path) {
                case "/login" -> handleLogin(request);
                case "/logout" -> handleLogout(request);
                case "/search" -> handleSearch(request);
                case "/repo" -> handleDownload(request);
                case "/admin" -> handleAdmin(request);
                case "/admin/shutdown" -> handleShutdown(request);
                case "/admin/connections" -> handleConnectionsCount(request);
                default -> handleStaticFile(request);
            };

        } catch (Exception e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Internal Server Error: " + e.getMessage()));
        }
    }

    /**
     * 处理登录请求
     */
    private HttpResponse handleLogin(HttpRequest request) {
        if ("GET".equals(request.method())) {
            return handleStaticFile(new HttpRequest("GET", "/login.html", request.version(),
                    request.headers(), ""));
        } else if ("POST".equals(request.method())) {
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

                    try {
                        // 读取文件内容
                        File file = new File("static/login_success.html");
                        String content = new String(Files.readAllBytes(file.toPath()));
                        // 替换占位符
                        content = content.replace("{{ username }}", user.name());

                        HttpResponse httpResponse = new HttpResponse(200, "OK", "text/html", content.getBytes());
                        httpResponse.addCookie("sessionId", sessionId);
                        return httpResponse;
                    } catch (IOException e) {
                        return new HttpResponse(500, "Internal Server Error", "text/html",
                                errorHTMLPage(500, "Internal Server Error", "Error reading login success page: " + e.getMessage()));
                    }
                } else {
                    return new HttpResponse(401, "Unauthorized", "text/html",
                            errorHTMLPage(401, "Unauthorized", "Invalid credentials, please try again."));
                }
            }
        }

        return new HttpResponse(400, "Bad Request", "text/html",
                errorHTMLPage(400, "Bad Request", "Bad Request"));
    }

    /**
     * 处理退出登录请求
     */
    private HttpResponse handleLogout(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        assert (sessionId != null); // 返回一个当前尚未登陆的页面

        User user = server.getUsers().get(server.getSessions().get(sessionId).getUsername());
        server.getSessions().remove(sessionId);

        try {
            // 读取文件内容
            File file = new File("static/logout_success.html");
            String content = new String(Files.readAllBytes(file.toPath()));
            // 替换占位符
            content = content.replace("{{ username }}", user.name());

            HttpResponse httpResponse = new HttpResponse(200, "OK", "text/html", content.getBytes());
            httpResponse.eraseCookie("sessionId");
            return httpResponse;
        } catch (IOException e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Error reading login success page" + e.getMessage()));
        }
    }

    /**
     * 处理仓库的文件搜索请求
     */
    private HttpResponse handleSearch(HttpRequest request) {
        String query = extractQueryParam(request.path());

        File htmlFile = new File("static/store.html");
        if (!htmlFile.exists()) {
            return new HttpResponse(404, "Not Found", "text/html",
                    errorHTMLPage(404, "Not Found", "Page Not Found"));
        }

        try {
            // 读取模板内容
            String content = Files.readString(htmlFile.toPath(), StandardCharsets.UTF_8);

            // 动态插入文件列表
            File[] files = new File(HttpWebServer.RECOURSES_DIR).listFiles();
            StringBuilder fileListHtml = new StringBuilder();
            if (files != null) {
                int index = 0;
                for (File file : files) {
                    if (query == null || file.getName().contains(query)) {
                        fileListHtml.append("<div class='file-item' style='animation-delay: ")
                                .append(0.4 * index / files.length)
                                .append("s;'>")
                                .append("<div>").append(file.getName()).append("</div>")
                                .append("<a href='/repo?").append(file.getName()).append("'>Download</a>")
                                .append("</div>\n");
                        index++;
                    }
                }
            }

            content = content.replace("{{fileList}}", fileListHtml.toString());

            content = content.replace("{{fileList}}", fileListHtml.toString());
            return new HttpResponse(200, "OK", "text/html", content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Internal Server Error" + e.getMessage()));
        }
    }

    // 从路径提取查询参数 ?q=search
    private String extractQueryParam(String path) {
        if (path.contains("?")) {
            String query = path.split("\\?")[1];
            for (String param : query.split("&"))
                if (param.startsWith("q="))
                    return URLDecoder.decode(param.substring(2), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 处理文件下载请求
     */
    private HttpResponse handleDownload(HttpRequest request) {
        String path = request.path();
        String filename = path.substring("/repo/".length()); // 去除前缀

        File file = new File(HttpWebServer.RECOURSES_DIR, filename); // 资源目录 + 文件名
        if (!file.exists() || file.isDirectory()) {
            return new HttpResponse(404, "Not Found", "text/html",
                    errorHTMLPage(404, "Not Found", "Page Not Found"));
        }

        try {
            byte[] fileContent = Files.readAllBytes(file.toPath());

            HttpResponse response = new HttpResponse(200, "OK",
                    "application/octet-stream", fileContent);
            response.addHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            return response;
        } catch (IOException e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Internal Server Error" + e.getMessage()));
        }
    }

    /**
     * 处理访问管理页面请求
     */
    private HttpResponse handleAdmin(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;

        if (session == null || !session.getUsername().equals("admin")) {
            return new HttpResponse(403, "Forbidden", "text/html",
                    errorHTMLPage(403, "Forbidden", "Admin access required"));
        }

        try {
            // 读取 admin.html 文件
            File adminFile = new File("static/admin.html");
            if (adminFile.exists() && adminFile.isFile()) {
                String content = new String(Files.readAllBytes(adminFile.toPath()));

                // 获取服务器数据
                long activeConnections = server.getActiveConnections().get();
                long totalRequests = server.getTotalRequests().get();
                long startTime = server.getStartTime().get();
                long uptime = (System.currentTimeMillis() - startTime) / 1000;

                // 替换占位符
                content = content.replace("{{ activeConnections }}", String.valueOf(activeConnections));
                content = content.replace("{{ totalRequests }}", String.valueOf(totalRequests));
                content = content.replace("{{ startTime }}", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime)));
                content = content.replace("{{ uptime }}", String.valueOf(uptime));

                return new HttpResponse(200, "OK", "text/html", content.getBytes());
            } else {
                return new HttpResponse(404, "Not Found", "text/html",
                        errorHTMLPage(404, "Not Found", "Page Not Found"));
            }
        } catch (IOException e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Error reading admin page: " + e.getMessage()));
        }
    }

    /**
     * 处理关闭服务器请求
     */
    private HttpResponse handleShutdown(HttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;

        if (session == null || !session.getUsername().equals("admin")) {
            return new HttpResponse(403, "Forbidden", "text/html",
                    errorHTMLPage(403, "Forbidden", "你没有权限."));
        }

        new Thread(() -> {
            try {
                Thread.sleep(5000);
                server.stop();
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        return new HttpResponse(200, "OK", "text/html",
                "<!DOCTYPE html>" +
                "<html><head><title>Shutdown</title>" +
                "<link rel='stylesheet' href='/sucess_style.css'>" +
                "</head><body class='error-page'>" +
                "<div class='error-container'>" +
                "<h1>"+ "Shutting Down" + "</h1>" +
                "<div class='error-details'>" + "The server will shutdown in 5 seconds..." + "</div>" +

                "</div></body></html>");
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
            return new HttpResponse(404, "Not Found", "text/html",
                    errorHTMLPage(404, "Not Found", "Page Not Found"));
        }

        try {
            byte[] byteContent = java.nio.file.Files.readAllBytes(file.toPath());
            String mimeType = getMimeType(file.getName());

            // dong tai chu li login/logout he admin de xian shi
            String content = new String(byteContent, StandardCharsets.UTF_8);
            String sessionId = getCookieValue(request, "sessionId");
            String replaceHref = (sessionId != null) ? "/logout" : "/login";
            String replaceText = (sessionId != null) ? "Logout" : "Login";
            content = content.replaceAll(
                "<a href=\"/auth_link\">auth_link</a>",
                "<a href=\"" + replaceHref + "\">" + replaceText + "</a>"
            );
            Session session = sessionId != null ? server.getSessions().get(sessionId) : null;
            boolean isAdmin = session != null && "admin".equals(session.getUsername());
            if (!isAdmin) content = content.replace("<a href='/admin'>Admin</a>", "");

            return new HttpResponse(200, "OK", mimeType, content.getBytes());
        } catch (IOException e) {
            return new HttpResponse(500, "Internal Server Error", "text/html",
                    errorHTMLPage(500, "Internal Server Error", "Error reading file: " + e.getMessage()));
        }
    }
    
    /**
     * 发送 HTTP 响应报文
     * 这里默认使用 HTTP/1.1 协议, 根据加分项, 这里需要支持 HTTPS
     * @param response 需要发送的 HTTP 响应对象
     */
    private void sendResponse(HttpResponse response) throws IOException {
        out.println("HTTP/1.1 " + response.getStatusCode() + " " + response.getStatusText());

        out.println("Content-Type: " + response.getContentType());
        out.println("Content-Length: " + response.getContentLength());
        out.println("Server: CustomHTTPServer/1.0");
        out.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).format(new Date()));
        for (Map.Entry<String, String> header : response.getHeaders().entrySet()) {
            out.println(header.getKey() + ": " + header.getValue());
        }
        for (String cookie : response.getCookies()) {
            out.println("Set-Cookie: " + cookie);
        }

        out.println();
        out.flush();

        if (response.getContent() != null) {
            outputStream.write(response.getContent());
            outputStream.flush();
        }
    }

    /**
     * 解析表单数据
     * @param body 请求体, 来自 POST 请求 (登录和搜索)
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
     * 使用枚举 MimeType 来获取文件的 MIME 类型
     */
    private String getMimeType(String fileName) {
        MimeType mimeType = MimeType.fromFilename(fileName);
        return mimeType.getMimeType();
    }

    /**
     * 生成报错网页报文
     */
    private String errorHTMLPage (int statusCode, String statusText, String Message) {
        return "<!DOCTYPE html>" +
        "<html><head><title>Error</title>" +
        "<link rel='stylesheet' href='/error_style.css'>" +
        "</head><body class='error-page'>" +
        "<div class='error-container'>" +
        "<h1>"+ statusCode + ' ' + statusText + "</h1>" +
        "<div class='error-details'>" + Message + "</div>" +
        "<a href='/' class='back-link'>Back</a>" +
        "</div></body></html>";
    }
}
