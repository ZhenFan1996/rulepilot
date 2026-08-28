package com.rulepilot.modelconfig;

import com.rulepilot.modelconfig.ModelProviderProperties.Provider;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.modelconfig.adapter.out.QuotaAwareChatModel;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableConfigurationProperties(ModelProviderProperties.class)
public class RuntimeModelConfiguration {

    public enum Role {
        TEACHING,
        VISUAL,
        ANSWER,
        CRITIC,
        RECOMMENDATION
    }

    private static final List<String> SUPPORTED = List.of("gemini", "openai", "deepseek", "qwen", "compatible");

    private final ChatModelFactory factory;
    private final ConfiguredProvider recommendationStartupOverride;
    private final State startupState;
    private final State personalDefaultState;
    private final Set<String> startupAllowedUsers;
    private final boolean deepSeekGenerationThinking;
    private final ModelConfigurationStore store;
    private final ModelCredentialCipher credentialCipher;
    private final ModelAccountQuota quota;
    private final long perCallReservationTokens;
    private final AtomicReference<State> platformState;
    private final ConcurrentMap<String, AtomicReference<State>> userStates = new ConcurrentHashMap<>();

    public RuntimeModelConfiguration(
            ChatModelFactory factory,
            ModelProviderProperties properties,
            @Value("${rulepilot.teaching.provider:fake}") String teachingAdapter,
            @Value("${rulepilot.teaching.model-provider:gemini}") String teachingProvider,
            @Value("${rulepilot.visual.provider:fake}") String visualAdapter,
            @Value("${rulepilot.visual.model-provider:gemini}") String visualProvider,
            @Value("${rulepilot.answer.provider:fake}") String answerAdapter,
            @Value("${rulepilot.answer.model-provider:gemini}") String answerProvider,
            @Value("${rulepilot.critic.provider:fake}") String criticAdapter,
            @Value("${rulepilot.critic.model-provider:gemini}") String criticProvider,
            @Value("${rulepilot.bgg.recommendation-agent.provider:fake}") String recommendationAdapter,
            @Value("${rulepilot.bgg.recommendation-agent.model-provider:qwen}") String recommendationProvider,
            @Value("${rulepilot.models.deepseek.generation-thinking:false}") boolean deepSeekGenerationThinking,
            @Value("${rulepilot.models.startup-allowed-users:}") String startupAllowedUsers) {
        this(
                factory,
                properties,
                teachingAdapter,
                teachingProvider,
                visualAdapter,
                visualProvider,
                answerAdapter,
                answerProvider,
                criticAdapter,
                criticProvider,
                recommendationAdapter,
                recommendationProvider,
                "",
                deepSeekGenerationThinking,
                startupAllowedUsers);
    }

    RuntimeModelConfiguration(
            ChatModelFactory factory,
            ModelProviderProperties properties,
            String teachingAdapter,
            String teachingProvider,
            String visualAdapter,
            String visualProvider,
            String answerAdapter,
            String answerProvider,
            String criticAdapter,
            String criticProvider,
            String recommendationAdapter,
            String recommendationProvider,
            String recommendationModel,
            boolean deepSeekGenerationThinking,
            String startupAllowedUsers) {
        this(
                factory,
                properties,
                teachingAdapter,
                teachingProvider,
                visualAdapter,
                visualProvider,
                answerAdapter,
                answerProvider,
                criticAdapter,
                criticProvider,
                recommendationAdapter,
                recommendationProvider,
                recommendationModel,
                deepSeekGenerationThinking,
                startupAllowedUsers,
                null,
                null,
                null,
                16_000);
    }

