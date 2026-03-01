package com.mimecast.robin.user.service;

import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

/**
 * Generates DKIM keypairs and DNS-safe public key values.
 */
public class DkimKeyGenerator {

    private static final String BC_PROVIDER = "BC";
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        if (Security.getProvider(BC_PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Generates an RSA 2048-bit keypair for DKIM.
     *
     * @return generated key material
     */
    public GeneratedKey generateRsa2048() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BC_PROVIDER);
            generator.initialize(2048, RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            String dnsPublicKey = encodeRsaPublicKeyForDns(keyPair.getPublic());
            return new GeneratedKey(
                    "RSA_2048",
                    keyPair,
                    toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                    toPem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                    dnsPublicKey
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate RSA DKIM keypair", e);
        }
    }

    /**
     * Generates an Ed25519 keypair for DKIM.
     *
     * @return generated key material
     */
    public GeneratedKey generateEd25519() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", BC_PROVIDER);
            generator.initialize(255, RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            String dnsPublicKey = encodeEd25519PublicKeyForDns(keyPair.getPublic());
            return new GeneratedKey(
                    "ED25519",
                    keyPair,
                    toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                    toPem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                    dnsPublicKey
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate Ed25519 DKIM keypair", e);
        }
    }

    private static String encodeRsaPublicKeyForDns(PublicKey publicKey) {
        try {
            AsymmetricKeyParameter parameter = PublicKeyFactory.createKey(publicKey.getEncoded());
            if (!(parameter instanceof RSAKeyParameters rsaKeyParameters)) {
                throw new IllegalStateException("Public key is not RSA");
            }
            RSAPublicKey pkcs1 = new RSAPublicKey(rsaKeyParameters.getModulus(), rsaKeyParameters.getExponent());
            return Base64.getEncoder().encodeToString(pkcs1.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode RSA public key for DKIM DNS record", e);
        }
    }

    private static String encodeEd25519PublicKeyForDns(PublicKey publicKey) {
        try {
            SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
            byte[] raw = subjectPublicKeyInfo.getPublicKeyData().getBytes();
            if (raw.length != 32) {
                throw new IllegalStateException("Unexpected Ed25519 public key length: " + raw.length);
            }
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode Ed25519 public key for DKIM DNS record", e);
        }
    }

    private static String toPem(String type, byte[] derEncoded) {
        String payload = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derEncoded);
        return "-----BEGIN " + type + "-----\n"
                + payload
                + "\n-----END " + type + "-----\n";
    }

    /**
     * Generated DKIM key material.
     *
     * @param algorithm    generated algorithm identifier
     * @param keyPair      keypair object
     * @param privateKeyPem PKCS#8 private key in PEM format
     * @param publicKeyPem  public key in PEM format
     * @param dnsPublicKey  DNS-safe base64 public key value for p=
     */
    public record GeneratedKey(
            String algorithm,
            KeyPair keyPair,
            String privateKeyPem,
            String publicKeyPem,
            String dnsPublicKey
    ) {
    }
}
