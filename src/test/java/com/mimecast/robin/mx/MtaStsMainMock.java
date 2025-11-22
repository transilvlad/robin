package com.mimecast.robin.mx;

import com.mimecast.robin.mx.cache.MemoryPolicyCache;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.mx.util.LocalHttpsPolicyClient;
import com.mimecast.robin.trust.PermissiveTrustManager;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI runnable.
 *
 * @author "Vlad Marian" <vmarian@mimecast.com>
 * @link <a href="http://mimecast.com">Mimecast</a>
 */
final class MtaStsMainMock extends MtaStsMain {

    /**
     * Logs list.
     */
    private List<String> logs;

    /**
     * Main runnable.
     * <p>Override StrictTransportSecurity instance with new using HttpsPolicyClientMock instance param.
     *
     * @param args String array.
     * @param port HTTPS mock server port.
     */
    public static List<String> main(String[] args, int port) throws InstantiationException {
        strictTransportSecurity = new StrictTransportSecurity(new XBillDnsRecordClient(), new LocalHttpsPolicyClient(new PermissiveTrustManager(), port), new MemoryPolicyCache());

        MtaStsMainMock main = new MtaStsMainMock(args);
        return main.getLogs();
    }

    /**
     * Constructs a new Main instance.
     *
     * @param args String array.
     */
    private MtaStsMainMock(String[] args) {
        super(args);
    }

    /**
     * Logging wrapper.
     *
     * @param string String.
     */
    @Override
    protected void log(String string) {
        super.log(string);
        if (logs == null) {
            logs = new ArrayList<>();
        }
        logs.add(string);
    }

    /**
     * Gets logs.
     *
     * @return List of String.
     */
    private List<String> getLogs() {
        return logs;
    }
}
