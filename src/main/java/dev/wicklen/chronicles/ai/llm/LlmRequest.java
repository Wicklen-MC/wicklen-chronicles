package dev.wicklen.chronicles.ai.llm;

import java.util.List;

/** Запрос к LLM-провайдеру. */
public record LlmRequest(String system, List<LlmMessage> messages, int maxTokens, double temperature, boolean jsonObject) {
    public LlmRequest(String system, List<LlmMessage> messages, int maxTokens, double temperature) {
        this(system, messages, maxTokens, temperature, false);
    }
    public LlmRequest { messages = List.copyOf(messages); }
}
