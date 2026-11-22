package me.lendev.flow.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.lendev.flow.common.auth.domain.DomainAuthenticator;
import me.lendev.flow.common.auth.mfa.MfaSecrets;
import me.lendev.flow.common.auth.rate.RateLimitProperties;
import me.lendev.flow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PolicyCatalogTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final JsonMapper json = new JsonMapper();
    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final AuthPolicyDefinition auth = new AuthPolicyDefinition(json, mock(AuthPolicyRepository.class),
            mock(DomainAuthenticator.class), mock(MfaSecrets.class));

    @Test
    void importsDeploymentDefaultsOnceAndKeepsTheStoredPolicyOnRestart() {
        var inserted = new AtomicReference<PolicyRepository.Stored>();
        when(repository.find(AuthPolicy.CODE, false)).thenAnswer(invocation -> inserted.get());
        doAnswer(invocation -> {
            inserted.set(stored(invocation.getArgument(1), auth.schemaVersion()));
            return null;
        }).when(repository).insert(eq(auth), org.mockito.ArgumentMatchers.any(), eq(NOW));
        var rate = new RateLimitProperties();
        rate.setLoginPerAccount(27);
        var env = new MockEnvironment().withProperty("SA_TOKEN_TIMEOUT", "36000");

        catalog(env, rate).run(null);

        var config = inserted.get().config();
        assertThat(config.path("session").path("absoluteTimeoutSeconds").asLong()).isEqualTo(36_000);
        assertThat(config.path("rateLimit").path("loginPerAccount").asInt()).isEqualTo(27);
        catalog(new MockEnvironment(), new RateLimitProperties()).run(null);
        verify(repository).insert(auth, config, NOW);
    }

    @Test
    void acceptsAConcurrentInitializerButRejectsAnUnsupportedSchema() {
        var winner = stored(auth.defaults(), auth.schemaVersion());
        when(repository.find(AuthPolicy.CODE, false)).thenReturn(null, winner);
        doAnswer(invocation -> {
            throw new DuplicateKeyException("initialized concurrently");
        })
                .when(repository).insert(eq(auth), org.mockito.ArgumentMatchers.any(), eq(NOW));
        var catalog = catalog(new MockEnvironment(), new RateLimitProperties());
        catalog.run(null);

        when(repository.find(AuthPolicy.CODE, true)).thenReturn(stored(auth.defaults(), auth.schemaVersion() - 1));
        assertThatThrownBy(() -> catalog.read(auth, true)).isInstanceOf(PolicyUnavailableException.class);
        assertThatThrownBy(() -> catalog.require("UNREGISTERED")).isInstanceOf(BadRequestException.class)
                .hasMessage("error.policy.unknown");
        verify(repository, never()).find("UNREGISTERED", false);
    }

    private PolicyCatalog catalog(MockEnvironment env, RateLimitProperties rate) {
        return new PolicyCatalog(List.of(auth), repository, Clock.fixed(NOW, ZoneOffset.UTC), env, rate, json);
    }

    private PolicyRepository.Stored stored(JsonNode config, int version) {
        return new PolicyRepository.Stored(AuthPolicy.CODE, config, version, 0, NOW, null);
    }
}
