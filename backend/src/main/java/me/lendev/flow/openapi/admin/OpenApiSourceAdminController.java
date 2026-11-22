package me.lendev.flow.openapi.admin;

import static me.lendev.flow.common.auth.PermissionCode.OPEN_API_ENDPOINT_PUBLISH;
import static me.lendev.flow.common.auth.PermissionCode.OPEN_API_VIEW;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.actor;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.permit;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import org.springframework.web.bind.annotation.*;

/**
 * 管理现有 REST 来源路由的发现、导入和契约确认，所有操作要求开放 API 查看权限。
 */
@RestController
@RequestMapping(OpenApiAdminRoutes.ROOT)
@RequiredArgsConstructor
public class OpenApiSourceAdminController {
    private final OpenApiManagedReadAdmin managed;

    /**
     * 查询来源路由及导入条件，可按当前部署的导入资格筛选。
     *
     * @param importable 指定导入资格，省略时返回全部来源
     * @return 符合筛选条件的来源接口
     */
    @GetMapping("/endpoint-sources")
    public R<List<EndpointSource>> endpointSources(@RequestParam(required = false) Boolean importable) {
        permit(OPEN_API_VIEW);
        return R.ok(managed.sources(importable));
    }

    /**
     * 将已审查的只读来源导入为待发布接口。
     *
     * @param request 来源路径、公开路径、接口编码及可选的公开字段
     * @return 新登记的接口及其来源契约
     */
    @PostMapping("/endpoints/import")
    public R<Endpoint> importEndpoint(@Valid @RequestBody ImportEndpoint request) {
        permit(OPEN_API_ENDPOINT_PUBLISH);
        return R.ok(managed.importEndpoint(request, actor()).response());
    }

    /**
     * 重新确认当前部署中的来源契约。
     *
     * @param endpointId 待确认契约的接口 ID
     * @param request    操作依据的接口版本
     * @return 确认后持久化的接口信息
     */
    @PutMapping("/endpoints/{endpointId}/source-contract")
    public R<Endpoint> confirmSource(@PathVariable @Positive long endpointId, @Valid @RequestBody ConfirmSourceContract request) {
        permit(OPEN_API_ENDPOINT_PUBLISH);
        return R.ok(managed.confirmSource(endpointId, request, actor()).response());
    }

}
