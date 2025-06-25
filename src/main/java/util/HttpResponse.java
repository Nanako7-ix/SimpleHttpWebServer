package util;

import java.util.ArrayList;
import java.util.List;

public class HttpResponse {
    private final int statusCode;
    private final String statusText;
    private final String contentType;
    private final byte[] content;
    private final List<String> cookies;

    public HttpResponse(int statusCode, String statusText, String contentType, String content) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.contentType = contentType;
        this.content = content.getBytes();
        this.cookies = new ArrayList<>();
    }

    public HttpResponse(int statusCode, String statusText, String contentType, byte[] content) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.contentType = contentType;
        this.content = content;
        this.cookies = new ArrayList<>();
    }

    public void addCookie(String name, String value) {
        cookies.add(name + "=" + value + "; Path=/");
    }

    public void eraseCookie(String name) {
        cookies.add(name + "=DeleteCookie; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
    }

    public int getStatusCode() { return statusCode; }
    public String getStatusText() { return statusText; }
    public String getContentType() { return contentType; }
    public byte[] getContent() { return content; }
    public int getContentLength() { return content != null ? content.length : 0; }
    public List<String> getCookies() { return cookies; }
}
