package me.lendev.flow.openapi.admin;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.openapi.ManagedReadRegistry.Field;
import me.lendev.flow.openapi.ManagedReadRegistry.Parameter;
import tools.jackson.databind.JsonNode;

/**
 * 开放 API 管理端的请求、配置视图和调用历史契约。
 */
public final class OpenApiAdminModels {

    static final String NO_DATA_SCOPE = "NONE";

    private OpenApiAdminModels() {
    }

    public enum EndpointStatus {REGISTERED, PUBLISHED, OFFLINE}

    public enum GrantStatus {GRANTED, REVOKED}

    public enum DataScopeMode {NOT_APPLICABLE, ALL, RESTRICTED}

    public enum SecretStatus {CANDIDATE, ACTIVE, RETIRING, REVOKED}

    public enum RevokeMode {RETIRE, ABANDON, COMPROMISED}

    public enum ScopeType {MARKET, INSTITUTION}

    public enum CallResult {SUCCESS, DENIED, FAILED}

    public record Endpoint(long id, String endpointCode, String moduleCode, String displayName, String httpMethod,
                           String pathTemplate, String apiVersion, String dataScopeType, EndpointStatus status,
                           long version, String documentUrl, long authorizedAppCount, EndpointSourceInfo source) {
    }

    public enum SourceRestriction {READ_ONLY, POLICY_REQUIRED}

    public record EndpointSource(String sourcePath, String httpMethod, String moduleCode, boolean importable,
                                 SourceRestriction reason, String dataScopeType, List<Parameter> parameters,
                                 List<Field> fields, boolean collection, boolean page, boolean wholeResponse) {
    }

    public record EndpointSourceInfo(String sourcePath, boolean contractMatches, List<Parameter> parameters,
                                     List<Field> fields, boolean collection, boolean page, boolean wholeResponse) {
    }

    public record ImportEndpoint(@NotBlank @Size(max = 256) String sourcePath,
                                 @NotBlank @Size(max = 128) String displayName,
                                 @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,95}") String endpointCode,
                                 @NotBlank @Size(max = 256) String pathTemplate,
                                 @Size(max = 32) List<@NotBlank @Size(max = 64) String> responseFields) {
    }

    public record ConfirmSourceContract(@NotNull @PositiveOrZero Long version) {
    }

    public record EndpointUpdate(@NotNull EndpointStatus status, @NotNull @PositiveOrZero Long version) {
    }

    public record App(long id, String clientId, String name, String environment, boolean enabled, Integer ownerUserId,
                      String ownerDisplayName, String description, int rateLimitCount, int rateLimitWindowSeconds,
                      long version, Instant createdAt, Instant updatedAt) {
    }

    public record CreateApp(@NotBlank @Size(max = 128) String name, @Positive Integer ownerUserId,
                            @Size(max = 512) String description, @Positive Integer rateLimitCount,
                            @Positive Integer rateLimitWindowSeconds) {
    }

    /**
     * 负责人和用途支持显式清空，省略任意字段时保留当前配置。
     */
    @Getter
    @Setter
    public static class PatchApp {
        @NotNull
        @PositiveOrZero
        private Long version;
        @Size(max = 128)
        private String name;
        @Positive
        private Integer ownerUserId;
        @Size(max = 512)
        private String description;
        @Positive
        private Integer rateLimitCount;
        @Positive
        private Integer rateLimitWindowSeconds;

        @JsonIgnore
        @Setter(lombok.AccessLevel.NONE)
        private boolean ownerUserIdSupplied;
        @JsonIgnore
        @Setter(lombok.AccessLevel.NONE)
        private boolean descriptionSupplied;

        @JsonSetter(nulls = Nulls.FAIL)
        public void setName(String name) {
            this.name = name;
        }

        @JsonSetter
        public void setOwnerUserId(Integer ownerUserId) {
            this.ownerUserId = ownerUserId;
            this.ownerUserIdSupplied = true;
        }

        @JsonSetter
        public void setDescription(String description) {
            this.description = description;
            this.descriptionSupplied = true;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setRateLimitCount(Integer rateLimitCount) {
            this.rateLimitCount = rateLimitCount;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setRateLimitWindowSeconds(Integer rateLimitWindowSeconds) {
            this.rateLimitWindowSeconds = rateLimitWindowSeconds;
        }
    }

    public record AppStatus(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) {
    }

    public record OwnerOption(int id, String displayName) {
    }

    public record ScopeOption(long id, String code, String name, String nameEn) {
    }

    public record GrantUpdate(@NotNull GrantStatus status, DataScopeMode dataScopeMode,
                              JsonNode dataScope,
                              @Positive Integer rateLimitCount, @Positive Integer rateLimitWindowSeconds,
                              @PositiveOrZero Long version) {
    }

    public record Grant(long appId, long endpointId, String endpointCode, String displayName,
                        EndpointStatus endpointStatus,
                        String dataScopeType, GrantStatus status, DataScopeMode dataScopeMode, JsonNode dataScope,
                        Integer rateLimitCount, Integer rateLimitWindowSeconds, long version) {
    }

    public record SecretMetadata(int versionNo, SecretStatus status, Instant expiresAt, Instant activatedAt,
                                 Instant revokedAt, Instant tokensRevokedAt, Instant createdAt) {
    }

    /**
     * 仅用于新候选创建后的单次 HTTP 响应。
     */
    public record GeneratedSecret(int versionNo, String clientSecret, Instant expiresAt) {
        @Override
        public String toString() {
            return "GeneratedSecret[versionNo=" + versionNo + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record SecretActivation(SecretMetadata activated, SecretMetadata replaced) {
    }

    public record SecretRevoke(@NotNull RevokeMode mode) {
    }

    public record Call(long id, String requestId, Instant occurredAt, long durationMs, int httpStatus, Long endpointId,
                       String endpointCode, String errorCode, String sourceIp, CallResult result) {
    }
}
