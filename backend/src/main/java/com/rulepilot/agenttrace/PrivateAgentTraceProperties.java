package com.rulepilot.agenttrace;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("rulepilot.private-agent-trace")
public class PrivateAgentTraceProperties {

    private static final int MAXIMUM_ALLOWED_USERS = 32;

    private boolean enabled;
    private Duration captureDuration = Duration.ofHours(2);
    private Duration retention = Duration.ofHours(24);
    private DataSize maxBytes = DataSize.ofMegabytes(32);
    private DataSize maxEventBytes = DataSize.ofKilobytes(512);
    private String encryptionKey = "";
    private short encryptionKeyVersion = 1;
    private String redisPrefix = "rulepilot:private-agent-trace:";
    private List<String> allowedUsers = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCaptureDuration() {
        return captureDuration;
    }

    public void setCaptureDuration(Duration captureDuration) {
        this.captureDuration = captureDuration;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public DataSize getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(DataSize maxBytes) {
        this.maxBytes = maxBytes;
    }

    public DataSize getMaxEventBytes() {
        return maxEventBytes;
    }

    public void setMaxEventBytes(DataSize maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
    }

    public short getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(short encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public void setRedisPrefix(String redisPrefix) {
        this.redisPrefix = redisPrefix;
    }

    public List<String> getAllowedUsers() {
        return allowedUsers;
    }

    public void setAllowedUsers(List<String> allowedUsers) {
        this.allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    }

    boolean allows(String ownerUsername) {
        String checked = ownerUsername == null ? "" : ownerUsername.strip();
        return !checked.isEmpty() && allowedUsers.contains(checked);
    }

    void validate() {
        if (captureDuration == null || captureDuration.isZero() || captureDuration.isNegative()
                || retention == null || retention.isZero() || retention.isNegative()
                || captureDuration.compareTo(retention) >= 0
                || maxBytes == null || maxBytes.toBytes() < 1
                || maxEventBytes == null || maxEventBytes.toBytes() < 1
                || maxEventBytes.toBytes() > maxBytes.toBytes()
                || encryptionKeyVersion < 1
                || redisPrefix == null || redisPrefix.isBlank() || redisPrefix.length() > 120
                || allowedUsers == null || allowedUsers.size() > MAXIMUM_ALLOWED_USERS) {
            throw new IllegalArgumentException("private agent trace configuration is invalid");
        }
        LinkedHashSet<String> normalizedUsers = new LinkedHashSet<>();
        for (String user : allowedUsers) {
            String checked = user == null ? "" : user.strip();
            if (checked.isEmpty() || checked.length() > 120) {
                throw new IllegalArgumentException("private agent trace configuration is invalid");
            }
            normalizedUsers.add(checked);
        }
        redisPrefix = redisPrefix.strip();
        allowedUsers = List.copyOf(normalizedUsers);
    }
}
