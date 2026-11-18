package com.aeginning.noticevault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM record encryption backed by Android Keystore.
 * New payloads use a high-bit header flag for bounded GZIP compression;
 * legacy nonce-length headers remain readable without a database migration.
 */
public final class VaultCrypto {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "noticevault.records.v1";
    private static final int NONCE_BYTES = 12;
    private static final int COMPRESSED_FLAG = 0x80;
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024;

    private VaultCrypto() { }

    private static SecretKey key(boolean createIfMissing) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            try {
                SecretKey existing = (SecretKey) keyStore.getKey(ALIAS, null);
                if (existing != null) return existing;
            } catch (Exception error) {
                throw new KeyUnavailableException(
                        "本地加密密钥无法读取；为保护旧记录，应用不会自动替换密钥。", error);
            }
            throw new KeyUnavailableException(
                    "本地加密密钥不存在或不可用；为保护旧记录，应用不会自动替换密钥。");
        }
        if (!createIfMissing) {
            throw new KeyUnavailableException(
                    "本地加密密钥缺失；已阻止自动创建新密钥，以免旧记录永久无法解密。");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    /** Returns whether the existing alias is present without creating or replacing a key. */
    public static boolean hasKeyAlias() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE);
        keyStore.load(null);
        return keyStore.containsAlias(ALIAS);
    }

    public static final class KeyUnavailableException extends Exception {
        public KeyUnavailableException(String message) {
            super(message);
        }

        public KeyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static byte[] encrypt(byte[] plain, boolean allowKeyCreation) throws Exception {
        if (plain == null) throw new IllegalArgumentException("Plaintext cannot be null");
        byte[] compressed = PayloadCompression.compress(plain);
        boolean useCompression = compressed.length < plain.length;
        byte[] input = useCompression ? compressed : plain;
        byte[] nonce = null;
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key(allowKeyCreation));
            } catch (java.security.InvalidKeyException error) {
                throw new KeyUnavailableException(
                        "本地加密密钥不可用；应用已拒绝替换密钥。", error);
            }
            nonce = cipher.getIV();
            encrypted = cipher.doFinal(input);
            int header = nonce.length | (useCompression ? COMPRESSED_FLAG : 0);
            return ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                    .put((byte) header).put(nonce).put(encrypted).array();
        } finally {
            java.util.Arrays.fill(compressed, (byte) 0);
            if (nonce != null) java.util.Arrays.fill(nonce, (byte) 0);
            if (encrypted != null) java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static byte[] decrypt(byte[] packed) throws Exception {
        if (packed == null || packed.length < 1 + NONCE_BYTES + 16) {
            throw new IllegalArgumentException("Invalid encrypted record");
        }

        ByteBuffer buffer = ByteBuffer.wrap(packed);
        int header = buffer.get() & 0xff;
        boolean compressed = (header & COMPRESSED_FLAG) != 0;
        int nonceLength = header & 0x7f;
        if (nonceLength != NONCE_BYTES || buffer.remaining() <= nonceLength + 15) {
            throw new IllegalArgumentException("Invalid encrypted record");
        }

        byte[] nonce = new byte[nonceLength];
        byte[] encrypted = new byte[buffer.remaining() - nonceLength];
        byte[] decrypted = null;
        buffer.get(nonce);
        buffer.get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, nonce));
            } catch (java.security.InvalidKeyException error) {
                throw new KeyUnavailableException(
                        "本地加密密钥不可用；旧记录保持原样，未创建替代密钥。", error);
            }
            decrypted = cipher.doFinal(encrypted);
            if (!compressed) return decrypted;

            try {
                return PayloadCompression.decompress(decrypted, MAX_DECOMPRESSED_BYTES);
            } finally {
                java.util.Arrays.fill(decrypted, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
            java.util.Arrays.fill(nonce, (byte) 0);
        }
    }
}
