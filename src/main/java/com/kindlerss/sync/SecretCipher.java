package com.kindlerss.sync;

import com.kindlerss.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM encryption for provider passwords stored in the database. */
@Component
public class SecretCipher {

    private static final byte[] SALT = "extrablatt-sync-secret-v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties properties) {
        String secret = properties.sync().encryptionKey();
        this.key = secret == null ? null : derive(secret);
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt secret (was the encryption key changed?)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Sync encryption key is not configured");
        }
    }

    private static SecretKeySpec derive(String secret) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] bytes = factory.generateSecret(new PBEKeySpec(secret.toCharArray(), SALT, 100_000, 256)).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive sync key", e);
        }
    }
}