    @Autowired
    public RuntimeModelConfiguration(
            ChatModelFactory factory,
            ModelProviderProperties properties,
            @Value("${rulepilot.teaching.provider:fake}") String teachingAdapter,
            @Value("${rulepilot.teaching.model-provider:gemini}") String teachingProvider,
            @Value("${rulepilot.visual.provider:fake}") String visualAdapter,
            @Value("${rulepilot.visual.model-provider:gemini}") String visualProvider,
            @Value("${rulepilot.answer.provider:fake}") String answerAdapter,
            @Value("${rulepilot.answer.model-provider:gemini}") String answerProvider,
            @Value("${rulepilot.critic.provider:fake}") String criticAdapter,
            @Value("${rulepilot.critic.model-provider:gemini}") String criticProvider,
            @Value("${rulepilot.bgg.recommendation-agent.provider:fake}") String recommendationAdapter,
            @Value("${rulepilot.bgg.recommendation-agent.model-provider:qwen}") String recommendationProvider,
            @Value("${rulepilot.bgg.recommendation-agent.model:}") String recommendationModel,
            @Value("${rulepilot.models.deepseek.generation-thinking:false}") boolean deepSeekGenerationThinking,
            @Value("${rulepilot.models.startup-allowed-users:}") String startupAllowedUsers,
            ModelConfigurationStore store,
            ModelCredentialCipher credentialCipher,
            ModelAccountQuota quota,
            @Value("${rulepilot.models.per-call-reservation-tokens:16000}") long perCallReservationTokens) {
        if (perCallReservationTokens < 1) {
            throw new IllegalArgumentException("Per-call model quota reservation must be positive");
        }
        this.factory = factory;
        this.deepSeekGenerationThinking = deepSeekGenerationThinking;
        this.store = store;
        this.credentialCipher = credentialCipher;
        this.quota = quota;
        this.perCallReservationTokens = perCallReservationTokens;
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>();
        addStartupProvider(providers, "gemini", properties.gemini());
        addStartupProvider(providers, "openai", properties.openai());
        addStartupProvider(providers, "deepseek", properties.deepseek());
        addStartupProvider(providers, "qwen", properties.qwen());
        addStartupProvider(providers, "compatible", properties.compatible());
        String teachingAssignment = assignment(teachingAdapter, teachingProvider, providers);
        String recommendationAssignment = assignment(recommendationAdapter, recommendationProvider, providers);
        this.recommendationStartupOverride = startupRecommendationOverride(
                recommendationAssignment, recommendationModel, properties);
        this.startupState = new State(
                Map.copyOf(providers),
                new Assignments(
                        teachingAssignment,
                        visualAssignment(visualAdapter, visualProvider, teachingAssignment, providers),
                        assignment(answerAdapter, answerProvider, providers),
                        assignment(criticAdapter, criticProvider, providers),
                        recommendationAssignment),
                0);
        this.personalDefaultState = new State(
                Map.of(),
                new Assignments("fake", "fake", "fake", "fake", "fake"),
                0);
        this.startupAllowedUsers = parseStartupAllowedUsers(startupAllowedUsers);
        this.platformState = new AtomicReference<>(durable() ? loadPlatform() : this.startupState);
    }

    public ChatModel modelFor(Role role) {
        return resolvedModelFor(role).model();
    }

