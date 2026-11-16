package com.mimecast.robin.scanners;

import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationHeadersTest {
    private static final AuthenticationHeaders.Resolver NO_DNS = host -> {
        throw new UnknownHostException(host);
    };

    private static Session session(String address, String rdns) {
        Session session = new Session();
        session.setFriendAddr(address);
        session.setFriendRdns(rdns);
        session.setEhlo("mail.sender.example");
        return session;
    }

    private static MessageEnvelope envelope(String sender) {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail(sender);
        return envelope;
    }

    private static Map<String, Object> symbol(String... options) {
        return Map.of("score", 0.0, "options", List.of((Object[]) options));
    }

    @Test
    void reportsEveryVerdictRspamdGaveWithItsProperties() {
        Map<String, Object> rspamd = Map.of("symbols", Map.of(
                "R_SPF_ALLOW", symbol("+ip4:198.51.100.7"),
                "R_DKIM_ALLOW", symbol("sender.example:s=mail"),
                "DMARC_POLICY_ALLOW", symbol("sender.example", "none"),
                "ARC_NA", symbol()));

        String value = AuthenticationHeaders.authenticationResults("mx.robin.test",
                session("10.0.0.5", "10.0.0.5"), envelope("alice@sender.example"), rspamd, NO_DNS);

        assertEquals("mx.robin.test;\r\n"
                + "\tspf=pass smtp.mailfrom=alice@sender.example;\r\n"
                + "\tdkim=pass header.d=sender.example;\r\n"
                + "\tdmarc=pass header.from=sender.example;\r\n"
                + "\tarc=none", value);
    }

    @Test
    void failuresAndErrorsMapToRfc8601Results() {
        Map<String, Object> rspamd = Map.of("symbols", Map.of(
                "R_SPF_SOFTFAIL", symbol(), "R_DKIM_REJECT", symbol("sender.example:s=mail"),
                "DMARC_POLICY_QUARANTINE", symbol("sender.example", "quarantine")));

        String value = AuthenticationHeaders.authenticationResults("mx.robin.test",
                session("10.0.0.5", "10.0.0.5"), envelope(""), rspamd, NO_DNS);

        assertTrue(value.contains("spf=softfail smtp.helo=mail.sender.example"),
                "A null sender is checked by its HELO identity: " + value);
        assertTrue(value.contains("dkim=fail header.d=sender.example"), value);
        assertTrue(value.contains("dmarc=fail header.from=sender.example"), value);
    }

    @Test
    void withoutRspamdTheHeaderStillCarriesWhatRobinKnows() {
        Session submission = session("10.0.0.5", "10.0.0.5");
        submission.setAuth(true);
        submission.setUsername("tony@example.com");

        assertEquals("mx.robin.test;\r\n\tauth=pass smtp.auth=tony@example.com",
                AuthenticationHeaders.authenticationResults("mx.robin.test", submission, envelope("tony@example.com"), null, NO_DNS));
        assertEquals("mx.robin.test; none",
                AuthenticationHeaders.authenticationResults("mx.robin.test", session("10.0.0.5", "10.0.0.5"),
                        envelope("a@b.example"), null, NO_DNS));
    }

    @Test
    void iprevIsCheckedForPublicClientsOnly() throws Exception {
        InetAddress client = InetAddress.getByName("198.51.100.7");
        AuthenticationHeaders.Resolver matching = host -> new InetAddress[]{client};
        AuthenticationHeaders.Resolver other = host -> new InetAddress[]{InetAddress.getByName("203.0.113.1")};

        assertEquals("x;\r\n\tiprev=pass policy.iprev=198.51.100.7 (mail.sender.example)",
                AuthenticationHeaders.authenticationResults("x", session("198.51.100.7", "mail.sender.example"),
                        envelope("a@b.example"), null, matching));
        assertTrue(AuthenticationHeaders.authenticationResults("x", session("198.51.100.7", "mail.sender.example"),
                envelope("a@b.example"), null, other).contains("iprev=fail policy.iprev=198.51.100.7"));
        assertTrue(AuthenticationHeaders.authenticationResults("x", session("198.51.100.7", "198.51.100.7"),
                envelope("a@b.example"), null, matching).contains("iprev=permerror"), "No PTR name");
        assertFalse(AuthenticationHeaders.authenticationResults("x", session("192.168.1.9", "192.168.1.9"),
                envelope("a@b.example"), null, matching).contains("iprev"), "Private clients are not checked");
    }

    @Test
    void spamHeadersFollowTheScoreAndAction() {
        Map<String, Object> clean = Map.of("score", 0.9, "action", "no action",
                "thresholds", Map.of("add header", 6.0, "reject", 15.0));
        Map<String, Object> marked = Map.of("score", 7.25, "action", "add header", "required_score", 15.0);

        List<String[]> headers = AuthenticationHeaders.spamHeaders(clean);
        assertEquals(2, headers.size());
        assertEquals("X-Spam-Status", headers.get(0)[0]);
        assertEquals("No, score=0.90 required=6.00 action=\"no action\"", headers.get(0)[1]);
        assertEquals("0.90", headers.get(1)[1]);

        headers = AuthenticationHeaders.spamHeaders(marked);
        assertEquals("Yes, score=7.25 required=15.00 action=\"add header\"", headers.get(0)[1]);
        assertEquals("X-Spam-Flag", headers.get(2)[0]);

        assertTrue(AuthenticationHeaders.spamHeaders(null).isEmpty());
        assertTrue(AuthenticationHeaders.spamHeaders(Map.of("score", Double.NaN)).isEmpty());
    }

    @Test
    void ourAuthservIdIsRecognisedThroughCommentsAndFolding() {
        assertTrue(AuthenticationHeaders.claims("MX.Robin.Test; spf=pass", "mx.robin.test"));
        assertTrue(AuthenticationHeaders.claims("(forged) mx.robin.test 1;\r\n\tdkim=pass", "mx.robin.test"));
        assertTrue(AuthenticationHeaders.claims("mx.robin.test; none", "mx.robin.test"));
        assertFalse(AuthenticationHeaders.claims("mx.robin.test.evil; spf=pass", "mx.robin.test"));
        assertFalse(AuthenticationHeaders.claims("other.example; spf=pass", "mx.robin.test"));
    }
}
