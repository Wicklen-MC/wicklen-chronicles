package dev.redzumi.spirit.ai.llm;

import java.util.List;

/** Запрос к LLM-провайдеру. */
public record LlmRequest(String system, List<LlmMessage> messages, int maxTokens, double temperature) {}
