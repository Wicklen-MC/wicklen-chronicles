package dev.wicklen.chronicles.ai.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Провайдер LLM. Реализации обязаны быть неблокирующими (async HTTP)
 * и НИКОГДА не вызываться из тик-потока напрямую — только через SpiritAi.
 */
public interface LlmProvider {
    /** Короткий id для логов и конфигурации: "deepseek", "openai", "ollama". */
    String id();

    /** Выполнить запрос; future завершается текстом ответа или исключением. */
    CompletableFuture<String> complete(LlmRequest request);
}
