package me.lendev.flow.openapi.admin;

/**
 * 管理端的应用内路由，供 HTTP 映射和接口目录的文档链接复用。
 */
final class OpenApiAdminRoutes {
    static final String ROOT = "/admin/open-api";
    static final String DOCS_V1 = "/docs/v1";

    private OpenApiAdminRoutes() {
    }
}
