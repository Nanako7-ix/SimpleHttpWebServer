package util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


public class HttpResponse {
    private final int statusCode;
    private final String statusText;
    private final String contentType;
    private final byte[] content;
    private final List<String> cookies;
    private final Map<String, String> headers;

    public HttpResponse(int statusCode, String statusText, String contentType, String content) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.contentType = contentType;
        this.content = content.getBytes();
        this.cookies = new ArrayList<>();
        this.headers = new HashMap<>();
    }

    public HttpResponse(int statusCode, String statusText, String contentType, byte[] content) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.contentType = contentType;
        this.content = content;
        this.cookies = new ArrayList<>();
        this.headers = new HashMap<>();
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
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
    public Map<String, String> getHeaders() { return headers; }
}
