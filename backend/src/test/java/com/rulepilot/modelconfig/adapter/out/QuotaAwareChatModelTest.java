package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class QuotaAwareChatModelTest {

    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    @Test
    void preservesTheDelegatesConcreteProviderOptions() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("vision-compatible-model")
                .build();
        when(delegate.getOptions()).thenReturn(options);
        when(delegate.getDefaultOptions()).thenReturn(options);

        QuotaAwareChatModel quotaAware = model(delegate, quota);

        assertThat(quotaAware.getOptions()).isSameAs(options);
        assertThat(quotaAware.getDefaultOptions()).isSameAs(options);
    }

    @Test
    void reservesThenSettlesTheProvidersReportedTokenUsage() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        ChatResponse response = responseWithUsage(120, 35);
        UUID id = UUID.randomUUID();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        when(delegate.call(prompt)).thenReturn(response);

        model(delegate, quota).call(prompt);

        ArgumentCaptor<ModelAccountQuota.Request> request = ArgumentCaptor.forClass(ModelAccountQuota.Request.class);
        verify(quota).reserve(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().username()).isEqualTo("alice");
        org.assertj.core.api.Assertions.assertThat(request.getValue().credentialSource())
                .isEqualTo(ModelAccountQuota.CredentialSource.PLATFORM);
        verify(quota).settle(id, new ModelAccountQuota.Usage(120, 35, "SUCCESS"), NOW);
        verify(quota, never()).release(any(), any(), any());
    }

    @Test
    void releasesTheReservationWhenTheProviderFails() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID id = UUID.randomUUID();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        when(delegate.call(prompt)).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> model(delegate, quota).call(prompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider down");
        verify(quota).release(id, "PROVIDER_FAILED", NOW);
        verify(quota, never()).settle(any(), any(), any());
    }

    @Test
    void keepsTheReservationOpenForTheWholeStreamAndSettlesOnceOnCompletion() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID id = UUID.randomUUID();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PERSONAL, 16_000));
        ChatResponse streamedResponse = responseWithUsage(80, 20);
        when(delegate.stream(prompt)).thenReturn(Flux.just(streamedResponse));

        Flux<ChatResponse> stream = model(delegate, quota).stream(prompt);
        verify(quota, never()).reserve(any());
        verify(delegate, never()).stream(prompt);

        stream.blockLast();

        verify(quota).settle(id, new ModelAccountQuota.Usage(80, 20, "SUCCESS"), NOW);
        verify(quota, never()).release(any(), any(), any());
    }

    @Test
    void releasesAStreamReservationWhenTheDelegateFailsBeforeReturningAPublisher() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID id = UUID.randomUUID();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        when(delegate.stream(prompt)).thenThrow(new IllegalStateException("provider stream failed"));

        Flux<ChatResponse> stream = model(delegate, quota).stream(prompt);

        assertThatThrownBy(stream::blockLast)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider stream failed");
        verify(quota).release(id, "PROVIDER_FAILED", NOW);
        verify(quota, never()).settle(any(), any(), any());
    }

    @Test
    void releasesAStreamReservationWhenTheSubscriptionIsCancelled() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID id = UUID.randomUUID();
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        when(delegate.stream(prompt)).thenReturn(Flux.never());

        var subscription = model(delegate, quota).stream(prompt).subscribe();
        subscription.dispose();

        verify(quota).release(id, "CANCELLED", NOW);
        verify(quota, never()).settle(any(), any(), any());
    }

    @Test
    void createsAndSettlesAnIndependentReservationForEverySubscription() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(quota.reserve(any()))
                .thenReturn(new ModelAccountQuota.Reservation(
                        firstId, ModelAccountQuota.CredentialSource.PLATFORM, 16_000))
                .thenReturn(new ModelAccountQuota.Reservation(
                        secondId, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        ChatResponse streamedResponse = responseWithUsage(80, 20);
        when(delegate.stream(prompt)).thenReturn(Flux.just(streamedResponse));
        Flux<ChatResponse> stream = model(delegate, quota).stream(prompt);

        stream.blockLast();
        stream.blockLast();

        verify(quota, times(2)).reserve(any());
        verify(delegate, times(2)).stream(prompt);
        verify(quota).settle(firstId, new ModelAccountQuota.Usage(80, 20, "SUCCESS"), NOW);
        verify(quota).settle(secondId, new ModelAccountQuota.Usage(80, 20, "SUCCESS"), NOW);
        verify(quota, never()).release(any(), any(), any());
    }

    @Test
    void releasesTheReservationIfStreamSettlementCannotBePersisted() {
        ChatModel delegate = mock(ChatModel.class);
        ModelAccountQuota quota = mock(ModelAccountQuota.class);
        Prompt prompt = mock(Prompt.class);
        UUID id = UUID.randomUUID();
        ModelAccountQuota.Usage usage = new ModelAccountQuota.Usage(80, 20, "SUCCESS");
        when(quota.reserve(any())).thenReturn(new ModelAccountQuota.Reservation(
                id, ModelAccountQuota.CredentialSource.PLATFORM, 16_000));
        ChatResponse streamedResponse = responseWithUsage(80, 20);
        when(delegate.stream(prompt)).thenReturn(Flux.just(streamedResponse));
        doThrow(new IllegalStateException("quota settlement failed")).when(quota).settle(id, usage, NOW);

        assertThatThrownBy(() -> model(delegate, quota).stream(prompt).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quota settlement failed");

        verify(quota).release(id, "PROVIDER_FAILED", NOW);
    }

    private QuotaAwareChatModel model(ChatModel delegate, ModelAccountQuota quota) {
        return new QuotaAwareChatModel(
                delegate,
                quota,
                "alice",
                ModelAccountQuota.CredentialSource.PLATFORM,
                RuntimeModelConfiguration.Role.RECOMMENDATION,
                "qwen",
                "qwen3.7-plus",
                16_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ChatResponse responseWithUsage(int promptTokens, int completionTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }
}
