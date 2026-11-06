package dev.wicklen.chronicles.core.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Small deterministic retrieval boundary. No game, network, storage or model dependency. */
public final class KnowledgeIndex {
    public record Entry(String id, List<String> aliases, String facts, List<String> recipes) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(facts);
            aliases = List.copyOf(aliases);
            recipes = List.copyOf(recipes);
            if (!id.matches("[a-z_]+") || facts.length() > 1600 || aliases.isEmpty()
                    || recipes.size() > 3 || recipes.stream().anyMatch(r -> !r.matches("[a-z0-9_/]+"))) {
                throw new IllegalArgumentException("Invalid knowledge entry");
            }
        }
    }

    private final List<Entry> entries;

    public KnowledgeIndex(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        if (entries.size() > 128 || entries.stream().map(Entry::id).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("Invalid knowledge catalogue");
        }
    }

    public List<Entry> find(String query) {
        String normalized = normalize(query == null ? "" : query.substring(0, Math.min(query.length(), 500)));
        if (normalized.isBlank()) return List.of();
        return entries.stream().filter(entry -> score(entry, normalized) > 0)
            .sorted(Comparator.<Entry>comparingInt(entry -> score(entry, normalized)).reversed()
                .thenComparing(Entry::id))
            .limit(3).toList();
    }

    private static int score(Entry entry, String query) {
        int score = 0;
        for (String alias : entry.aliases()) {
            boolean prefix = alias.endsWith("*");
            String term = normalize(prefix ? alias.substring(0, alias.length() - 1) : alias);
            if (term.isBlank() || prefix && term.length() < 4) continue;
            String expression = "(?:^| )" + Pattern.quote(term) + (prefix ? "[\\p{L}\\p{N}]*" : "") + "(?: |$)";
            if (Pattern.compile(expression).matcher(query).find()) score += 1 + term.length();
        }
        return score;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
