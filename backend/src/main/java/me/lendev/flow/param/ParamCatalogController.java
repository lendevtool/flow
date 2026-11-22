package me.lendev.flow.param;

import static me.lendev.flow.common.auth.PermissionCode.PARAM_MGT;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.param.ParamCatalogModels.Group;
import me.lendev.flow.param.ParamCatalogModels.Item;
import me.lendev.flow.param.ParamCatalogModels.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供有读取权限的员工和应用查询参数目录。
 */
@RestController
@RequestMapping("/param/catalog/groups")
@EndpointGrantRead("param")
@RequiredArgsConstructor
@Tag(name = "参数目录")
public class ParamCatalogController {
    private final ParamService service;

    /**
     * 分页查询参数分类的元数据。
     */
    @GetMapping
    public R<Page<Group>> groups(@Valid Query query) {
        permitRead();
        return R.ok(service.catalogGroups(query.toPageRequest()));
    }

    /**
     * 按分类 ID 查询元数据。
     */
    @GetMapping("/{groupId}")
    public R<Group> group(@PathVariable @Positive long groupId) {
        permitRead();
        return R.ok(service.catalogGroup(groupId));
    }

    /**
     * 分页查询指定分类下的参数明细元数据。
     */
    @GetMapping("/{groupId}/items")
    public R<Page<Item>> items(@PathVariable @Positive long groupId, @Valid Query query) {
        permitRead();
        return R.ok(service.catalogItems(groupId, query.toPageRequest()));
    }

    /**
     * 查询指定分类内单条参数明细的元数据。
     */
    @GetMapping("/{groupId}/items/{id}")
    public R<Item> item(@PathVariable @Positive long groupId, @PathVariable @Positive long id) {
        permitRead();
        return R.ok(service.catalogItem(groupId, id));
    }

    private static void permitRead() {
        if (ManagedReadInvocation.callerForModule(ParamCatalogController.class) == null) {
            ParamController.permit(PARAM_MGT);
        }
    }
}
