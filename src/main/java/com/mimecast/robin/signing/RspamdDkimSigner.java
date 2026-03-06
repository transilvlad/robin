package com.mimecast.robin.signing;

import com.mimecast.robin.scanners.RspamdClient;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * DKIM signer that delegates to Rspamd via its HTTP signing API.
 * <p>
 * Posts the email to Rspamd's {@code /checkv2} endpoint with
 * {@code PerformDkimSign}, {@code DkimDomain}, {@code DkimSelector}, and
 * {@code DkimPrivateKey} headers, then extracts the resulting
 * {@code DKIM-Signature} value from the milter response.
 * <p>
 * Rspamd must be configured with {@code use_http_headers = true} and
 * {@code use_milter_headers = true} in {@code dkim_signing.conf}.
 */
public class RspamdDkimSigner implements DkimSigner {

    private final RspamdClient client;

    /**
     * Constructs a signer targeting the given Rspamd host and port.
     *
     * @param host Rspamd server host.
     * @param port Rspamd server port.
     */
    public RspamdDkimSigner(String host, int port) {
        this.client = new RspamdClient(host, port);
    }

    @Override
    public Optional<String> sign(File emailFile, String domain, String selector, String privateKey) throws IOException {
        return client.sign(emailFile, domain, selector, privateKey);
    }
}
