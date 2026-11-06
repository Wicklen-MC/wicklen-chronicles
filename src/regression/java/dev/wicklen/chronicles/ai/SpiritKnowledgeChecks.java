package dev.wicklen.chronicles.ai;

import java.util.List;

/** Real bundled catalogue checks without a running world or external model. */
public final class SpiritKnowledgeChecks {
    public static void main(String[] args) {
        var honey = SpiritGameKnowledge.find("где добыть мёд?", List.of());
        require(honey.size() == 1 && honey.getFirst().id().equals("honey"), "Russian honey query");
        require(honey.getFirst().facts().contains("FULL") && honey.getFirst().facts().contains("campfire"), "fullness and calm harvesting are part of the verified mechanic");
        require(SpiritGameKnowledge.find("медная кирка", List.of()).isEmpty(), "copper is not honey");
        require(SpiritGameKnowledge.find("картошка", List.of()).isEmpty(), "potatoes are not maps");
        require(SpiritGameKnowledge.find("honeycomb", List.of()).getFirst().id().equals("honeycomb"), "honeycomb remains a separate product");
        require(SpiritGameKnowledge.find("А как его собрать?", List.of(new SpiritMemoryStore.Turn("Где добыть мед?", "answer"))).equals(honey), "immediate pronoun follow-up");
        require(SpiritGameKnowledge.find("Привет", List.of(new SpiritMemoryStore.Turn("honey", "answer"))).isEmpty(), "ordinary dialogue does not carry unrelated help cards");
        require(SpiritGameKnowledge.find("соты", List.of()).getFirst().recipes().contains("shears"), "honeycomb retrieval asks for the correct tool recipe");
        require(SpiritAi.groundingRules().contains("Это не запрещает объяснять общие ванильные механики"), "unknown world locations do not imply a blanket refusal of gameplay help");
        System.out.println("PASS: real vanilla catalogue, Russian follow-ups and unrelated-topic boundaries");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
