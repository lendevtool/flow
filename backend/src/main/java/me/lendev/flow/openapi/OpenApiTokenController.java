package me.lendev.flow.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 客户端凭据签发入口。调用方使用 HTTP Basic 提交应用标识和凭据，
 * 成功返回 OAuth 令牌 JSON，拒绝时返回 OAuth 错误响应。
 */
@RestController
@RequiredArgsConstructor
class OpenApiTokenController {
    private final OpenApiAuthorization authorization;

    /**
     * 用应用凭据换取短期令牌。参数通过 {@code application/x-www-form-urlencoded} 请求体提交。
     *
     * @param request 当前 HTTP 请求，用于读取唯一的 Authorization 头并拒绝 URL 查询参数
     * @param form    签发表单，必须包含单个 {@code grant_type=client_credentials}，可选单个 {@code scope}，
     *                省略范围时使用应用当前获准的可用接口，应用凭据通过 HTTP Basic 提交
     * @return Bearer 令牌、以秒计的有效期和最终获准的接口范围
     * @throws OpenApiException 表单、授权类型或范围不合法时返回 400，应用凭据无效时返回
     *                          401 {@code invalid_client}，依赖故障时返回 503 {@code temporarily_unavailable}
     */
    @Operation(summary = "Issue an application access token", security = @SecurityRequirement(name = "clientBasic"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                            schema = @Schema(implementation = TokenForm.class))))
    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OpenApiAuthorization.TokenResponse issue(HttpServletRequest request,
                                                    @Parameter(hidden = true) @RequestBody(required = false) MultiValueMap<String, String> form) {
        if (request.getQueryString() != null || form != null && (form.containsKey("client_id")
                || form.containsKey("client_secret") || multiple(form, "grant_type") || multiple(form, "scope"))) {
            throw new OpenApiException(400, "invalid_request", "Invalid token request form");
        }
        return authorization.issue(OpenApiTrafficFilter.authorizationHeader(request),
                form == null ? null : form.getFirst("grant_type"), form == null ? null : form.getFirst("scope"),
                OpenApiResponses.requestId(request));
    }

    private static boolean multiple(MultiValueMap<String, String> form, String key) {
        return form.containsKey(key) && form.get(key).size() > 1;
    }

    /**
     * OAuth 令牌签发的表单契约。
     *
     * @param grantType 必填授权类型，固定为 {@code client_credentials}
     * @param scope     可选的空格分隔接口编码，省略时取应用当前获准的可用接口
     */
    record TokenForm(@JsonProperty("grant_type") @Schema(allowableValues = "client_credentials",
            requiredMode = Schema.RequiredMode.REQUIRED) String grantType, String scope) {
    }
}
