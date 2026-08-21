package com.rulepilot.modelconfig.adapter.out;

import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class QuotaAwareChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ModelAccountQuota quota;
    private final String username;
    private final ModelAccountQuota.CredentialSource credentialSource;
    private final RuntimeModelConfiguration.Role role;
    private final String provider;
    private final String model;
    private final long reservationTokens;
    private final Clock clock;

    public QuotaAwareChatModel(
            ChatModel delegate,
            ModelAccountQuota quota,
            String username,
            ModelAccountQuota.CredentialSource credentialSource,
            RuntimeModelConfiguration.Role role,
            String provider,
            String model,
            long reservationTokens,
            Clock clock) {
        this.delegate = delegate;
        this.quota = quota;
        this.username = username;
        this.credentialSource = credentialSource;
        this.role = role;
        this.provider = provider;
        this.model = model;
        this.reservationTokens = reservationTokens;
        this.clock = clock;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelAccountQuota.Reservation reservation = reserve();
        try {
            ChatResponse response = delegate.call(prompt);
            TokenUsage usage = responseUsage(response, promptCharacters(prompt), responseCharacters(response));
            quota.settle(
                    reservation.id(),
                    new ModelAccountQuota.Usage(usage.prompt(), usage.completion(), "SUCCESS"),
                    Instant.now(clock));
            return response;
        } catch (RuntimeException exception) {
            quota.release(reservation.id(), "PROVIDER_FAILED", Instant.now(clock));
            throw exception;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ModelAccountQuota.Reservation reservation = reserve();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicLong reportedPromptTokens = new AtomicLong(-1);
        AtomicLong reportedCompletionTokens = new AtomicLong(-1);
        AtomicLong completionCharacters = new AtomicLong();
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    completionCharacters.addAndGet(responseCharacters(response));
                    Usage usage = response == null || response.getMetadata() == null
                            ? null
                            : response.getMetadata().getUsage();
                    if (usage != null && usage.getPromptTokens() != null) {
                        reportedPromptTokens.set(usage.getPromptTokens());
                    }
                    if (usage != null && usage.getCompletionTokens() != null) {
                        reportedCompletionTokens.set(usage.getCompletionTokens());
                    }
                })
                .doOnComplete(() -> settleStream(
                        reservation,
                        finished,
                        reportedPromptTokens.get() >= 0
                                ? reportedPromptTokens.get()
                                : estimateTokens(promptCharacters(prompt)),
                        reportedCompletionTokens.get() >= 0
                                ? reportedCompletionTokens.get()
                                : estimateTokens(completionCharacters.get())))
                .doOnError(ignored -> releaseStream(reservation, finished, "PROVIDER_FAILED"))
                .doOnCancel(() -> releaseStream(reservation, finished, "CANCELLED"));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ModelAccountQuota.Reservation reserve() {
        return quota.reserve(new ModelAccountQuota.Request(
                username,
                credentialSource,
                role,
                provider,
                model,
                role.name().toLowerCase(java.util.Locale.ROOT),
                reservationTokens,
                Instant.now(clock)));
    }

    private void settleStream(
            ModelAccountQuota.Reservation reservation,
            AtomicBoolean finished,
            long promptTokens,
            long completionTokens) {
        if (!finished.compareAndSet(false, true)) return;
        quota.settle(
                reservation.id(),
                new ModelAccountQuota.Usage(promptTokens, completionTokens, "SUCCESS"),
                Instant.now(clock));
    }

    private void releaseStream(
            ModelAccountQuota.Reservation reservation, AtomicBoolean finished, String outcome) {
        if (!finished.compareAndSet(false, true)) return;
        quota.release(reservation.id(), outcome, Instant.now(clock));
    }

    private TokenUsage responseUsage(ChatResponse response, long promptCharacters, long completionCharacters) {
        Usage nativeUsage = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        long prompt = nativeUsage == null || nativeUsage.getPromptTokens() == null
                ? estimateTokens(promptCharacters)
                : nativeUsage.getPromptTokens();
        long completion = nativeUsage == null || nativeUsage.getCompletionTokens() == null
                ? estimateTokens(completionCharacters)
                : nativeUsage.getCompletionTokens();
        return new TokenUsage(prompt, completion);
    }

    private long promptCharacters(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) return 0;
        return prompt.getInstructions().stream()
                .map(message -> message == null || message.getText() == null ? 0 : message.getText().length())
                .mapToLong(Integer::longValue)
                .sum();
    }

    private long responseCharacters(ChatResponse response) {
        if (response == null || response.getResults() == null) return 0;
        return response.getResults().stream()
                .mapToLong(generation -> generation == null
                                || generation.getOutput() == null
                                || generation.getOutput().getText() == null
                        ? 0
                        : generation.getOutput().getText().length())
                .sum();
    }

    private long estimateTokens(long characters) {
        return Math.max(1, (characters + 3) / 4);
    }

    private record TokenUsage(long prompt, long completion) {}
}
