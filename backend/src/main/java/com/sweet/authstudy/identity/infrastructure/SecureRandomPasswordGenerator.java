package com.sweet.authstudy.identity.infrastructure;

import com.sweet.authstudy.identity.application.PasswordGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecureRandomPasswordGenerator implements PasswordGenerator {

    private static final int PASSWORD_LENGTH = 16;
    private static final char[] UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWERCASE = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%^&*".toCharArray();
    private static final char[] ALL =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generateTemporaryPassword() {
        char[] password = new char[PASSWORD_LENGTH];
        password[0] = randomCharacter(UPPERCASE);
        password[1] = randomCharacter(LOWERCASE);
        password[2] = randomCharacter(DIGITS);
        password[3] = randomCharacter(SYMBOLS);
        for (int index = 4; index < password.length; index++) {
            password[index] = randomCharacter(ALL);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int target = secureRandom.nextInt(index + 1);
            char value = password[index];
            password[index] = password[target];
            password[target] = value;
        }
        return new String(password);
    }

    private char randomCharacter(char[] characters) {
        return characters[secureRandom.nextInt(characters.length)];
    }
}
