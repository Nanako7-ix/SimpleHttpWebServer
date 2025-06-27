package util;

import java.io.Serializable;

public class Session implements Serializable {
    private final String sessionId;
    private final String username;

    public Session(String sessionId, String username) {
        this.sessionId = sessionId;
        this.username = username;
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getUsername() { return username; }
}
