package me.lendev.flow.document;

import static me.lendev.flow.common.auth.PermissionCode.DOCUMENT_CENTER_VIEW;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询文档中心共用的部门、产品和维度选项。
 */
@RestController
@RequestMapping(DocumentController.ROOT_PATH)
@RequiredArgsConstructor
@EndpointGrantRead("document")
public class DocumentOptionsController {

    private final DocumentDimensions dimensions;

    /**
     * 返回全局维度配置，供员工和已获得接口授权的应用查询。
     *
     * @return 部门、产品和维度选项
     */
    @GetMapping("/options")
    public R<DocumentDimensions.Options> options() {
        if (ManagedReadInvocation.callerForModule(DocumentOptionsController.class) == null) {
            DocumentAccess.permit(DOCUMENT_CENTER_VIEW);
        }
        return R.ok(dimensions.options());
    }
}
