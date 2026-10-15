package dev.veil.control.config;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM for provider API keys at rest and in Kafka snapshots (format: base64(iv || ciphertext+tag)),
 * and SHA-256 for virtual keys. The proxy holds the same master key and decrypts only in memory.
 */
@Component
public class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public Crypto(AppProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.masterKey());
        if (raw.length != 32) throw new IllegalStateException("veil.master-key must be 32 bytes, base64-encoded");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt", e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
