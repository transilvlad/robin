package com.mimecast.robin.scanners.port;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Probes SMTP and IMAP ports for reachability and TLS certificate health.
 *
 * <p>For each port a TCP connect is attempted. If the port is open, a TLS handshake
 * is initiated (either implicit for ports 465/993, or via STARTTLS for 25/587/143).
 * The certificate subject and expiry are extracted and days-until-expiry calculated.
 */
public class PortTlsChecker {
    private static final Logger log = LogManager.getLogger(PortTlsChecker.class);

    private PortTlsChecker() {}

    /**
     * Probes all requested ports on a host in parallel.
     *
     * @param host           Target hostname.
     * @param ports          Ports to check.
     * @param timeoutSeconds Connect + handshake timeout per port.
     * @return One result per port.
     */
    public static List<PortTlsResult> checkPorts(String host, List<Integer> ports, int timeoutSeconds) {
        if (host == null || host.isEmpty() || ports == null || ports.isEmpty()) {
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(ports.size(), 10));
        try {
            List<CompletableFuture<PortTlsResult>> futures = ports.stream()
                    .map(port -> CompletableFuture.supplyAsync(() -> checkPort(host, port, timeoutSeconds), executor))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()))
                    .get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error probing ports on {}: {}", host, e.getMessage());
            return new ArrayList<>();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Probes a single port.
     */
    public static PortTlsResult checkPort(String host, int port, int timeoutSeconds) {
        int timeoutMs = timeoutSeconds * 1000;
        PortTlsResult.Builder result = PortTlsResult.builder(host, port);

        // 1. TCP connect
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            result.open(true);
        } catch (Exception e) {
            log.debug("Port {}:{} closed or unreachable: {}", host, port, e.getMessage());
            return result.open(false).error(e.getMessage()).build();
        }

        // 2. TLS probe
        boolean implicitTls = (port == 465 || port == 993);
        try {
            SSLSocketFactory factory = buildTrustAllFactory();
            X509Certificate cert = implicitTls
                    ? probeImplicitTls(host, port, timeoutMs, factory)
                    : probeStartTls(host, port, timeoutMs, factory);

            if (cert != null) {
                LocalDate expiry = cert.getNotAfter().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                int days = (int) ChronoUnit.DAYS.between(LocalDate.now(), expiry);
                result.tlsStatus(PortTlsResult.TlsStatus.ENABLED)
                        .certSubject(cert.getSubjectX500Principal().getName())
                        .certExpiry(expiry)
                        .daysUntilExpiry(days);
            } else {
                result.tlsStatus(PortTlsResult.TlsStatus.DISABLED);
            }
        } catch (Exception e) {
            log.debug("TLS probe failed on {}:{}: {}", host, port, e.getMessage());
            result.tlsStatus(PortTlsResult.TlsStatus.ERROR).error(e.getMessage());
        }

        return result.build();
    }

    // ── TLS helpers ───────────────────────────────────────────────────────────

    private static X509Certificate probeImplicitTls(String host, int port, int timeoutMs,
                                                      SSLSocketFactory factory) throws Exception {
        try (SSLSocket ssl = (SSLSocket) factory.createSocket()) {
            ssl.connect(new InetSocketAddress(host, port), timeoutMs);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            return (X509Certificate) ssl.getSession().getPeerCertificates()[0];
        }
    }

    private static X509Certificate probeStartTls(String host, int port, int timeoutMs,
                                                   SSLSocketFactory factory) throws Exception {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);

            BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream()));
            PrintWriter out = new PrintWriter(plain.getOutputStream(), true);

            // Read banner
            String banner = in.readLine();
            if (banner == null) return null;

            boolean isImap = (port == 143);
            if (isImap) {
                // IMAP: send CAPABILITY, look for STARTTLS, then STARTTLS
                out.println("A001 CAPABILITY");
                String line;
                boolean hasStartTls = false;
                while ((line = in.readLine()) != null) {
                    if (line.contains("STARTTLS")) hasStartTls = true;
                    if (line.startsWith("A001 ")) break;
                }
                if (!hasStartTls) return null;
                out.println("A002 STARTTLS");
                String stResp = in.readLine();
                if (stResp == null || !stResp.contains("OK")) return null;
            } else {
                // SMTP: EHLO + STARTTLS
                out.println("EHLO portcheck.local");
                String line;
                boolean hasStartTls = false;
                while ((line = in.readLine()) != null) {
                    if (line.toUpperCase().contains("STARTTLS")) hasStartTls = true;
                    if (!line.startsWith("250-")) break;
                }
                if (!hasStartTls) return null;
                out.println("STARTTLS");
                String stResp = in.readLine();
                if (stResp == null || !stResp.startsWith("220")) return null;
            }

            // Upgrade to TLS
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            X509Certificate cert = (X509Certificate) ssl.getSession().getPeerCertificates()[0];
            ssl.close();
            return cert;
        }
    }

    private static SSLSocketFactory buildTrustAllFactory() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }
}
