package com.mimecast.robin.scanners.port;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
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
        return checkPorts(host, ports, timeoutSeconds, "portcheck.local");
    }

    /**
     * Probes all requested ports on a host in parallel.
     *
     * @param host           Target hostname.
     * @param ports          Ports to check.
     * @param timeoutSeconds Connect + handshake timeout per port.
     * @param ehloName       EHLO name used for SMTP STARTTLS probing.
     * @return One result per port.
     */
    public static List<PortTlsResult> checkPorts(String host, List<Integer> ports, int timeoutSeconds, String ehloName) {
        if (host == null || host.isEmpty() || ports == null || ports.isEmpty()) {
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(ports.size(), 10));
        try {
            List<CompletableFuture<PortTlsResult>> futures = ports.stream()
                    .map(port -> CompletableFuture.supplyAsync(() -> checkPort(host, port, timeoutSeconds, ehloName), executor))
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
        return checkPort(host, port, timeoutSeconds, "portcheck.local");
    }

    /**
     * Probes a single port.
     */
    public static PortTlsResult checkPort(String host, int port, int timeoutSeconds, String ehloName) {
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
            ProbeDetails details = implicitTls
                    ? probeImplicitTls(host, port, timeoutMs, factory)
                    : probeStartTls(host, port, timeoutMs, factory, ehloName);

            result.banner(details.banner())
                    .extensions(details.extensions())
                    .negotiatedProtocol(details.protocol());

            X509Certificate cert = details.cert();
            if (cert != null) {
                LocalDate expiry = cert.getNotAfter().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                int days = (int) ChronoUnit.DAYS.between(LocalDate.now(), expiry);
                result.tlsStatus(PortTlsResult.TlsStatus.ENABLED)
                        .certSubject(cert.getSubjectX500Principal().getName())
                        .certIssuer(cert.getIssuerX500Principal().getName())
                        .certExpiry(expiry)
                        .daysUntilExpiry(days)
                        .certKeyBits(publicKeyBits(cert.getPublicKey()))
                        .hostnameMatch(hostnameMatches(host, details.session()));
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

    private static ProbeDetails probeImplicitTls(String host, int port, int timeoutMs,
                                                 SSLSocketFactory factory) throws Exception {
        try (SSLSocket ssl = (SSLSocket) factory.createSocket()) {
            ssl.connect(new InetSocketAddress(host, port), timeoutMs);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            X509Certificate cert = (X509Certificate) ssl.getSession().getPeerCertificates()[0];
            return new ProbeDetails(null, List.of(), cert, ssl.getSession(), ssl.getSession().getProtocol());
        }
    }

    private static ProbeDetails probeStartTls(String host, int port, int timeoutMs,
                                              SSLSocketFactory factory, String ehloName) throws Exception {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);

            BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream()));
            PrintWriter out = new PrintWriter(plain.getOutputStream(), true);

            // Read banner
            String banner = in.readLine();
            if (banner == null) return ProbeDetails.noTls(null, List.of());

            List<String> extensions = new ArrayList<>();
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
                if (!hasStartTls) return ProbeDetails.noTls(banner, extensions);
                out.println("A002 STARTTLS");
                String stResp = in.readLine();
                if (stResp == null || !stResp.contains("OK")) return ProbeDetails.noTls(banner, extensions);
            } else {
                // SMTP: EHLO + STARTTLS
                out.println("EHLO " + ((ehloName == null || ehloName.isBlank()) ? "portcheck.local" : ehloName));
                String line;
                boolean hasStartTls = false;
                while ((line = in.readLine()) != null) {
                    if (line.toUpperCase().contains("STARTTLS")) hasStartTls = true;
                    if (line.startsWith("250-") || line.startsWith("250 ")) {
                        extensions.add(line.length() > 4 ? line.substring(4).trim() : "");
                    }
                    if (!line.startsWith("250-")) break;
                }
                if (!hasStartTls) return ProbeDetails.noTls(banner, extensions);
                out.println("STARTTLS");
                String stResp = in.readLine();
                if (stResp == null || !stResp.startsWith("220")) return ProbeDetails.noTls(banner, extensions);
            }

            // Upgrade to TLS
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            X509Certificate cert = (X509Certificate) ssl.getSession().getPeerCertificates()[0];
            SSLSession session = ssl.getSession();
            String protocol = session.getProtocol();
            ssl.close();
            return new ProbeDetails(banner, extensions, cert, session, protocol);
        }
    }

    private static boolean hostnameMatches(String host, SSLSession session) {
        if (host == null || host.isBlank() || session == null) {
            return false;
        }
        return HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
    }

    private static int publicKeyBits(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (key instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        if (key instanceof EdECPublicKey ed) {
            return "Ed25519".equalsIgnoreCase(ed.getParams().getName()) ? 256 : 0;
        }
        return 0;
    }

    private record ProbeDetails(String banner, List<String> extensions, X509Certificate cert,
                                SSLSession session, String protocol) {
        static ProbeDetails noTls(String banner, List<String> extensions) {
            return new ProbeDetails(banner, extensions == null ? List.of() : List.copyOf(extensions),
                    null, null, null);
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
