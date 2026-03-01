package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimDetectedSelector;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExistingDkimDetectorTest {

    @Mock
    private DkimKeyRepository repository;

    private ExistingDkimDetector detector;
    private MockResolver mockResolver;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);
        mockResolver = new MockResolver();
        detector = new ExistingDkimDetector(repository, mockResolver);
    }

    @AfterEach
    void tearDown() throws Exception {
        detector.shutdown();
        closeable.close();
    }

    @Test
    void testDetectGoogleAndMicrosoftSelectors() throws Exception {
        // Mock Google selector TXT record
        mockResolver.addTxtRecord("google._domainkey.example.com", 
                "v=DKIM1; k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA12345");
        
        // Mock Microsoft selector TXT record
        mockResolver.addTxtRecord("selector1._domainkey.example.com", 
                "v=DKIM1; k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA67890");
        
        // Mock a revoked selector
        mockResolver.addTxtRecord("k1._domainkey.example.com", "v=DKIM1; p=");

        long start = System.currentTimeMillis();
        List<DkimDetectedSelector> detected = detector.probe("example.com");
        long duration = System.currentTimeMillis() - start;

        // Ensure probe completes within 2 seconds
        assertTrue(duration < 2000, "Probe took longer than 2 seconds: " + duration + "ms");
        
        // Google, Microsoft, and revoked selector should be detected
        assertEquals(3, detected.size());

        boolean googleFound = false;
        boolean selector1Found = false;
        boolean revokedFound = false;

        for (DkimDetectedSelector selector : detected) {
            if ("google".equals(selector.getSelector())) {
                assertEquals("rsa", selector.getAlgorithm());
                assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA12345", selector.getPublicKeyDns());
                assertFalse(selector.isRevoked());
                googleFound = true;
            } else if ("selector1".equals(selector.getSelector())) {
                assertEquals("rsa", selector.getAlgorithm());
                assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA67890", selector.getPublicKeyDns());
                assertFalse(selector.isRevoked());
                selector1Found = true;
            } else if ("k1".equals(selector.getSelector())) {
                assertTrue(selector.isRevoked());
                assertEquals("", selector.getPublicKeyDns());
                revokedFound = true;
            }
        }

        assertTrue(googleFound, "Google selector not found");
        assertTrue(selector1Found, "Microsoft selector1 not found");
        assertTrue(revokedFound, "Revoked selector not found");
        
        // Verify repository save was called
        verify(repository, times(3)).saveDetectedSelector(any(DkimDetectedSelector.class));
    }

    // A simple mock resolver that returns predefined TXT records
    private static class MockResolver implements Resolver {
        private final java.util.Map<String, String> txtRecords = new java.util.HashMap<>();

        void addTxtRecord(String name, String value) {
            txtRecords.put(name + ".", value); // xbill names usually end with a dot
        }

        @Override
        public Message send(Message query) {
            Record question = query.getQuestion();
            String name = question.getName().toString();
            
            Message response = new Message(query.getHeader().getID());
            response.getHeader().setFlag(Flags.QR);
            response.addRecord(question, Section.QUESTION);

            if (question.getType() == Type.TXT && txtRecords.containsKey(name)) {
                try {
                    TXTRecord txtRecord = new TXTRecord(question.getName(), question.getDClass(), 3600, txtRecords.get(name));
                    response.addRecord(txtRecord, Section.ANSWER);
                } catch (Exception e) {
                    response.getHeader().setRcode(Rcode.SERVFAIL);
                }
            } else {
                response.getHeader().setRcode(Rcode.NXDOMAIN);
            }

            return response;
        }

        @Override
        public Object sendAsync(Message query, ResolverListener listener) {
            listener.receiveMessage(this, send(query));
            return null;
        }

        @Override
        public void setPort(int port) {}
        @Override
        public void setTCP(boolean flag) {}
        @Override
        public void setIgnoreTruncation(boolean flag) {}
        @Override
        public void setEDNS(int level) {}
        @Override
        public void setEDNS(int level, int payloadSize, int flags, List<EDNSOption> options) {}
        @Override
        public void setTSIGKey(TSIG key) {}
        @Override
        public void setTimeout(java.time.Duration timeout) {}
    }
}
