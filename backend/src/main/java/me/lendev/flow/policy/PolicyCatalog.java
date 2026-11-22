package me.lendev.flow.policy;

import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.auth.rate.RateLimitProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 初始化已登记的策略类型，并校验数据库中的结构版本。
 */
@Component
@Order(0)
@Slf4j
public class PolicyCatalog implements ApplicationRunner {

    private final Map<String, PolicyDefinition> definitions;
    private final PolicyRepository repository;
    private final Clock clock;
    private final Environment environment;
    private final RateLimitProperties initialRateLimit;
    private final ObjectMapper json;

    public PolicyCatalog(List<PolicyDefinition> definitions, PolicyRepository repository,
                         Clock clock, Environment environment, RateLimitProperties initialRateLimit,
                         ObjectMapper json) {
        this.definitions = definitions.stream().collect(Collectors.toUnmodifiableMap(PolicyDefinition::code, Function.identity()));
        this.repository = repository;
        this.clock = clock;
        this.environment = environment;
        this.initialRateLimit = initialRateLimit;
        this.json = json;
    }

    PolicyDefinition require(String code) {
        var definition = definitions.get(code);
        if (definition == null) {
            throw BadRequestException.localized("error.policy.unknown");
        }
        return definition;
    }

    PolicyRepository.Stored read(PolicyDefinition definition, boolean lock) {
        var stored = repository.find(definition.code(), lock);
        if (stored == null || stored.schemaVersion() != definition.schemaVersion()) {
            log.warn("系统策略缺失或结构版本不匹配，请检查策略初始化和数据库迁移记录，requestId={} policyCode={} expectedSchemaVersion={} storedSchemaVersion={}",
                    AuditParticipants.currentRequestId(), definition.code(), definition.schemaVersion(),
                    stored == null ? null : stored.schemaVersion());
            throw new PolicyUnavailableException();
        }
        return stored;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var definition : definitions.values()) {
            if (repository.find(definition.code(), false) == null) {
                var config = initialConfig(definition);
                definition.validate(config);
                try {
                    repository.insert(definition, config, clock.instant());
                } catch (DuplicateKeyException ignored) {
                    // 并行启动时由先提交的实例写入初始记录。
                }
            }
            definition.validate(read(definition, false).config());
        }
    }

    private JsonNode initialConfig(PolicyDefinition definition) {
        var config = definition.defaults();
        if (AuthPolicy.CODE.equals(definition.code())) {
            ((ObjectNode) config).set("rateLimit", json.valueToTree(initialRateLimit.toPolicy()));
            Long timeout = environment.getProperty("SA_TOKEN_TIMEOUT", Long.class);
            if (timeout != null) {
                ((ObjectNode) config.get("session")).put("absoluteTimeoutSeconds", timeout);
            }
        }
        return config;
    }
}
