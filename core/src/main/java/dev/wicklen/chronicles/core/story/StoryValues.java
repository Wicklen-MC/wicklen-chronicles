package dev.wicklen.chronicles.core.story;

import java.util.Objects;

final class StoryValues {
    private StoryValues() {}

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

    static String choiceId(String value) {
        Objects.requireNonNull(value, "choiceId");
        if (!value.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid choice ID");
        return value;
    }

    static boolean identifier(String value) {
        return value != null && value.length() <= 192 && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
