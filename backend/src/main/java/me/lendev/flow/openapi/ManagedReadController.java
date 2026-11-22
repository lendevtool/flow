package me.lendev.flow.openapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 提供由后台发布的只读映射，调用身份由应用 Bearer 令牌确认。
 * 映射来源由已校验的目录记录决定，成功时返回发布契约规定的 JSON，失败时返回开放 API 的错误响应。
 */
@RestController
@RequiredArgsConstructor
class ManagedReadController {
    private final OpenApiAuthorization authorization;
    private final ManagedReadInvocation invocation;

    /**
     * 调用一个已发布的只读映射，按该入口绑定的来源契约校验业务查询参数并投影响应。
     *
     * @param slug    必填路径变量，指向唯一的已发布映射
     * @param request 当前 HTTP 请求，用于读取应用令牌及该映射声明的查询参数
     * @return 发布契约允许的 JSON 字段，分页、排序和空结果语义由该映射的契约定义
     * @throws OpenApiException 映射不存在或来源契约失配时返回 404 {@code ENDPOINT_UNAVAILABLE}，
     *                          凭据或授权失效时返回 401 {@code INVALID_TOKEN} 或 403 {@code INSUFFICIENT_SCOPE}
     * @throws Exception        来源调用异常，由开放 API 异常处理统一转换为对应的 HTTP 响应
     */
    @GetMapping(value = ManagedReadRegistry.PATH_PREFIX + "{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode read(@PathVariable String slug, HttpServletRequest request) throws Exception {
        String path = ManagedReadRegistry.PATH_PREFIX + slug;
        var call = OpenApiTrafficFilter.call(request);
        if (call == null || !"GET".equals(request.getMethod()) || !ManagedReadRegistry.isPublicPath(path)
                || !(request.getContextPath() + path).equals(request.getRequestURI())) {
            throw OpenApiException.unavailableEndpoint();
        }
        var token = authorization.authenticate(OpenApiTrafficFilter.authorizationHeader(request));
        call.application(token.appId(), token.clientId());
        return invocation.read(request, authorization.authorizeManaged(token, path, call));
    }
}
