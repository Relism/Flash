package dev.relism.flash.ext.security.form;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** One-way password hashing. {@link #pbkdf2()} unless another is configured. */
public interface PasswordEncoder {

    String encode(CharSequence password);

    /** Constant-time; {@code false} for anything this encoder did not produce. */
    boolean matches(CharSequence password, String encoded);

    /** PBKDF2-HMAC-SHA256, 600,000 iterations (OWASP 2023), a random 16-byte salt — JDK only. */
    static PasswordEncoder pbkdf2() {
        return Pbkdf2.INSTANCE;
    }

    enum Pbkdf2 implements PasswordEncoder {
        INSTANCE;

        private static final int ITERATIONS = 600_000;
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public String encode(CharSequence password) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
        }

        @Override
        public boolean matches(CharSequence password, String encoded) {
            String[] parts = encoded == null ? new String[0] : encoded.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(derive(password, salt, Integer.parseInt(parts[1])), Base64.getDecoder().decode(parts[3]));
        }

        private static byte[] derive(CharSequence password, byte[] salt, int iterations) {
            char[] chars = password.toString().toCharArray();
            PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            } finally {
                spec.clearPassword();
                java.util.Arrays.fill(chars, '\0');
            }
        }
    }
}
