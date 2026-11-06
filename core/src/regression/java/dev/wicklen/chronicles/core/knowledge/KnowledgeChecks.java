package dev.wicklen.chronicles.core.knowledge;

import java.util.List;

public final class KnowledgeChecks {
    public static void main(String[] args) {
        var honey = new KnowledgeIndex.Entry("honey", List.of("мед", "меда", "honey"), "bottle", List.of());
        var bees = new KnowledgeIndex.Entry("bees", List.of("пчел*", "bees"), "flowers", List.of());
        var index = new KnowledgeIndex(List.of(honey, bees));
        require(index.find("Где добыть МЁД?").equals(List.of(honey)), "case and ё normalization");
        require(index.find("А где достать меда?").equals(List.of(honey)), "Russian aliases");
        require(index.find("медная кирка").isEmpty(), "honey must not match copper");
        require(index.find("honeycomb").isEmpty(), "separate products must not match substrings");
        require(index.find("Пчелиное гнездо").equals(List.of(bees)), "explicit long stem matching");
        require(index.find("hello").isEmpty() && index.find("").isEmpty(), "no unrelated cards");
        require(index.find("honey bees").size() == 2, "several relevant facts");
        try {
            new KnowledgeIndex.Entry("unsafe", List.of("bad"), "data", List.of("../../private"));
            throw new AssertionError("unsafe resource path accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: bilingual knowledge retrieval, product boundaries and bounded source paths");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
