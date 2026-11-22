package me.lendev.flow.pms.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.pms.domain.dto.ResourceCatalogDto;
import me.lendev.flow.pms.service.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为超级管理员和已获接口授权的应用提供资源目录查询。
 */
@RestController
@RequestMapping("/permission/catalog")
@EndpointGrantRead("permission")
@RequiredArgsConstructor
@Tag(name = "资源目录")
public class PermissionCatalogController {

    private final PermissionService permissions;

    /**
     * 分页读取资源目录。
     *
     * @param query 可选的分页条件
     * @return 按资源 ID 升序排列的当前页与总数
     */
    @GetMapping
    @Operation(summary = "查询资源目录")
    public R<Page<ResourceCatalogDto>> page(@Valid Query query) {
        permitRead();
        return R.ok(permissions.catalogPage(query.pageNumber(), query.size()));
    }

    /**
     * 读取指定资源的目录信息。
     *
     * @param id 资源 ID
     * @return 资源目录项
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询资源目录详情")
    public R<ResourceCatalogDto> get(@PathVariable @Positive long id) {
        permitRead();
        return R.ok(permissions.catalogEntry(id));
    }

    private static void permitRead() {
        if (ManagedReadInvocation.callerForModule(PermissionCatalogController.class) == null) {
            StpUtil.checkRole(RoleType.SUPER_ADMIN);
        }
    }

    /**
     * 资源目录分页条件，默认第一页且每页 20 条。
     */
    public record Query(@Positive Integer pageNo, @Positive @Max(100) Integer pageSize) {

        int pageNumber() {
            return pageNo == null ? 1 : pageNo;
        }

        int size() {
            return pageSize == null ? 20 : pageSize;
        }
    }
}
