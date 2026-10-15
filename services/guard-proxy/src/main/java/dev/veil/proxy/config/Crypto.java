package dev.veil.proxy.config;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Decrypts provider API keys (AES-256-GCM, written by the control plane), hashes virtual keys, fingerprints values. */
@Component
public class Crypto {

    private final SecretKeySpec key;

    public Crypto(ProxyProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.masterKey());
        if (raw.length != 32) throw new IllegalStateException("veil.master-key must be 32 bytes, base64-encoded");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a provider key; is VEIL_MASTER_KEY the same as the control plane's?", e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First 12 hex characters of HMAC-SHA256(salt, value): correlates repeats without storing the value. */
    public static String fingerprint(String salt, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
