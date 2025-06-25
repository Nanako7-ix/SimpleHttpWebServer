package util;

import java.util.Map;

public record HttpRequest(String method, String path, String version, Map<String, String> headers, String body) {
    @Override
    public String toString() {
        return method + " " + path + " " + version;
    }
}
