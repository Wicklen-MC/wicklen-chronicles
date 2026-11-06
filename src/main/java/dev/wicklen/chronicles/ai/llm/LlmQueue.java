package dev.wicklen.chronicles.ai.llm;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded asynchronous dispatch, shared by dialogue, chronicles and story planning. */
public final class LlmQueue implements AutoCloseable {
    private record Job(Supplier<CompletableFuture<String>> request, CompletableFuture<String> result, long enqueuedAt) {}
    private final int parallelism, capacity, hourlyRequests, hourlyOutputTokens;
    private final LongSupplier clock;
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private final Set<CompletableFuture<String>> running = new HashSet<>();
    private long windowStarted;
    private int requests, outputTokens;
    private boolean closed;

    public LlmQueue(int parallelism, int capacity, int hourlyRequests, int hourlyOutputTokens, LongSupplier clock) {
        if (parallelism < 1 || capacity < 0 || hourlyRequests < 1 || hourlyOutputTokens < 1)
            throw new IllegalArgumentException("Invalid LLM queue limits");
        this.parallelism = parallelism;
        this.capacity = capacity;
        this.hourlyRequests = hourlyRequests;
        this.hourlyOutputTokens = hourlyOutputTokens;
        this.clock = clock;
        this.windowStarted = clock.getAsLong();
    }

    /** Reserves the requested maximum output tokens; failed requests still consume the budget. */
    public synchronized CompletableFuture<String> submit(int maxTokens, Supplier<CompletableFuture<String>> request) {
        if (closed) return rejected("World AI queue is closed");
        if (maxTokens < 1) return rejected("Invalid token reservation");
        long now = clock.getAsLong();
        if (now - windowStarted >= 3_600_000L) {
            windowStarted = now;
            requests = 0;
            outputTokens = 0;
        }
        if (requests >= hourlyRequests || maxTokens > hourlyOutputTokens - outputTokens)
            return rejected("Server AI hourly budget exhausted");
        if (running.size() >= parallelism && waiting.size() >= capacity)
            return rejected("Server AI queue is full");
        requests++;
        outputTokens += maxTokens;
        var result = new CompletableFuture<String>();
        waiting.addLast(new Job(request, result, now));
        pump();
        return result;
    }

    private void pump() {
        while (!closed && running.size() < parallelism && !waiting.isEmpty()) {
            Job job = waiting.removeFirst();
            if (job.result().isCancelled()) continue;
            if (clock.getAsLong() - job.enqueuedAt() > 30_000) {
                // Completion is off-monitor just like normal transport completion.
                CompletableFuture.runAsync(() -> job.result().completeExceptionally(
                    new RejectedExecutionException("AI request expired while queued")));
                continue;
            }
            // Reserve before invoking: immediate completion may enter this monitor again.
            running.add(job.result());
            CompletableFuture.supplyAsync(job.request())
                .thenCompose(request -> request).orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((answer, failure) -> {
                    synchronized (LlmQueue.this) {
                        running.remove(job.result());
                        pump();
                    }
                    // Never execute a consumer while holding the dispatch monitor.
                    if (failure == null) job.result().complete(answer);
                    else job.result().completeExceptionally(failure);
                });
        }
    }

    private static CompletableFuture<String> rejected(String message) {
        return CompletableFuture.failedFuture(new RejectedExecutionException(message));
    }

    @Override
    public synchronized void close() {
        closed = true;
        var failure = new RejectedExecutionException("World AI queue stopped");
        for (Job job : waiting) job.result().completeExceptionally(failure);
        waiting.clear();
        for (var result : Set.copyOf(running)) result.completeExceptionally(failure);
        running.clear();
    }
}
