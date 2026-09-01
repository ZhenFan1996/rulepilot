package com.rulepilot.modelconfig.adapter.out;

import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.time.Clock;
import java.time.Instant;
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
        ReservationLifecycle lifecycle = new ReservationLifecycle(reserve());
        try {
            ChatResponse response = delegate.call(prompt);
            TokenUsage usage = responseUsage(response, promptCharacters(prompt), responseCharacters(response));
            lifecycle.settle(usage.prompt(), usage.completion());
            return response;
        } catch (RuntimeException exception) {
            releaseAfterFailure(lifecycle, exception, "PROVIDER_FAILED");
            throw exception;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            ReservationLifecycle lifecycle = new ReservationLifecycle(reserve());
            AtomicLong reportedPromptTokens = new AtomicLong(-1);
            AtomicLong reportedCompletionTokens = new AtomicLong(-1);
            AtomicLong completionCharacters = new AtomicLong();
            return Flux.defer(() -> {
                        Flux<ChatResponse> provider = delegate.stream(prompt);
                        return provider == null
                                ? Flux.error(new IllegalStateException("chat model returned no stream"))
                                : provider;
                    })
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
                    .doOnComplete(() -> lifecycle.settle(
                            reportedPromptTokens.get() >= 0
                                    ? reportedPromptTokens.get()
                                    : estimateTokens(promptCharacters(prompt)),
                            reportedCompletionTokens.get() >= 0
                                    ? reportedCompletionTokens.get()
                                    : estimateTokens(completionCharacters.get())))
                    .doOnError(failure -> releaseAfterFailure(lifecycle, failure, "PROVIDER_FAILED"))
                    .doFinally(signal -> {
                        try {
                            lifecycle.release(signal == reactor.core.publisher.SignalType.CANCEL
                                    ? "CANCELLED"
                                    : "PROVIDER_FAILED");
                        } catch (RuntimeException ignored) {
                            // The persistent reservation timeout is the recovery boundary when cleanup storage fails.
                        }
                    });
        });
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

    private void releaseAfterFailure(ReservationLifecycle lifecycle, Throwable failure, String outcome) {
        try {
            lifecycle.release(outcome);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
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

    private final class ReservationLifecycle {
        private final ModelAccountQuota.Reservation reservation;
        private boolean terminal;

        private ReservationLifecycle(ModelAccountQuota.Reservation reservation) {
            this.reservation = reservation;
        }

        private synchronized void settle(long promptTokens, long completionTokens) {
            if (terminal) return;
            quota.settle(
                    reservation.id(),
                    new ModelAccountQuota.Usage(promptTokens, completionTokens, "SUCCESS"),
                    Instant.now(clock));
            terminal = true;
        }

        private synchronized void release(String outcome) {
            if (terminal) return;
            quota.release(reservation.id(), outcome, Instant.now(clock));
            terminal = true;
        }
    }

    private record TokenUsage(long prompt, long completion) {}
}
