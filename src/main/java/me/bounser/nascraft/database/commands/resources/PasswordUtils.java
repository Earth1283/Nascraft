package me.bounser.nascraft.database.commands.resources;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordUtils {

    private static final int LOG_ROUNDS = 10;

    private PasswordUtils() {}

    public static String hashPassword(String plaintext) {
        if (plaintext == null || plaintext.isEmpty())
            throw new IllegalArgumentException("Password must not be null or empty");
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(LOG_ROUNDS));
    }

    public static boolean checkPassword(String plaintext, String hash) {
        if (plaintext == null || plaintext.isEmpty() || hash == null || hash.isEmpty()) return false;
        try {
            return BCrypt.checkpw(plaintext, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
