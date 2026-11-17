package com.aeginning.noticevault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-encrypted portable backup; independent of this device's Android Keystore. */
public final class BackupCrypto {
    private static final byte[] MAGIC = {'N', 'V', 'B', 'K', '1'};
    private static final int ITERATIONS = 210000;

    private BackupCrypto() { }

    public static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("备份密码至少 8 位");
        }
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] key = derive(password, salt);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            encrypted = cipher.doFinal(plain);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(MAGIC);
            output.write(salt);
            output.write(nonce);
            output.write(encrypted);
            return output.toByteArray();
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(nonce, (byte) 0);
            if (encrypted != null) java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static byte[] decrypt(byte[] packed, char[] password) throws Exception {
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("备份密码至少 8 位");
        }
        if (packed == null || packed.length < 5 + 16 + 12 + 16) {
            throw new IllegalArgumentException("备份文件无效或已损坏");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        byte[] magic = new byte[5];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("不支持的备份格式");
        }
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        byte[] encrypted = new byte[buffer.remaining() - 28];
        buffer.get(salt);
        buffer.get(nonce);
        buffer.get(encrypted);
        byte[] key = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(encrypted);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(nonce, (byte) 0);
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
