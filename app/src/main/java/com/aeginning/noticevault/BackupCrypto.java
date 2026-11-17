package com.aeginning.noticevault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-encrypted portable backup; independent of this device's Android Keystore. */
public final class BackupCrypto {
    private static final byte[] MAGIC={'N','V','B','K','1'}; private static final int ITERATIONS=210000;
    private BackupCrypto() {}
    public static byte[] encrypt(byte[] plain,char[] password) throws Exception {
        if(password==null||password.length<8) throw new IllegalArgumentException("备份密码至少 8 位");
        byte[] salt=new byte[16],nonce=new byte[12]; SecureRandom random=new SecureRandom(); random.nextBytes(salt); random.nextBytes(nonce);
        byte[] key=derive(password,salt);
        try {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            byte[] encrypted=c.doFinal(plain); ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(MAGIC); out.write(salt); out.write(nonce); out.write(encrypted); return out.toByteArray();
        } finally { java.util.Arrays.fill(key,(byte)0); }
    }
    public static byte[] decrypt(byte[] packed,char[] password) throws Exception {
        if(packed==null||packed.length<5+16+12+16) throw new IllegalArgumentException("备份文件无效或已损坏");
        ByteBuffer b=ByteBuffer.wrap(packed); byte[] magic=new byte[5]; b.get(magic); if(!java.util.Arrays.equals(magic,MAGIC)) throw new IllegalArgumentException("不支持的备份格式");
        byte[] salt=new byte[16],nonce=new byte[12],encrypted=new byte[b.remaining()-28]; b.get(salt); b.get(nonce); b.get(encrypted);
        byte[] key=derive(password,salt);
        try { Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); return c.doFinal(encrypted); }
        finally { java.util.Arrays.fill(key,(byte)0); }
    }
    private static byte[] derive(char[] password,byte[] salt) throws Exception { PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256); try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); } finally { spec.clearPassword(); } }
}