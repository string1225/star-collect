package com.starcollect.app;

import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class ParentPin {
    private ParentPin() {}
    public static void set(StarState state, String pin) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        state.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP);
        state.pinHash = hash(pin, salt);
    }
    public static boolean verify(StarState state, String pin) {
        return MessageDigest.isEqual(state.pinHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hash(pin, Base64.decode(state.pinSalt, Base64.NO_WRAP)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String hash(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120_000, 256);
        try { return Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), Base64.NO_WRAP); }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
