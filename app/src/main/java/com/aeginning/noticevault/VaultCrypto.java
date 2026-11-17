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

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
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

    public static byte[] encrypt(byte[] plain) throws Exception {
        byte[] compressed = PayloadCompression.compress(plain);
        boolean useCompression = compressed.length < plain.length;
        byte[] input = useCompression ? compressed : plain;
        byte[] nonce = null;
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
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
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
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