    public ResolvedModel resolvedModelFor(Role role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return resolvedModelFor(role, authentication.getName());
        }
        return resolvedModelFor(role, startupState, null);
    }

    public ChatModel modelFor(Role role, String username) {
        return resolvedModelFor(role, username).model();
    }

    public ResolvedModel resolvedModelFor(Role role, String username) {
        if (username == null || username.isBlank()) return resolvedModelFor(role, startupState, null);
        String owner = required(username, "username", 160);
        State state = stateFor(owner);
        return resolvedModelFor(role, state, owner);
    }

    private ResolvedModel resolvedModelFor(Role role, State state, String quotaOwner) {
        ConfiguredProvider configured = configured(role, state);
        ChatModel selected = configured.model();
        if (quota != null && quotaOwner != null) {
            selected = new QuotaAwareChatModel(
                    selected,
                    quota,
                    quotaOwner,
                    configured.credentialSource(),
                    role,
                    configured.id(),
                    configured.modelName(),
                    perCallReservationTokens,
                    Clock.systemUTC());
        }
        boolean deepSeekNonThinking = !deepSeekGenerationThinking
                && (role == Role.TEACHING
                        || role == Role.ANSWER
                        || role == Role.CRITIC
                        || role == Role.RECOMMENDATION)
                && "deepseek".equals(configured.id());
        return new ResolvedModel(
                selected,
                configured.id(),
                configured.modelName(),
                deepSeekNonThinking);
    }

    private ConfiguredProvider configured(Role role, State current) {
        String provider = current.assignments().forRole(role);
        ConfiguredProvider configured = current.providers().get(provider);
        if (configured == null) {
            throw new IllegalStateException("model provider '" + provider + "' is not configured");
        }
        if (role == Role.RECOMMENDATION
                && recommendationStartupOverride != null
                && configured.startupDefault()
                && recommendationStartupOverride.id().equals(provider)) {
            return recommendationStartupOverride;
        }
        return configured;
    }

    public boolean usesFake(Role role) {
        return "fake".equals(providerFor(role));
    }

    public boolean usesFake(Role role, String username) {
        return "fake".equals(stateForOrStartup(username).assignments().forRole(role));
    }

    public boolean usesDeepSeekNonThinkingGeneration(Role role) {
        return usesDeepSeekNonThinkingGeneration(role, null);
    }

    public boolean usesDeepSeekNonThinkingGeneration(Role role, String username) {
        String provider = username == null || username.isBlank()
                ? providerFor(role)
                : providerFor(role, username);
        return !deepSeekGenerationThinking
                && (role == Role.TEACHING || role == Role.ANSWER || role == Role.CRITIC || role == Role.RECOMMENDATION)
                && "deepseek".equals(provider);
    }

    public String providerFor(Role role) {
        return currentState().assignments().forRole(role);
    }

    public String providerFor(Role role, String username) {
        return stateForOrStartup(username).assignments().forRole(role);
    }

    /** Returns the concrete model selected for a role, including a user's private configuration when present. */
    public String modelNameFor(Role role) {
        return modelNameFor(role, currentState());
    }

    public String modelNameFor(Role role, String username) {
        return modelNameFor(role, stateForOrStartup(username));
    }

    private String modelNameFor(Role role, State state) {
        return configured(role, state).modelName();
    }

    /** Returns only the non-secret provider/model pair that a role would use for the current account. */
    public EffectiveModel effectiveModelFor(Role role) {
        return effectiveModelFor(role, currentState());
    }

    public EffectiveModel effectiveModelFor(Role role, String username) {
        return effectiveModelFor(role, stateForOrStartup(username));
    }

    private EffectiveModel effectiveModelFor(Role role, State state) {
        String provider = state.assignments().forRole(role);
        if ("fake".equals(provider)) {
            return new EffectiveModel("fake", "");
        }
        return new EffectiveModel(provider, configured(role, state).modelName());
    }

    public boolean supportsVision(Role role) {
        State current = currentState();
        ConfiguredProvider configured = current.providers().get(current.assignments().forRole(role));
        return configured != null && configured.visionCapable();
    }

    public boolean supportsVision(Role role, String username) {
        State current = stateForOrStartup(username);
        ConfiguredProvider configured = current.providers().get(current.assignments().forRole(role));
        return configured != null && configured.visionCapable();
    }

    @Transactional
    public synchronized Snapshot configure(
            String username,
            String provider,
            String apiKey,
            String baseUrl,
            String model,
            boolean visionCapable) {
        String id = providerId(provider);
        String checkedModel = required(model, "model name", 200);
        String checkedBaseUrl = "gemini".equals(id) ? "" : validBaseUrl(baseUrl);
        String checkedApiKey = required(apiKey, "API key", 4096);
        ChatModel client = factory.create(id, checkedApiKey, checkedBaseUrl, checkedModel);

        AtomicReference<State> userState = userState(username);
        State current = userState.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.put(id, new ConfiguredProvider(
                id,
                checkedBaseUrl,
                checkedModel,
                client,
                visionCapable,
                ModelAccountQuota.CredentialSource.PERSONAL,
                false));
        Assignments assignments = visionCapable ? current.assignments() : current.assignments().withoutVisual(id);
        long revision = current.revision() + 1;
        if (managedPersistence()) {
            var encrypted = credentialCipher.encrypt(personalContext(username, id), checkedApiKey);
            revision = store.savePersonalProvider(
                    required(username, "username", 160),
                    new ModelConfigurationStore.StoredProvider(
                            id, encrypted, checkedBaseUrl, checkedModel, visionCapable, 0),
                    Instant.now());
            if (!assignments.equals(current.assignments())) {
                revision = store.savePersonalAssignments(username, stored(assignments), Instant.now());
            }
        }
        userState.set(new State(Map.copyOf(providers), assignments, revision));
        return snapshot(username);
    }

    @Transactional
    public synchronized Snapshot disable(String username, String provider) {
        String id = providerId(provider);
        AtomicReference<State> userState = userState(username);
        State current = userState.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.remove(id);
        ConfiguredProvider platformProvider = platformState.get().providers().get(id);
        if (platformProvider != null) providers.put(id, platformProvider);
        Assignments assignments = platformProvider == null
                ? current.assignments().replace(id, "fake")
                : current.assignments();
        long revision = current.revision() + 1;
        if (durable()) {
            revision = store.removePersonalProvider(username, id, Instant.now());
            revision = store.savePersonalAssignments(username, stored(assignments), Instant.now());
        }
        userState.set(new State(Map.copyOf(providers), assignments, revision));
        return snapshot(username);
    }

    @Transactional
    public synchronized Snapshot assign(
            String username,
            String teaching,
            String visual,
            String answer,
            String critic,
            String recommendation) {
        AtomicReference<State> userState = userState(username);
        State current = userState.get();
        Assignments assignments = new Assignments(
                selectable(teaching, current.providers()),
                selectableVisual(visual, current.providers()),
                selectable(answer, current.providers()),
                selectable(critic, current.providers()),
                selectable(recommendation, current.providers()));
        long revision = durable()
                ? store.savePersonalAssignments(username, stored(assignments), Instant.now())
                : current.revision() + 1;
        userState.set(new State(current.providers(), assignments, revision));
        return snapshot(username);
    }

    @Transactional
    public synchronized Snapshot configurePlatform(
            String administrator,
            String provider,
            String apiKey,
            String baseUrl,
            String model,
            boolean visionCapable) {
        requireDurableCredentials();
        String actor = required(administrator, "administrator", 160);
        String id = providerId(provider);
        String checkedModel = required(model, "model name", 200);
        String checkedBaseUrl = "gemini".equals(id) ? "" : validBaseUrl(baseUrl);
        String checkedApiKey = required(apiKey, "API key", 4096);
        ChatModel client = factory.create(id, checkedApiKey, checkedBaseUrl, checkedModel);
        State current = platformState.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.put(id, new ConfiguredProvider(
                id,
                checkedBaseUrl,
                checkedModel,
                client,
                visionCapable,
                ModelAccountQuota.CredentialSource.PLATFORM,
                false));
        Assignments assignments = visionCapable ? current.assignments() : current.assignments().withoutVisual(id);
        Instant updatedAt = Instant.now();
        long revision = store.savePlatformProvider(
                actor,
                new ModelConfigurationStore.StoredProvider(
                        id,
                        credentialCipher.encrypt(platformContext(id), checkedApiKey),
                        checkedBaseUrl,
                        checkedModel,
                        visionCapable,
                        0),
                updatedAt);
        if (!assignments.equals(current.assignments())) {
            revision = store.savePlatformAssignments(actor, stored(assignments), updatedAt);
        }
        replacePlatform(new State(Map.copyOf(providers), assignments, revision));
        return platformSnapshot();
    }

    @Transactional
    public synchronized Snapshot disablePlatform(String administrator, String provider) {
        requireDurableCredentials();
        String actor = required(administrator, "administrator", 160);
        String id = providerId(provider);
        State current = platformState.get();
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(current.providers());
        providers.remove(id);
        ConfiguredProvider startupProvider = startupState.providers().get(id);
        if (startupProvider != null) providers.put(id, startupProvider);
        Assignments assignments = startupProvider == null
                ? current.assignments().replace(id, "fake")
                : current.assignments();
        Instant updatedAt = Instant.now();
        long revision = store.removePlatformProvider(actor, id, updatedAt);
        revision = store.savePlatformAssignments(actor, stored(assignments), updatedAt);
        replacePlatform(new State(Map.copyOf(providers), assignments, revision));
        return platformSnapshot();
    }

    @Transactional
    public synchronized Snapshot assignPlatform(
            String administrator,
            String teaching,
            String visual,
            String answer,
            String critic,
            String recommendation) {
        requireDurableCredentials();
        String actor = required(administrator, "administrator", 160);
        State current = platformState.get();
        Assignments assignments = new Assignments(
                selectable(teaching, current.providers()),
                selectableVisual(visual, current.providers()),
                selectable(answer, current.providers()),
                selectable(critic, current.providers()),
                selectable(recommendation, current.providers()));
        long revision = store.savePlatformAssignments(actor, stored(assignments), Instant.now());
        replacePlatform(new State(current.providers(), assignments, revision));
        return platformSnapshot();
    }

    public Snapshot platformSnapshot() {
        return snapshot(platformState.get(), false, false);
    }

    public synchronized Snapshot assign(
            String username, String teaching, String visual, String answer, String critic) {
        String currentRecommendation = stateFor(username).assignments().recommendation();
        return assign(username, teaching, visual, answer, critic, currentRecommendation);
    }

    public Snapshot snapshot(String username) {
        return snapshot(
                stateFor(username),
                !durable(),
                startupAllowedUsers.contains(required(username, "username", 160)));
    }

    private Snapshot snapshot(State current, boolean volatileSecrets, boolean managedStartupAccess) {
        List<ProviderView> providers = new ArrayList<>();
        for (String id : SUPPORTED) {
            ConfiguredProvider configured = current.providers().get(id);
            providers.add(new ProviderView(
                    id,
                    configured != null,
                    configured == null ? defaultBaseUrl(id) : configured.baseUrl(),
                    configured == null ? defaultModel(id) : configured.modelName(),
                    configured != null,
                    configured == null ? defaultVisionCapable(id) : configured.visionCapable(),
                    configured == null ? "NONE" : configured.credentialSource().name()));
        }
        return new Snapshot(
                List.copyOf(providers),
                current.assignments(),
                effectiveModelFor(Role.RECOMMENDATION, current),
                current.revision(),
                volatileSecrets,
                managedStartupAccess);
    }

    private State currentState() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return startupState;
        }
        return stateFor(authentication.getName());
    }

    private State stateFor(String username) {
        String owner = required(username, "username", 160);
        AtomicReference<State> personal = userStates.get(owner);
        if (personal != null) return personal.get();
        if (durable()) {
            return userStates.computeIfAbsent(owner, ignored -> new AtomicReference<>(loadPersonal(owner))).get();
        }
        return startupAllowedUsers.contains(owner) ? startupState : personalDefaultState;
    }

    private State stateForOrStartup(String username) {
        return username == null || username.isBlank() ? startupState : stateFor(username);
    }

    private AtomicReference<State> userState(String username) {
        String owner = required(username, "username", 160);
        State initial = durable()
                ? loadPersonal(owner)
                : startupAllowedUsers.contains(owner) ? startupState : personalDefaultState;
        return userStates.computeIfAbsent(owner, ignored -> new AtomicReference<>(initial));
    }

    private State loadPersonal(String username) {
        return store.personal(username)
                .map(stored -> restoredPersonal(username, platformState.get(), stored))
                .orElseGet(platformState::get);
    }

    private State loadPlatform() {
        return store.platform()
                .map(stored -> restoredPlatform(stored, startupState))
                .orElse(startupState);
    }

    private State restoredPersonal(
            String username,
            State platform,
            ModelConfigurationStore.StoredConfiguration stored) {
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(platform.providers());
        for (ModelConfigurationStore.StoredProvider provider : stored.providers()) {
            String id = providerId(provider.provider());
            String apiKey = credentialCipher.decrypt(personalContext(username, id), provider.encryptedApiKey());
            ChatModel client = factory.create(id, apiKey, provider.baseUrl(), provider.model());
            providers.put(id, new ConfiguredProvider(
                    id,
                    provider.baseUrl(),
                    provider.model(),
                    client,
                    provider.visionCapable(),
                    ModelAccountQuota.CredentialSource.PERSONAL,
                    false));
        }
        Assignments assignments = stored.assignments() == null
                ? platform.assignments()
                : restoredAssignments(stored.assignments(), providers);
        return new State(Map.copyOf(providers), assignments, stored.revision());
    }

    private State restoredPlatform(ModelConfigurationStore.StoredConfiguration stored, State fallback) {
        Map<String, ConfiguredProvider> providers = new LinkedHashMap<>(fallback.providers());
        for (ModelConfigurationStore.StoredProvider provider : stored.providers()) {
            String id = providerId(provider.provider());
            String apiKey = credentialCipher.decrypt(platformContext(id), provider.encryptedApiKey());
            ChatModel client = factory.create(id, apiKey, provider.baseUrl(), provider.model());
            providers.put(id, new ConfiguredProvider(
                    id,
                    provider.baseUrl(),
                    provider.model(),
                    client,
                    provider.visionCapable(),
                    ModelAccountQuota.CredentialSource.PLATFORM,
                    false));
        }
        Assignments assignments = stored.assignments() == null
                ? fallback.assignments()
                : restoredAssignments(stored.assignments(), providers);
        return new State(Map.copyOf(providers), assignments, stored.revision());
    }

    private Assignments restoredAssignments(
            ModelConfigurationStore.StoredAssignments stored,
            Map<String, ConfiguredProvider> providers) {
        return new Assignments(
                selectable(stored.teaching(), providers),
                selectableVisual(stored.visual(), providers),
                selectable(stored.answer(), providers),
                selectable(stored.critic(), providers),
                selectable(stored.recommendation(), providers));
    }

    private ModelConfigurationStore.StoredAssignments stored(Assignments assignments) {
        return new ModelConfigurationStore.StoredAssignments(
                assignments.teaching(),
                assignments.visual(),
                assignments.answer(),
                assignments.critic(),
                assignments.recommendation(),
                0);
    }

    private boolean durable() {
        return store != null && credentialCipher != null && credentialCipher.available();
    }

    private boolean managedPersistence() {
        return store != null && credentialCipher != null;
    }

    private void requireDurableCredentials() {
        if (!managedPersistence() || !credentialCipher.available()) {
            throw new IllegalStateException("Durable model credentials are not configured");
        }
    }

    private void replacePlatform(State replacement) {
        platformState.set(replacement);
        userStates.clear();
    }

    private String personalContext(String username, String provider) {
        return "PERSONAL|" + required(username, "username", 160) + "|" + provider;
    }

    private String platformContext(String provider) {
        return "PLATFORM|" + provider;
    }

    private Set<String> parseStartupAllowedUsers(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(candidate -> !candidate.isBlank())
                .map(candidate -> required(candidate, "startup model account", 160))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void addStartupProvider(Map<String, ConfiguredProvider> providers, String id, Provider properties) {
        if (properties == null || !properties.enabled()) {
            return;
        }
        providers.put(id, startupProvider(id, properties, properties.model()));
    }

    private ConfiguredProvider startupRecommendationOverride(
            String provider, String model, ModelProviderProperties properties) {
        if (model == null || model.isBlank()) {
            return null;
        }
        if ("fake".equals(provider)) {
            throw new IllegalArgumentException(
                    "recommendation model requires a configured Spring AI recommendation provider");
        }
        Provider providerProperties = switch (provider) {
            case "gemini" -> properties.gemini();
            case "openai" -> properties.openai();
            case "deepseek" -> properties.deepseek();
            case "qwen" -> properties.qwen();
            case "compatible" -> properties.compatible();
            default -> throw new IllegalArgumentException("unsupported model provider: " + provider);
        };
        return startupProvider(provider, providerProperties, model);
    }

    private ConfiguredProvider startupProvider(String id, Provider properties, String modelName) {
        String baseUrl = "gemini".equals(id) ? "" : validBaseUrl(properties.baseUrl());
        String model = required(modelName, "model name", 200);
        ChatModel client = factory.create(id, properties.apiKey(), baseUrl, model);
        return new ConfiguredProvider(
                id,
                baseUrl,
                model,
                client,
                properties.visionCapable(),
                ModelAccountQuota.CredentialSource.PLATFORM,
                true);
    }

    private String assignment(String adapter, String provider, Map<String, ConfiguredProvider> providers) {
        return "spring-ai".equalsIgnoreCase(adapter) ? selectable(provider, providers) : "fake";
    }

    private String visualAssignment(
            String adapter,
            String provider,
            String teachingAssignment,
            Map<String, ConfiguredProvider> providers) {
        if ("spring-ai".equalsIgnoreCase(adapter)) {
            return selectableVisual(provider, providers);
        }
        return supportsVision(teachingAssignment, providers) ? teachingAssignment : "fake";
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

    private String selectableVisual(String provider, Map<String, ConfiguredProvider> providers) {
        String selected = selectable(provider, providers);
        if (!"fake".equals(selected) && !supportsVision(selected, providers)) {
            throw new IllegalArgumentException("visual model provider must support page images");
        }
        return selected;
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
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "compatible" -> "http://localhost:11434/v1";
            default -> "";
        };
    }

    private String defaultModel(String provider) {
        return switch (provider) {
            case "gemini" -> "gemini-2.5-flash";
            case "openai" -> "gpt-5-mini";
            case "deepseek" -> "deepseek-v4-flash";
            case "qwen" -> "qwen3-vl-plus";
            default -> "local-model";
        };
    }

    private boolean supportsVision(String provider, Map<String, ConfiguredProvider> providers) {
        ConfiguredProvider configured = providers.get(provider);
        return configured != null && configured.visionCapable();
    }

    private boolean defaultVisionCapable(String provider) {
        return "gemini".equals(provider) || "openai".equals(provider) || "qwen".equals(provider);
    }

    private record ConfiguredProvider(
            String id,
            String baseUrl,
            String modelName,
            ChatModel model,
            boolean visionCapable,
            ModelAccountQuota.CredentialSource credentialSource,
            boolean startupDefault) {}

    private record State(Map<String, ConfiguredProvider> providers, Assignments assignments, long revision) {}

    public record ProviderView(
            String id,
            boolean configured,
            String baseUrl,
            String model,
            boolean apiKeyConfigured,
            boolean visionCapable,
            String credentialSource) {}

    public record EffectiveModel(String provider, String model) {}

    /** One immutable provider selection for a complete model call, including its quota boundary. */
    public record ResolvedModel(
            ChatModel model,
            String provider,
            String modelName,
            boolean deepSeekNonThinkingGeneration) {}

    public record Assignments(String teaching, String visual, String answer, String critic, String recommendation) {
        String forRole(Role role) {
            return switch (role) {
                case TEACHING -> teaching;
                case VISUAL -> visual;
                case ANSWER -> answer;
                case CRITIC -> critic;
                case RECOMMENDATION -> recommendation;
            };
        }

        Assignments replace(String current, String replacement) {
            return new Assignments(
                    teaching.equals(current) ? replacement : teaching,
                    visual.equals(current) ? replacement : visual,
                    answer.equals(current) ? replacement : answer,
                    critic.equals(current) ? replacement : critic,
                    recommendation.equals(current) ? replacement : recommendation);
        }

        Assignments withoutVisual(String provider) {
            return visual.equals(provider) ? new Assignments(teaching, "fake", answer, critic, recommendation) : this;
        }
    }

    public record Snapshot(
            List<ProviderView> providers,
            Assignments assignments,
            EffectiveModel recommendationModel,
            long revision,
            boolean volatileSecrets,
            boolean managedStartupAccess) {}
}
