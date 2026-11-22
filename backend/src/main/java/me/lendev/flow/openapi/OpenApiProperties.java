package me.lendev.flow.openapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 开放入口的部署环境、配额及保留期限。
 */
@Validated
@ConfigurationProperties("flow.open-api")
public record OpenApiProperties(
        boolean enabled,
        @DefaultValue("local") @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,31}") String environment,
        @DefaultValue("http://localhost:7086") String publicBaseUrl,
        @DefaultValue("1800") @Min(1) @Max(86400) int tokenTtlSeconds,
        @DefaultValue("60") @Min(1) int tokenIpLimit,
        @DefaultValue("30") @Min(1) int tokenAppLimit,
        @DefaultValue("30") @Min(1) @Max(365) int callRetentionDays,
        Set<String> trustedProxyAddresses) {

    public OpenApiProperties {
        trustedProxyAddresses = trustedProxyAddresses == null ? Set.of() : Set.copyOf(trustedProxyAddresses);
    }

    Duration tokenTtl() {
        return Duration.ofSeconds(tokenTtlSeconds);
    }
}
