package com.rulepilot.ingestion.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class RedisProcessingProgressNotificationsTest {

    @Test
    void publishesAndReceivesTheSameBoundedSnapshotAcrossRuntimeInstances() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(container);
        var notifications = new RedisProcessingProgressNotifications(redis, provider);
        UUID versionId = UUID.randomUUID();
        var snapshot = new ProgressSnapshot("RENDERING", 55, 4, 12, false);
        @SuppressWarnings("unchecked")
        Consumer<ProgressSnapshot> listener = mock(Consumer.class);

        notifications.publish(versionId, snapshot);
        Runnable unsubscribe = notifications.subscribe(versionId, listener);

        String channel = RedisProcessingProgressNotifications.channel(versionId);
        verify(redis).convertAndSend(channel, RedisProcessingProgressNotifications.encode(snapshot));
        var listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(container).addMessageListener(listenerCaptor.capture(), eq(new ChannelTopic(channel)));
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(
                RedisProcessingProgressNotifications.encode(snapshot).getBytes(StandardCharsets.UTF_8));
        listenerCaptor.getValue().onMessage(message, null);
        verify(listener).accept(snapshot);

        unsubscribe.run();
        unsubscribe.run();
        verify(container, times(1)).removeMessageListener(listenerCaptor.getValue(), new ChannelTopic(channel));
    }

    @Test
    void rejectsMalformedOrUnboundedNotificationPayloads() {
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("RENDERING\t55\t4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("RENDERING\t55\t4\t12\tmaybe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("RENDERING\t101\t4\t12\tfalse"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("READY\t100\t12\t12\tfalse"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("BAD\nSTAGE\t50\t4\t12\tfalse"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisProcessingProgressNotifications.decode("R".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesSubscriptionsInAWorkerRuntimeWithoutAListenerContainer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var notifications = new RedisProcessingProgressNotifications(redis, provider);

        assertThatThrownBy(() -> notifications.subscribe(UUID.randomUUID(), ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscriptions are unavailable");
    }

    @Test
    void ignoresAnInvalidRemoteMessageWithoutClosingTheSubscription() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(container);
        var notifications = new RedisProcessingProgressNotifications(redis, provider);
        @SuppressWarnings("unchecked")
        Consumer<ProgressSnapshot> listener = mock(Consumer.class);

        notifications.subscribe(UUID.randomUUID(), listener);

        var listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(container).addMessageListener(listenerCaptor.capture(), any(ChannelTopic.class));
        Message invalid = mock(Message.class);
        when(invalid.getBody()).thenReturn("invalid".getBytes(StandardCharsets.UTF_8));
        listenerCaptor.getValue().onMessage(invalid, null);
        verify(listener, times(0)).accept(any());
    }
}
