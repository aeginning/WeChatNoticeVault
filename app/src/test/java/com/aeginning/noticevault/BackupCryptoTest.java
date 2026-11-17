package com.aeginning.noticevault;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
public class BackupCryptoTest {
 @Test public void encryptsAndDecryptsBackup() throws Exception {byte[] data="notice vault backup".getBytes(StandardCharsets.UTF_8);byte[] encrypted=BackupCrypto.encrypt(data,"correct horse".toCharArray());assertArrayEquals(data,BackupCrypto.decrypt(encrypted,"correct horse".toCharArray()));}
 @Test(expected=Exception.class) public void wrongPasswordFailsAuthentication() throws Exception {byte[] encrypted=BackupCrypto.encrypt("secret".getBytes(StandardCharsets.UTF_8),"password123".toCharArray());BackupCrypto.decrypt(encrypted,"wrongpass".toCharArray());}
 @Test(expected=IllegalArgumentException.class) public void rejectsShortPassword() throws Exception {BackupCrypto.encrypt(new byte[]{1},"123".toCharArray());}
}
