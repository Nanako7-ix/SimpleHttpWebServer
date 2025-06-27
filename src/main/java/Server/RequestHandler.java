package Server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import io.netty.util.CharsetUtil;
import util.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final HttpWebServer server;
    private InetSocketAddress clientAddress;

    public RequestHandler(HttpWebServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = processRequest(request);
        ctx.writeAndFlush(response);

        clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        server.getLogger().log(request, response, clientAddress.getAddress());
        server.getTotalRequests().incrementAndGet();
    }

    /**
     * 处理 HTTP 请求, 根据请求的路径调用不同的处理方法
     * @param request 请求报文
     * @return 响应报文
     */
    private FullHttpResponse processRequest(FullHttpRequest request) {
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
            };
        } catch (Exception e) {
            String content = errorHTMLPage(
                    500,
                    "Internal Server Error",
                    "Internal Server Error: " + e.getMessage()
            );
            response = new DefaultFullHttpResponse (
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        }
        return response;
    }

    private FullHttpResponse handleConnectionsCount(FullHttpRequest request) {
        FullHttpResponse response;    
        int count = server.getActiveConnections().get() / 2;
        response = new DefaultFullHttpResponse (
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer(String.valueOf(count), StandardCharsets.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, count);
        return response;
    }

    /**
     * 处理登录请求
     */
    private FullHttpResponse handleLogin(FullHttpRequest request) {
        FullHttpResponse response;
        if (request.method().equals(HttpMethod.GET)) {
            request.setUri("/login.html");
            response = handleStaticFile(request);
        } else if (request.method().equals(HttpMethod.POST)) {
            Map<String, String> params = parseFormData(request.content().toString(CharsetUtil.UTF_8));
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
                        File file = new File("static/login_success.html");
                        String content = new String(Files.readAllBytes(file.toPath()));
                        content = content.replace("{{ username }}", user.name());

                        response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1,
                                HttpResponseStatus.OK,
                                Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                        );
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

                        Cookie cookie = new DefaultCookie("sessionId", sessionId);
                        cookie.setHttpOnly(true);
                        cookie.setPath("/");
                        cookie.setMaxAge(3600);
                        String encodedCookie = ServerCookieEncoder.LAX.encode(cookie);
                        response.headers().add(HttpHeaderNames.SET_COOKIE, encodedCookie);

                        server.getActiveUsers().incrementAndGet();
                    } catch (IOException e) {
                        String content = errorHTMLPage(
                                500,
                                "Internal Server Error",
                                "Error reading login success page: " + e.getMessage()
                        );
                        response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1,
                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                        );
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                    }
                } else {
                    String content = errorHTMLPage(
                            401,
                            "Unauthorized",
                            "Invalid credentials, please try again."
                    );
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.UNAUTHORIZED,
                            Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                    );
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                }
            } else {
                String content = errorHTMLPage(
                        400,
                        "Bad Request",
                        "Username and password are required."
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_REQUEST,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        } else {
            String content = errorHTMLPage(
                    400,
                    "Bad Request",
                    "Bad Request"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        }
        return response;
    }

    /**
     * 处理退出登录请求
     */
    private FullHttpResponse handleLogout(FullHttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        FullHttpResponse response;

        if (sessionId == null) {
            String content = errorHTMLPage(
                    400,
                    "Bad Request",
                    "No session found, please login first."
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
            User user = server.getUsers().get(server.getSessions().get(sessionId).getUsername());
            server.getSessions().remove(sessionId);

            try {
                File file = new File("static/logout_success.html");
                String content = new String(Files.readAllBytes(file.toPath()));
                content = content.replace("{{ username }}", user.name());

                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

                Cookie cookie = new DefaultCookie("sessionId", "DeleteCookie");
                cookie.setHttpOnly(true);
                cookie.setPath("/");
                cookie.setMaxAge(0);
                String encodedCookie = ServerCookieEncoder.LAX.encode(cookie);
                response.headers().add(HttpHeaderNames.SET_COOKIE, encodedCookie);

                server.getActiveUsers().decrementAndGet();
            } catch (IOException e) {
                String content = errorHTMLPage(
                        500,
                        "Internal Server Error",
                        "Error reading login success page" + e.getMessage()
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        }

        return response;
    }

    /**
     * 处理仓库的文件搜索请求
     */
    private FullHttpResponse handleSearch(FullHttpRequest request) {
        String query = extractQueryParam(request.uri());
        File htmlFile = new File("static/store.html");
        FullHttpResponse response;

        if (!htmlFile.exists()) {
            String content = errorHTMLPage(
                    404,
                    "Not Found",
                    "Page Not Found"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
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

                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            } catch (IOException e) {
                String content = errorHTMLPage(
                        404,
                        "Not Found",
                        "Page Not Found"
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        }

        return response;
    }

    /**
     * 从请求路径中提取查询参数 ?q=search
     */
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
    private FullHttpResponse handleDownload(FullHttpRequest request) {
        FullHttpResponse response;
        String path = request.uri();
        String filename = path.substring("/repo/".length());
        File file = new File(HttpWebServer.RECOURSES_DIR, filename);

        if (!file.exists() || file.isDirectory()) {
            String content = errorHTMLPage(
                    404,
                    "Not Found",
                    "Page Not Found"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
            try {
                byte[] fileContent = Files.readAllBytes(file.toPath());
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(fileContent)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileContent.length);
                response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            } catch (IOException e) {
                String content = errorHTMLPage(
                        500,
                        "Internal Server Error",
                        "Internal Server Error" + e.getMessage()
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        }
        return response;
    }

    /**
     * 处理访问管理页面请求
     */
    private FullHttpResponse handleAdmin(FullHttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;
        FullHttpResponse response;

        if (session == null || !session.getUsername().equals("admin")) {
            String content = errorHTMLPage(
                    403,
                    "Forbidden",
                    "Admin access required"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
            try {
                // 读取 admin.html 文件
                File adminFile = new File("static/admin.html");
                if (adminFile.exists() && adminFile.isFile()) {
                    String content = new String(Files.readAllBytes(adminFile.toPath()));

                    // 获取服务器数据
                    long activeUsers = server.getActiveUsers().get();
                    long totalRequests = server.getTotalRequests().get();
                    long startTime = server.getStartTime().get();
                    long uptime = (System.currentTimeMillis() - startTime) / 1000;

                    // 替换占位符
                    content = content.replace("{{ activeUsers }}", String.valueOf(activeUsers));
                    content = content.replace("{{ totalRequests }}", String.valueOf(totalRequests));
                    content = content.replace("{{ startTime }}", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime)));
                    content = content.replace("{{ uptime }}", String.valueOf(uptime));

                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                    );
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                } else {
                    String content = errorHTMLPage(
                            404,
                            "Not Found",
                            "Page Not Found"
                    );
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.NOT_FOUND,
                            Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                    );
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                }
            } catch (IOException e) {
                String content = errorHTMLPage(
                        500,
                        "Internal Server Error",
                        "Error reading admin page: " + e.getMessage()
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        }
        return response;
    }

    /**
     * 处理关闭服务器请求
     */
    private FullHttpResponse handleShutdown(FullHttpRequest request) {
        String sessionId = getCookieValue(request, "sessionId");
        Session session = sessionId != null ? server.getSessions().get(sessionId) : null;
        FullHttpResponse response;

        if (session == null || !session.getUsername().equals("admin")) {
            String content = errorHTMLPage(
                    403,
                    "Forbidden",
                    "Admin access required"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
            // 防止重复关闭服务器
            if (!server.getShuttingDown().get()) {
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        server.stop();
                        System.exit(0);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
                server.getShuttingDown().set(true);
            }
            String content = "<!DOCTYPE html>" +
                    "<html><head><title>Shutdown</title>" +
                    "<link rel='stylesheet' href='/success_style.css'>" +
                    "</head><body class='error-page'>" +
                    "<div class='error-container'>" +
                    "<h1>"+ "Shutting Down" + "</h1>" +
                    "<div class='error-details'>" + "The server will shutdown in 5 seconds..." + "</div>" +
                    "</div></body></html>";
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
        }

        return response;
    }

    /**
     * 根据请求的路径处理静态文件
     * @param request 请求报文
     * @return 响应报文
     */
    private FullHttpResponse handleStaticFile(FullHttpRequest request) {
        String path = request.uri();
        if (path.equals("/")) {
            path = "/index.html";
        }
        System.out.println("handleStaticFile: " + path);
        File file = new File("static" + path);
        FullHttpResponse response;
        
        System.out.println(file.getPath());
        if (!file.exists() || file.isDirectory()) {
            String content = errorHTMLPage(
                    404,
                    "Forbidden",
                    "Admin access required"
            );
            response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        } else {
            try {
                byte[] byteContent = java.nio.file.Files.readAllBytes(file.toPath());
                String mimeType = getMimeType(file.getName());

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

                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            } catch (IOException e) {
                String content = errorHTMLPage(
                        500,
                        "Internal Server Error",
                        "Error reading file: " + e.getMessage()
                );
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
        }
        System.out.println("Response status: " + response.status());
        return response;
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
     * @param request 解析的 HTTP 请求对象
     * @param cookieName 需要的 cookie 名称
     * @return cookie 的值
     */
    private String getCookieValue(FullHttpRequest request, String cookieName) {
        String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader != null) {
            Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
            for (Cookie cookie : cookies) {
                if (cookie.name().equals(cookieName)) {
                    return cookie.value();
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
