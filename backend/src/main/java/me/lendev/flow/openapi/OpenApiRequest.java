package me.lendev.flow.openapi;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 已通过当前接口授权校验的应用身份。
 */
public record OpenApiRequest(long appId, String clientId) {
    private static final String ATTRIBUTE = OpenApiRequest.class.getName();

    static void establish(HttpServletRequest request, long appId, String clientId) {
        request.setAttribute(ATTRIBUTE, new OpenApiRequest(appId, clientId));
    }

    /**
     * 取得专用开放接口拦截器在完成鉴权、授权和限流校验后设置的身份。
     *
     * @param request 当前正在处理的 HTTP 请求
     * @return 受信任的应用主键和客户端标识
     * @throws OpenApiException 当前请求尚未通过专用开放接口校验
     */
    public static OpenApiRequest requireCurrent(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof OpenApiRequest authenticated) {
            return authenticated;
        }
        throw OpenApiException.bearer(false);
    }
}
