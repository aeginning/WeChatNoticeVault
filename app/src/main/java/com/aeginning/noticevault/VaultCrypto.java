package com.aeginning.noticevault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-256-GCM record encryption backed by Android Keystore. Nonces are unique per encryption. */
public final class VaultCrypto {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "noticevault.records.v1";
    private static final int NONCE_BYTES = 12;
    private VaultCrypto() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }
    public static byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] nonce = c.getIV(), encrypted = c.doFinal(plain);
        return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put((byte) nonce.length).put(nonce).put(encrypted).array();
    }
    public static byte[] decrypt(byte[] packed) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(packed); int n = b.get() & 0xff;
        if (n != NONCE_BYTES || b.remaining() <= n) throw new IllegalArgumentException("Invalid encrypted record");
        byte[] nonce = new byte[n], encrypted = new byte[b.remaining() - n]; b.get(nonce); b.get(encrypted);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        return c.doFinal(encrypted);
    }
}
