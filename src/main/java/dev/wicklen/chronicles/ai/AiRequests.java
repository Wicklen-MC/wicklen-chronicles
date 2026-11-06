package dev.wicklen.chronicles.ai;

import dev.wicklen.chronicles.ai.llm.LlmProvider;
import dev.wicklen.chronicles.ai.llm.LlmQueue;
import dev.wicklen.chronicles.ai.llm.LlmRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A new world session always receives a new queue and budget. */
public final class AiRequests {
    private static UUID session;
    private static LlmQueue queue;
    private AiRequests() {}

    public static synchronized CompletableFuture<String> complete(LlmProvider provider, LlmRequest request) {
        UUID current = WorldStorageContext.sessionId();
        if (!WorldStorageContext.matchesSession(current))
            return CompletableFuture.failedFuture(new IllegalStateException("World is stopping"));
        if (!current.equals(session)) {
            reset();
            var cfg = SpiritConfig.get();
            queue = new LlmQueue(cfg.aiParallelRequests, cfg.aiQueueCapacity,
                cfg.aiRequestsPerHour, cfg.aiOutputTokensPerHour, System::currentTimeMillis);
            session = current;
        }
        return queue.submit(request.maxTokens(), () -> provider.complete(request));
    }

    public static synchronized void reset() {
        if (queue != null) queue.close();
        queue = null;
        session = null;
    }
}
