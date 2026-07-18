package com.rulepilot.modelconfig;

import com.rulepilot.modelconfig.ModelProviderProperties.Provider;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ModelProviderProperties.class)
public class RuntimeModelConfiguration {

    public enum Role {
        TEACHING,
        ANSWER,
        CRITIC
    }

    private static final List<String> SUPPORTED = List.of("gemini", "openai", "deepseek", "compatible");

    private final ChatModelFactory factory;
    private final AtomicReference<State> state;

    public RuntimeModelConfiguration(
            ChatModelFactory factory,
            ModelProviderProperties properties,
            @Value("${rulepilot.teaching.provider:fake}") String teachingAdapter,
            @Value("${rulepilot.teaching.model-provider:gemini}") String teachingProvider,
            @Value("${rulepilot.answer.provider:fake}") String answerAdapter,
            @Value("${rulepilot.answer.model-provider:gemini}") String answerProvider,
            @Value("${rulepilot.critic.provider:fake}") String criticAdapter,
            @Value("${rulepilot.critic.model-provider:gemini}") String criticProvider) {
        this.factory = factory;
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>();
        addStartupProvider(providers, "gemini", properties.gemini());
        addStartupProvider(providers, "openai", properties.openai());
        addStartupProvider(providers, "deepseek", properties.deepseek());
        addStartupProvider(providers, "compatible", properties.compatible());
        this.state = new AtomicReference<>(new State(
                Map.copyOf(providers),
                new Assignments(
                        assignment(teachingAdapter, teachingProvider, providers),
                        assignment(answerAdapter, answerProvider, providers),
                        assignment(criticAdapter, criticProvider, providers)),
                0));
    }

    public ChatModel modelFor(Role role) {
        State current = state.get();
        String provider = current.assignments().forRole(role);
        ConfiguredProvider configured = current.providers().get(provider);
        if (configured == null) {
            throw new IllegalStateException("model provider '" + provider + "' is not configured");
        }
        return configured.model();
    }

    public boolean usesFake(Role role) {
        return "fake".equals(providerFor(role));
    }

    public String providerFor(Role role) {
        return state.get().assignments().forRole(role);
    }

    public synchronized Snapshot configure(String provider, String apiKey, String baseUrl, String model) {
        String id = providerId(provider);
        String checkedModel = required(model, "model name", 200);
        String checkedBaseUrl = "gemini".equals(id) ? "" : validBaseUrl(baseUrl);
        String checkedApiKey = required(apiKey, "API key", 4096);
        ChatModel client = factory.create(id, checkedApiKey, checkedBaseUrl, checkedModel);

        State current = state.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.put(id, new ConfiguredProvider(id, checkedBaseUrl, checkedModel, client));
        state.set(new State(Map.copyOf(providers), current.assignments(), current.revision() + 1));
        return snapshot();
    }

    public synchronized Snapshot disable(String provider) {
        String id = providerId(provider);
        State current = state.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.remove(id);
        Assignments assignments = current.assignments().replace(id, "fake");
        state.set(new State(Map.copyOf(providers), assignments, current.revision() + 1));
        return snapshot();
    }

    public synchronized Snapshot assign(String teaching, String answer, String critic) {
        State current = state.get();
        Assignments assignments = new Assignments(
                selectable(teaching, current.providers()),
                selectable(answer, current.providers()),
                selectable(critic, current.providers()));
        state.set(new State(current.providers(), assignments, current.revision() + 1));
        return snapshot();
    }

    public Snapshot snapshot() {
        State current = state.get();
        List<ProviderView> providers = new ArrayList<>();
        for (String id : SUPPORTED) {
            ConfiguredProvider configured = current.providers().get(id);
            providers.add(new ProviderView(
                    id,
                    configured != null,
                    configured == null ? defaultBaseUrl(id) : configured.baseUrl(),
                    configured == null ? defaultModel(id) : configured.modelName(),
                    configured != null));
        }
        return new Snapshot(List.copyOf(providers), current.assignments(), current.revision(), true);
    }

    private void addStartupProvider(Map<String, ConfiguredProvider> providers, String id, Provider properties) {
        if (properties == null || !properties.enabled()) {
            return;
        }
        String baseUrl = "gemini".equals(id) ? "" : validBaseUrl(properties.baseUrl());
        String model = required(properties.model(), "model name", 200);
        ChatModel client = factory.create(id, properties.apiKey(), baseUrl, model);
        providers.put(id, new ConfiguredProvider(id, baseUrl, model, client));
    }

    private String assignment(String adapter, String provider, Map<String, ConfiguredProvider> providers) {
        return "spring-ai".equalsIgnoreCase(adapter) ? selectable(provider, providers) : "fake";
    }

    private String selectable(String provider, Map<String, ConfiguredProvider> providers) {
        if (provider == null || "fake".equalsIgnoreCase(provider.trim())) {
            return "fake";
        }
        String id = providerId(provider);
        if (!providers.containsKey(id)) {
            throw new IllegalArgumentException("model provider '" + id + "' must be configured before assignment");
        }
        return id;
    }

    private String providerId(String provider) {
        String id = required(provider, "provider", 40).toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(id)) {
            throw new IllegalArgumentException("unsupported model provider: " + id);
        }
        return id;
    }

    private String validBaseUrl(String value) {
        String baseUrl = required(value, "model base URL", 500);
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("model base URL is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("model base URL must use http or https");
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("remote model base URL must use https");
        }
        return baseUrl;
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private String required(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return trimmed;
    }

    private String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "openai" -> "https://api.openai.com";
            case "deepseek" -> "https://api.deepseek.com";
            case "compatible" -> "http://localhost:11434/v1";
            default -> "";
        };
    }

    private String defaultModel(String provider) {
        return switch (provider) {
            case "gemini" -> "gemini-2.5-flash";
            case "openai" -> "gpt-5-mini";
            case "deepseek" -> "deepseek-v4-flash";
            default -> "local-model";
        };
    }

    private record ConfiguredProvider(String id, String baseUrl, String modelName, ChatModel model) {}

    private record State(Map<String, ConfiguredProvider> providers, Assignments assignments, long revision) {}

    public record ProviderView(String id, boolean configured, String baseUrl, String model, boolean apiKeyConfigured) {}

    public record Assignments(String teaching, String answer, String critic) {
        String forRole(Role role) {
            return switch (role) {
                case TEACHING -> teaching;
                case ANSWER -> answer;
                case CRITIC -> critic;
            };
        }

        Assignments replace(String current, String replacement) {
            return new Assignments(
                    teaching.equals(current) ? replacement : teaching,
                    answer.equals(current) ? replacement : answer,
                    critic.equals(current) ? replacement : critic);
        }
    }

    public record Snapshot(List<ProviderView> providers, Assignments assignments, long revision, boolean volatileSecrets) {}
}
