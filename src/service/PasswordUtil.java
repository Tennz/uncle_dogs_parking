package service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordUtil {
    private static final int ITERATIONS=210_000;
    private static final int KEY_BITS=256;
    private static final SecureRandom RANDOM=new SecureRandom();
    private PasswordUtil(){}
    public record Hash(String salt,String value){}
    public static Hash hash(char[] password){byte[] salt=new byte[16];RANDOM.nextBytes(salt);return new Hash(Base64.getEncoder().encodeToString(salt),derive(password,salt));}
    public static boolean verify(char[] password,String saltText,String expected){
        try {
            byte[] salt = Base64.getDecoder().decode(saltText);
            // [ENCAPSULATION] Compare the derived key bytes with the decoded stored hash.
            // Do not Base64-decode raw key bytes: that made valid logins fail.
            byte[] actual = deriveBytes(password, salt);
            byte[] wanted = Base64.getDecoder().decode(expected);
            return MessageDigest.isEqual(actual, wanted);
        } catch (Exception e) {
            return false;
        }
    }
    private static String derive(char[] password,byte[] salt){return Base64.getEncoder().encodeToString(deriveBytes(password,salt));}
    private static byte[] deriveBytes(char[] password,byte[] salt){PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,KEY_BITS);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(Exception e){throw new IllegalStateException("Password hashing is unavailable.",e);}finally{spec.clearPassword();}}
}
