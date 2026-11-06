package dev.wicklen.chronicles.core.discovery;

import java.util.Objects;
import java.util.regex.Pattern;

final class DiscoveryValues {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private DiscoveryValues() {}

    static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > 192 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    static String text(String value, String name, int maximum, boolean multiline) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException("Invalid " + name + " length");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character) && !(multiline && (character == '\n' || character == '\t'))) {
                throw new IllegalArgumentException("Control character in " + name);
            }
        }
        return value;
    }
}
