package com.mimecast.robin.mime.headers;

import com.mimecast.robin.mime.EmailParser;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Chaos headers container for testing exception scenarios.
 * 
 * <p>This class provides access to X-Robin-Chaos headers from parsed emails.
 * The chaos headers feature allows testing of exception scenarios by bypassing
 * normal processing and returning predefined results.
 * 
 * <p>The value format is: {@code ClassName; param1=value1; param2=value2}
 * where ClassName represents the implementation class where the action occurs.
 * 
 * <p>Example usage:
 * <pre>
 * ChaosHeaders chaosHeaders = new ChaosHeaders(emailParser);
 * List&lt;MimeHeader&gt; ldaHeaders = chaosHeaders.getByValue("DovecotLdaClient");
 * </pre>
 * 
 * @see EmailParser
 * @see MimeHeader
 */
public class ChaosHeaders {
    
    /**
     * Header name for chaos testing.
     */
    public static final String HEADER_NAME = "X-Robin-Chaos";
    
    /**
     * List of chaos headers found in the email.
     */
    private final List<MimeHeader> headers = new ArrayList<>();
    
    /**
     * Constructs a new ChaosHeaders instance from an EmailParser.
     * 
     * @param parser EmailParser instance containing parsed headers.
     */
    public ChaosHeaders(EmailParser parser) {
        if (parser != null && parser.getHeaders() != null) {
            headers.addAll(parser.getHeaders().getAll(HEADER_NAME));
        }
    }
    
    /**
     * Gets all chaos headers.
     * 
     * @return List of MimeHeader instances for chaos headers.
     */
    public List<MimeHeader> getHeaders() {
        return new ArrayList<>(headers);
    }
    
    /**
     * Gets chaos headers filtered by clean value (class name).
     * 
     * @param value The class name to filter by (e.g., "LocalStorageClient", "DovecotLdaClient").
     * @return List of MimeHeader instances matching the value, or empty list if none found.
     */
    public List<MimeHeader> getByValue(String value) {
        if (value == null) {
            return new ArrayList<>();
        }
        
        return headers.stream()
                .filter(h -> value.equalsIgnoreCase(h.getCleanValue()))
                .collect(Collectors.toList());
    }
    
    /**
     * Checks if chaos headers are present.
     * 
     * @return True if at least one chaos header exists, false otherwise.
     */
    public boolean hasHeaders() {
        return !headers.isEmpty();
    }
}
