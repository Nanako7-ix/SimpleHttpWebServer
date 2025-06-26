package util;

import java.io.Serializable;
import java.util.Date;

public class Session implements Serializable {
    private final String sessionId;
    private final String username;
    private final Date createdAt;
    private Date lastAccessed;

    public Session(String sessionId, String username) {
        this.sessionId = sessionId;
        this.username = username;
        this.createdAt = new Date();
        this.lastAccessed = new Date();
    }

    public void updateLastAccessed() {
        this.lastAccessed = new Date();
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getUsername() { return username; }
    public Date getCreatedAt() { return createdAt; }
    public Date getLastAccessed() { return lastAccessed; }
}
