package Server;

import util.HttpRequest;
import util.HttpResponse;

import java.io.*;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RequestLogger {
    private PrintWriter logWriter;
    private SimpleDateFormat dateFormat;
    
    public RequestLogger(String logFile) {
        try {
            this.logWriter = new PrintWriter(new FileWriter(logFile, true));
            this.dateFormat = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
        }
    }
    
    public synchronized void log(HttpRequest request, HttpResponse response, InetAddress clientAddress) {
        if (logWriter != null) {
            // Common Log Format: IP - - [timestamp] "method path version" status size
            String logEntry = String.format("%s - - [%s] \"%s\" %d %d",
                clientAddress.getHostAddress(),
                dateFormat.format(new Date()),
                request.toString(),
                response.getStatusCode(),
                response.getContentLength()
            );
            
            logWriter.println(logEntry);
            logWriter.flush();
        }
    }
    
    public void close() {
        if (logWriter != null) {
            logWriter.close();
        }
    }
}
