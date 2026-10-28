package io.pgvip.core.notify;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts stored secrets (the SMTP password) with AES-256-GCM. The key lives in {@code secret.key} in the watcher
 * data directory, created on first start with mode 0600. In Raft mode copy the same key file to every watcher.
 */
public final class SecretBox {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** Loads the key file, creating it if missing. */
    public static SecretBox fromKeyFile(Path file) {
        try {
            if (!Files.exists(file)) {
                byte[] k = new byte[32];
                RANDOM.nextBytes(k);
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, Base64.getEncoder().encodeToString(k) + "\n", StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
            }
            byte[] k = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
            return new SecretBox(k);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read key file " + file, e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        if (!secret.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not an encrypted value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(secret.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot decrypt the stored secret; is secret.key the same on every"
                    + " watcher?", e);
        }
    }
}
