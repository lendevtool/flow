package me.lendev.flow.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.openapi.mapper.OpenApiAccessMapper;
import me.lendev.flow.openapi.mapper.OpenApiAccessMapper.Access;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 签发应用令牌，并基于主库的最新快照决策每次调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OpenApiAuthorization {
    private static final String CANDIDATE = "CANDIDATE";
    private final OpenApiProperties properties;
    private final OpenApiAccessMapper mapper;
    private final OpenApiRegistry registry;
    private final ManagedReadRegistry managed;
    private final OpenApiRateLimits limits;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * 根据应用凭据和已发布授权签发短期令牌，在 Redis 中保存授权快照。
     *
     * @param authorization Basic 认证头
     * @param grantType     客户端凭据授权类型
     * @param scope         请求的接口范围，省略时使用该应用当前可用的接口集合
     * @param requestId     本次签发请求的关联 ID
     * @return 仅向调用方交付的令牌及有效期
     * @throws OpenApiException 凭据、授权类型、范围或签发条件不满足
     */
    TokenResponse issue(String authorization, String grantType, String scope, String requestId) {
        if (!properties.enabled()) {
            throw OpenApiException.unavailableEndpoint();
        }
        if (grantType == null || grantType.isBlank()) {
            throw new OpenApiException(400, "invalid_request", "grant_type is required");
        }
        if (!"client_credentials".equals(grantType)) {
            throw new OpenApiException(400, "unsupported_grant_type", "Unsupported grant type");
        }
        var credentials = credentials(authorization);
        var client = mapper.client(credentials.id(), properties.environment());
        if (client == null || !client.enabled()) {
            throw OpenApiException.client();
        }
        long secretId = signingSecret(client.id(), credentials.secret(), requestId);
        limits.signingApp(client.id());
        Set<String> scopes = signingScopes(client.id(), scope);
        String token = OpenApiCredentials.newToken();
        long expiresAt = Instant.now().plus(properties.tokenTtl()).toEpochMilli();
        var grant = new TokenGrant(properties.environment(), client.id(), client.clientId(), client.tokenRevision(),
                secretId, scopes, expiresAt);
        Boolean inserted = redis.opsForValue().setIfAbsent(tokenKey(token), json.writeValueAsString(grant), properties.tokenTtl());
        if (!Boolean.TRUE.equals(inserted)) {
            log.warn("开放 API 令牌存储未确认，本次签发返回 503，请检查 Redis 写入结果和连通性，requestId={} environment={} appId={}",
                    requestId, properties.environment(), client.id());
            throw OpenApiException.unavailableDependency();
        }
        log.info("开放 API 应用令牌已签发，requestId={} environment={} appId={} secretId={} scopeCount={} ttlSeconds={}",
                requestId, properties.environment(), client.id(), secretId, scopes.size(), properties.tokenTtlSeconds());
        return new TokenResponse(token, "Bearer", properties.tokenTtlSeconds(), String.join(" ", scopes));
    }

    /**
     * 读取有效的令牌快照，供后续使用主库授权状态校验本次调用。
     *
     * @param authorization Bearer 认证头
     * @return 本环境内尚未过期的授权快照
     * @throws OpenApiException 令牌缺失、无效或已过期
     */
    TokenGrant authenticate(String authorization) {
        if (authorization == null) {
            throw OpenApiException.bearer(false);
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw OpenApiException.bearer(true);
        }
        String token = authorization.substring(7);
        if (!OpenApiCredentials.validToken(token)) {
            throw OpenApiException.bearer(true);
        }
        String stored = redis.opsForValue().get(tokenKey(token));
        if (stored == null) {
            throw OpenApiException.bearer(true);
        }
        TokenGrant grant = json.readValue(stored, TokenGrant.class);
        if (grant == null || !properties.environment().equals(grant.environment()) || grant.scopes() == null
                || grant.clientId() == null || Instant.now().toEpochMilli() >= grant.expiresAt()) {
            throw OpenApiException.bearer(true);
        }
        return grant;
    }

    /**
     * 用主库状态重新检查应用、令牌版本和接口授权，通过后消耗调用配额。
     *
     * @param token        已从 Redis 读取的授权快照
     * @param registration 本次命中的接口登记
     * @param call         调用记录上下文，用于关联接口元数据
     * @throws OpenApiException 令牌、接口授权或限流检查失败
     */
    void authorize(TokenGrant token, OpenApiRegistry.Registration registration, OpenApiCalls.Context call) {
        Access access = mapper.access(token.appId(), token.clientId(), token.secretId(), registration.code(),
                properties.environment());
        if (access != null) {
            call.endpoint(access.endpointId(), registration.code());
        }
        validateToken(access, token.tokenRevision());
        validateEndpoint(access, registration, call);
        authorizeGrant(token, access);
    }

    ManagedGrant authorizeManaged(TokenGrant token, String path, OpenApiCalls.Context call) {
        Access access = mapper.managedAccess(token.appId(), token.clientId(), token.secretId(), path, properties.environment());
        if (access != null) {
            call.endpoint(access.endpointId(), access.endpointCode());
        }
        validateToken(access, token.tokenRevision());
        if (access.endpointId() == null || !"PUBLISHED".equals(access.endpointStatus())
                || !path.equals(access.pathTemplate()) || !"v1".equals(access.apiVersion())) {
            throw OpenApiException.unavailableEndpoint();
        }
        var binding = managed.matchingBinding(access.sourcePath(), access.sourceContractHash(),
                access.publicContractJson(), access.dataScopeType());
        if (binding == null) {
            log.warn("开放 API 已发布映射的来源契约不匹配，本次调用返回 404，请比对后台接口来源与当前部署的来源契约，requestId={} environment={} appId={} endpointId={} endpointCode={}",
                    call.requestId(), properties.environment(), token.appId(), access.endpointId(), access.endpointCode());
            throw OpenApiException.unavailableEndpoint();
        }
        authorizeGrant(token, access);
        return new ManagedGrant(new OpenApiRequest(token.appId(), token.clientId()), binding);
    }

    private void authorizeGrant(TokenGrant token, Access access) {
        if (!token.scopes().contains(access.endpointCode()) || !"GRANTED".equals(access.grantStatus())) {
            throw OpenApiException.scope(access.endpointCode());
        }
        if (access.appLimit() < 1 || access.appWindow() < 1) {
            throw OpenApiException.unavailableDependency();
        }
        limits.business(token.appId(), access.appLimit(), access.appWindow(), access.endpointId(), access.endpointLimit(),
                access.endpointWindow());
    }

    private static void validateToken(Access access, long revision) {
        if (access == null || !access.enabled() || access.tokenRevision() != revision || access.secretStatus() == null
                || access.tokensRevokedAt() != null || CANDIDATE.equals(access.secretStatus())
                && !access.secretExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw OpenApiException.bearer(true);
        }
    }

    private void validateEndpoint(Access access, OpenApiRegistry.Registration registration, OpenApiCalls.Context call) {
        if (access.endpointId() == null || !"PUBLISHED".equals(access.endpointStatus())) {
            throw OpenApiException.unavailableEndpoint();
        }
        if (access.sourcePath() != null || !registry.matches(registration.code(), access.httpMethod(), access.pathTemplate(), access.apiVersion(),
                access.dataScopeType())) {
            log.warn("开放 API 已发布目录与当前部署的 Controller 不一致，本次调用返回 404，请对照接口登记校正发布配置，requestId={} environment={} appId={} endpointId={} endpointCode={}",
                    call.requestId(), properties.environment(), call.appId(), access.endpointId(), registration.code());
            throw OpenApiException.unavailableEndpoint();
        }
    }

    private long signingSecret(long appId, String secret, String requestId) {
        var eligible = mapper.signingSecrets(appId, LocalDateTime.now(ZoneOffset.UTC));
        if (eligible.size() > 2) {
            log.warn("开放 API 应用可签发凭据超出并行上限，本次签发返回 503，请核对后台凭据轮换记录及有效状态，requestId={} environment={} appId={} eligibleSecretCount={} maxSecrets=2",
                    requestId, properties.environment(), appId, eligible.size());
            throw OpenApiException.unavailableDependency();
        }
        String hash = OpenApiCredentials.secretHash(secret);
        long matched = 0;
        for (var candidate : eligible) {
            if (OpenApiCredentials.equalHashes(hash, candidate.hash())) {
                matched = candidate.id();
            }
        }
        if (matched == 0) {
            throw OpenApiException.client();
        }
        return matched;
    }

    private Set<String> signingScopes(long appId, String scope) {
        Set<String> published = new TreeSet<>();
        for (var entry : mapper.publishedScopes(appId)) {
            var matches = entry.sourcePath() == null
                    ? registry.matches(entry.endpointCode(), entry.httpMethod(), entry.pathTemplate(), entry.apiVersion(), entry.dataScopeType())
                    : managed.matches(entry.pathTemplate(), entry.httpMethod(), entry.apiVersion(), entry.dataScopeType(),
                    entry.sourcePath(), entry.sourceContractHash(), entry.publicContractJson());
            if (matches) {
                published.add(entry.endpointCode());
            }
        }
        if (scope != null && scope.length() > 8192) {
            throw new OpenApiException(400, "invalid_scope", "Requested scope is invalid");
        }
        Set<String> selected = scope == null || scope.isEmpty() ? published : new TreeSet<>(Arrays.asList(scope.split(" +", -1)));
        if (selected.isEmpty() || !published.containsAll(selected)) {
            throw new OpenApiException(400, "invalid_scope", "Requested scope is unavailable");
        }
        return selected;
    }

    record ManagedGrant(OpenApiRequest caller, ManagedReadRegistry.Binding binding) {
    }

    private static Credentials credentials(String header) {
        if (header == null || header.length() > 150 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw OpenApiException.client();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException exception) {
            throw OpenApiException.client();
        }
        int separator = decoded.indexOf(':');
        if (separator == -1) {
            throw OpenApiException.client();
        }
        String id = decoded.substring(0, separator);
        String secret = decoded.substring(separator + 1);
        if (!OpenApiCredentials.validClientId(id) || !OpenApiCredentials.validSecret(secret)) {
            throw OpenApiException.client();
        }
        return new Credentials(id, secret);
    }

    private String tokenKey(String token) {
        return "flow:open-api:" + properties.environment() + ":token:" + OpenApiCredentials.digest(token);
    }

    private record Credentials(String id, String secret) {
    }

    record TokenGrant(String environment, long appId, String clientId, long tokenRevision, long secretId,
                      Set<String> scopes, long expiresAt) {
    }

    /**
     * OAuth 客户端凭据交换成功后的公开响应。
     *
     * @param accessToken 新签发的短期访问令牌，仅供该调用方使用
     * @param tokenType   固定为 {@code Bearer}
     * @param expiresIn   签发时的有效期，单位为秒
     * @param scope       以空格分隔的实际授予接口编码
     */
    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") int expiresIn, String scope) {
        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }
}
