package com.mimecast.robin.smtp.auth;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DigestMD5Test {

    private static DigestCache database;

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        database = new StaticDigestCache();
    }

    private final String host = "example.com";
    private final String username = "tony@" + host;
    private final String password = "stark";
    private final String realm = "example.com";

    @Test
    void validateUser() {
        assertThrows(IllegalArgumentException.class, () -> new DigestMD5Server(host, "",  password, realm));
    }

    @Test
    void validatePass() {
        assertThrows(IllegalArgumentException.class, () -> new DigestMD5Server(host, username,  "", realm));
    }

    @Test
    void setRandomSize() throws DecoderException {
        DigestMD5Server server = new DigestMD5Server(host, username,  password, realm);
        server.setDigestDatabase(database);
        server.setRandomSize(Math.toIntExact(Config.getProperties().getLongProperty("digestmd5.random", 32L)));

        String challenge = DigestUtils.decode(server.generateChallenge());
        DigestData data = DigestUtils.parsePayload(challenge);

        assertFalse(data.getNonce().isEmpty());
        assertEquals(64, Hex.decodeHex(data.getNonce()).length);
    }

    @Test
    void generateChallenge() {
        DigestMD5Server server = new DigestMD5Server(host, username,  password, realm);
        server.setDigestDatabase(database);
        String challenge = DigestUtils.decode(server.generateChallenge());

        assertNotNull(challenge);
        assertTrue(challenge.contains("username=\"" + username));
        assertTrue(challenge.contains("realm=\"" + realm));

        assertTrue(challenge.contains("qop=auth"));
        assertTrue(challenge.contains("nc=00000001"));

        assertTrue(challenge.contains("nonce="));
        assertTrue(challenge.contains("digest-uri=\"smtp/example.com\""));
    }

    @Test
    void parseChallenge() {
        DigestMD5Server server = new DigestMD5Server(host, username,  password, realm);
        server.setDigestDatabase(database);
        String challenge = DigestUtils.decode(server.generateChallenge());

        DigestData data = DigestUtils.parsePayload(challenge);
        assertEquals(username, data.getUsername());
        assertEquals(realm, data.getRealm());

        assertEquals("auth", data.getQop());
        assertEquals("00000001", data.getNc());

        assertFalse(data.getNonce().isEmpty());
        assertTrue(data.getMap().containsKey("digest-uri") && data.getMap().get("digest-uri").equals("smtp/" + host));
    }

    @Test
    void parseChallengeException() {
        // Triggers ParseException: Unbalanced quoted string.
        DigestData data = DigestUtils.parsePayload("username=\"test@example.com");

        // Asserts to empty list as the exception is caught.
        assertEquals(0, data.getMap().size());
    }

    @Test
    void buildPayload() {
        Map<String, String> map = new HashMap<>();
        map.put("username", username);
        map.put("realm", realm);
        map.put("nonce", "nonce");
        map.put("cnonce", "cnonce");
        map.put("nc", "1");
        map.put("qop", "auth");
        map.put("host", host);
        map.put("response", "response");

        StringBuilder built = DigestUtils.buildPayload(map);

        assertEquals("username=\"tony@example.com\",realm=\"example.com\",qop=auth,nc=00000001,nonce=\"nonce\",cnonce=\"cnonce\",digest-uri=\"smtp/example.com\",response=response", built.toString());
    }

    @Test
    void buildPayloadEmpty() {
        Map<String, String> map = new HashMap<>();
        StringBuilder built = DigestUtils.buildPayload(map);

        assertEquals("username=\"\",realm=\"\",qop=auth,nc=00000001,nonce=\"\"", built.toString());
    }

    @Test
    void authenticateClient() {
        DigestMD5Server server = new DigestMD5Server(host, username,  password, realm);
        server.setDigestDatabase(database);
        String challenge = DigestUtils.decode(server.generateChallenge());

        DigestMD5Client client = new DigestMD5Client(host, username,  password, realm);
        client.setDigestDatabase(database);
        String response = client.authenticateClient(challenge);

        DigestData data = DigestUtils.parsePayload(response);
        assertEquals(username, data.getUsername());
        assertEquals(realm, data.getRealm());

        assertEquals("auth", data.getQop());
        assertEquals("00000001", data.getNc());

        assertFalse(data.getNonce().isEmpty());
        assertFalse(data.getCnonce().isEmpty());

        assertTrue(data.getMap().containsKey("digest-uri") && data.getMap().get("digest-uri").equals("smtp/" + host));
        assertFalse(data.getResponse().isEmpty());

        Optional<String> auth = server.authenticateClient(response);
        assertTrue(auth.isPresent());
    }

    @Test
    void authenticateServer() {
        Random cnonce = new NotRandom("1f2dc86a79b1bbbbe4a3df34a3ebf8ba28f1f3553490612d372e908ee8e4c669==");

        String challenge = "cmVhbG09IiIsbm9uY2U9IlRIRWRCNDZ4ZzYrRWRhU1VNU3VIN2c9PSIscW9wPSJhdXRoIixjaGFyc2V0PSJ1dGYtOCIsYWxnb3JpdGhtPSJtZDUtc2VzcyI=";
        String response = "dXNlcm5hbWU9InRvbnlAZXhhbXBsZS5jb20iLHJlYWxtPSIiLHFvcD1hdXRoLG5jPTAwMDAwMDAxLG5vbmNlPSJUSEVkQjQ2eGc2K0VkYVNVTVN1SDdnPT0iLGNub25jZT0iMWYyZGM4NmE3OWIxYmJiYmU0YTNkZjM0YTNlYmY4YmEyOGYxZjM1NTM0OTA2MTJkMzcyZTkwOGVlOGU0YzY2OT09IixkaWdlc3QtdXJpPSJzbXRwLyIscmVzcG9uc2U9ZDk0N2YxN2M0Nzc1YTZiYTJiOGEzZWU5MmQ2MTVmZmU=";
        String second = "dXNlcm5hbWU9InRvbnlAZXhhbXBsZS5jb20iLHJlYWxtPSIiLHFvcD1hdXRoLG5jPTAwMDAwMDAyLG5vbmNlPSJUSEVkQjQ2eGc2K0VkYVNVTVN1SDdnPT0iLGNub25jZT0iMWYyZGM4NmE3OWIxYmJiYmU0YTNkZjM0YTNlYmY4YmEyOGYxZjM1NTM0OTA2MTJkMzcyZTkwOGVlOGU0YzY2OT09IixkaWdlc3QtdXJpPSJzbXRwLyIscmVzcG9uc2U9ZDk0N2YxN2M0Nzc1YTZiYTJiOGEzZWU5MmQ2MTVmZmU=";
        String server = "cnNwYXV0aD1iNTIwZjI2YTk1YzMzYWI3M2I5Y2I4ZTgxMTI1YWNiYg==";

        // Initialize client.
        DigestMD5Client client = new DigestMD5Client("", username,  password, "");
        client.setDigestDatabase(database);
        client.setRandom(cnonce);

        // Original authentication.
        String origAuth = client.authenticateClient(challenge);
        assertTrue(client.authenticateServer(server));

        // Reinitialize client.
        client = new DigestMD5Client("", username,  password, "");
        client.setDigestDatabase(database);
        client.setRandom(cnonce);

        // Subsequent authentication.
        String subAuth = client.getSubsequentAuthentication();

        DigestData dataOrig = DigestUtils.parsePayload(origAuth);
        DigestData dataAuth = DigestUtils.parsePayload(subAuth);

        assertEquals(8, dataAuth.getMap().size());

        assertEquals(dataOrig.getQop(), dataAuth.getQop());
        assertEquals(dataOrig.getMap().get("digest-uri"), dataAuth.getMap().get("digest-uri"));
        assertEquals(dataOrig.getResponse(), dataAuth.getResponse());
        assertEquals(dataOrig.getRealm(), dataAuth.getRealm());
        assertEquals(dataOrig.getNonce(), dataAuth.getNonce());
        assertEquals(dataOrig.getCnonce(), dataAuth.getCnonce());
        assertEquals(dataOrig.getUsername(), dataAuth.getUsername());

        assertEquals("00000001", dataOrig.getNc());
        assertEquals("00000002", dataAuth.getNc());

        assertEquals(response, origAuth);
        assertEquals(second, subAuth);
    }
}