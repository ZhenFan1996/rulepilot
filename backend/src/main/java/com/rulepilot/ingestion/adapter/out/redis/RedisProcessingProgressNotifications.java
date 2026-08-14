package com.rulepilot.ingestion.adapter.out.redis;

import com.rulepilot.ingestion.application.ProcessingProgressNotifications;
import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RedisProcessingProgressNotifications implements ProcessingProgressNotifications {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisProcessingProgressNotifications.class);
    private static final String CHANNEL_PREFIX = "rulepilot:ingestion-progress-events:";
    private static final int MAX_PAYLOAD_LENGTH = 256;

    private final StringRedisTemplate redis;
    private final ObjectProvider<RedisMessageListenerContainer> listeners;

    public RedisProcessingProgressNotifications(
            StringRedisTemplate redis,
            @Qualifier("processingProgressRedisMessageListenerContainer")
                    ObjectProvider<RedisMessageListenerContainer> listeners) {
        this.redis = redis;
        this.listeners = listeners;
    }

    @Override
    public void publish(UUID versionId, ProgressSnapshot snapshot) {
        redis.convertAndSend(channel(versionId), encode(snapshot));
    }

    @Override
    public Runnable subscribe(UUID versionId, Consumer<ProgressSnapshot> listener) {
        RedisMessageListenerContainer container = listeners.getIfAvailable();
        if (container == null) {
            throw new IllegalStateException("processing progress subscriptions are unavailable in this runtime");
        }
        var topic = new ChannelTopic(channel(versionId));
        var active = new AtomicBoolean(true);
        MessageListener messageListener = (message, ignored) -> {
            if (!active.get()) return;
            try {
                listener.accept(decode(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (RuntimeException invalidMessage) {
                LOGGER.warn("Ignored an invalid cross-runtime processing progress notification");
            }
        };
        container.addMessageListener(messageListener, topic);
        return () -> {
            if (active.compareAndSet(true, false)) {
                container.removeMessageListener(messageListener, topic);
            }
        };
    }

    static String channel(UUID versionId) {
        if (versionId == null) throw new IllegalArgumentException("document version is required");
        return CHANNEL_PREFIX + versionId;
    }

    static String encode(ProgressSnapshot snapshot) {
        return String.join(
                "\t",
                snapshot.stage(),
                Integer.toString(snapshot.percentage()),
                Integer.toString(snapshot.processedPages()),
                Integer.toString(snapshot.totalPages()),
                Boolean.toString(snapshot.complete()));
    }

    static ProgressSnapshot decode(String payload) {
        String[] fields = payload == null || payload.length() > MAX_PAYLOAD_LENGTH
                ? new String[0]
                : payload.split("\t", -1);
        if (fields.length != 5 || !"true".equals(fields[4]) && !"false".equals(fields[4])) {
            throw new IllegalArgumentException("processing progress notification is invalid");
        }
        try {
            return new ProgressSnapshot(
                    fields[0],
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]),
                    Boolean.parseBoolean(fields[4]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("processing progress notification is invalid", exception);
        }
    }
}
