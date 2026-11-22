package me.lendev.flow.openapi.admin;

import static me.lendev.flow.common.auth.PermissionCode.OPEN_API_VIEW;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.permit;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 提供已发布开放接口的契约下载，要求员工拥有开放 API 查看权限，响应禁止缓存。
 */
@RestController
@RequestMapping(OpenApiAdminRoutes.ROOT)
@RequiredArgsConstructor
public class OpenApiAdminDocumentsController {
    private final OpenApiAdminDocuments documents;

    /**
     * 下载当前已发布的 v1 开放接口契约。
     *
     * @param locale 请求协商得到的文档语言
     * @return 以 JSON 附件返回的公开接口契约，包含接口级授权要求及响应结构
     */
    @GetMapping(OpenApiAdminRoutes.DOCS_V1)
    public ResponseEntity<JsonNode> docs(Locale locale) {
        permit(OPEN_API_VIEW);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("open-api-v1.json").build().toString())
                .body(documents.document(locale));
    }

}
