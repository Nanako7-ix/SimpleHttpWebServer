package util;

import java.util.HashMap;
import java.util.Map;

public enum MimeType {
    HTML("text/html", "html", "htm"),
    CSS("text/css", "css"),
    JAVASCRIPT("application/javascript", "js"),
    JSON("application/json", "json"),
    PLAIN_TEXT("text/plain", "txt"),
    XML("application/xml", "xml"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg", "jpeg"),
    GIF("image/gif", "gif"),
    ICO("image/x-icon", "ico"),
    SVG("image/svg+xml", "svg"),
    WEBP("image/webp", "webp"),
    BMP("image/bmp", "bmp"),
    MP3("audio/mpeg", "mp3"),
    WAV("audio/wav", "wav"),
    OGG("audio/ogg", "ogg"),
    MP4("video/mp4", "mp4"),
    AVI("video/x-msvideo", "avi"),
    MOV("video/quicktime", "mov"),
    WEBM("video/webm", "webm"),
    PDF("application/pdf", "pdf"),
    DOC("application/msword", "doc"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    XLS("application/vnd.ms-excel", "xls"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PPT("application/vnd.ms-powerpoint", "ppt"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    ZIP("application/zip", "zip"),
    RAR("application/vnd.rar", "rar"),
    TAR("application/x-tar", "tar"),
    GZIP("application/gzip", "gz"),
    TTF("font/ttf", "ttf"),
    OTF("font/otf", "otf"),
    WOFF("font/woff", "woff"),
    WOFF2("font/woff2", "woff2"),
    OCTET_STREAM("application/octet-stream");

    private final String mimeType;
    private final String[] extensions;

    private static final Map<String, MimeType> EXTENSION_MAP = new HashMap<>();

    static {
        // Build the extension to MimeType mapping
        for (MimeType type : util.MimeType.values()) {
            for (String extension : type.extensions) {
                EXTENSION_MAP.put(extension.toLowerCase(), type);
            }
        }
    }

    MimeType(String mimeType, String... extensions) {
        this.mimeType = mimeType;
        this.extensions = extensions;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String[] getExtensions() {
        return extensions.clone(); // Return a copy to prevent modification
    }

    /**
     * Get MimeType by file extension
     * @param extension file extension (without dot)
     * @return MimeType enum, or OCTET_STREAM if not found
     */
    public static MimeType fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return OCTET_STREAM;
        }
        return EXTENSION_MAP.getOrDefault(extension.toLowerCase(), OCTET_STREAM);
    }

    /**
     * Get MimeType by filename
     * @param filename complete filename
     * @return MimeType enum, or OCTET_STREAM if not found
     */
    public static MimeType fromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return OCTET_STREAM;
        }

        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return OCTET_STREAM;
        }

        String extension = filename.substring(lastDotIndex + 1);
        return fromExtension(extension);
    }

    /**
     * Check if this MimeType represents a text-based content
     * @return true if it's a text-based MIME type
     */
    public boolean isText() {
        return mimeType.startsWith("text/") ||
                this == JSON ||
                this == JAVASCRIPT ||
                this == XML;
    }

    /**
     * Check if this MimeType represents an image
     * @return true if it's an image MIME type
     */
    public boolean isImage() {
        return mimeType.startsWith("image/");
    }

    /**
     * Check if this MimeType represents audio content
     * @return true if it's an audio MIME type
     */
    public boolean isAudio() {
        return mimeType.startsWith("audio/");
    }

    /**
     * Check if this MimeType represents video content
     * @return true if it's a video MIME type
     */
    public boolean isVideo() {
        return mimeType.startsWith("video/");
    }

    @Override
    public String toString() {
        return mimeType;
    }
}
