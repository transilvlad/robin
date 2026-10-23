package com.mimecast.robin.scanners.port;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLContext;
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
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
                        .certKeyAlgorithm(publicKeyAlgorithm(cert.getPublicKey()))
                        .certKeyBits(publicKeyBits(cert.getPublicKey()))
                        .hostnameMatch(hostnameMatches(host, certificateNames(cert)));
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

    /**
     * Names a certificate is issued for: its DNS SANs, or its subject CN when it has none (RFC 6125).
     */
    private static List<String> certificateNames(X509Certificate cert) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String name) {
                        names.add(name);
                    }
                }
            }
        } catch (CertificateParsingException e) {
            log.debug("Could not read SANs from {}: {}", cert.getSubjectX500Principal().getName(), e.getMessage());
        }
        if (names.isEmpty()) {
            try {
                for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType())) {
                        names.add(rdn.getValue().toString());
                    }
                }
            } catch (InvalidNameException e) {
                log.debug("Could not parse certificate subject: {}", e.getMessage());
            }
        }
        return names;
    }

    /**
     * Matches a hostname against certificate names. A wildcard covers exactly one leftmost label.
     */
    static boolean hostnameMatches(String host, List<String> certNames) {
        if (host == null || host.isBlank() || certNames == null) {
            return false;
        }
        String target = normalizeHostname(host);
        for (String certName : certNames) {
            if (certName == null || certName.isBlank()) continue;
            String name = normalizeHostname(certName);
            if (name.startsWith("*.")) {
                String suffix = name.substring(1);
                int dot = target.indexOf('.');
                if (dot > 0 && target.substring(dot).equals(suffix)) {
                    return true;
                }
            } else if (name.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHostname(String name) {
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String publicKeyAlgorithm(PublicKey key) {
        if (key instanceof RSAPublicKey) return "RSA";
        if (key instanceof ECPublicKey) return "EC";
        if (key instanceof EdECPublicKey ed) return ed.getParams().getName();
        return key.getAlgorithm();
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
