package me.lendev.flow.pms.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.pms.domain.dto.RoleCatalogDto;
import me.lendev.flow.pms.service.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为超级管理员和已获接口授权的应用提供角色目录查询。
 */
@RestController
@RequestMapping("/role/catalog")
@EndpointGrantRead("role")
@RequiredArgsConstructor
@Tag(name = "角色目录")
public class RoleCatalogController {

    private final RoleService roles;

    /**
     * 分页读取角色目录，可按中英文名称和启用状态筛选。
     *
     * @param query 可选的分页与筛选条件
     * @return 按角色 ID 升序排列的当前页与总数
     */
    @GetMapping
    @Operation(summary = "查询角色目录")
    public R<Page<RoleCatalogDto>> page(@Valid Query query) {
        permitRead();
        return R.ok(roles.catalogPage(query.name(), query.enable(), query.pageNumber(), query.size()));
    }

    /**
     * 读取指定角色的基本信息。
     *
     * @param id 角色 ID
     * @return 角色目录项
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询角色目录详情")
    public R<RoleCatalogDto> get(@PathVariable @Positive long id) {
        permitRead();
        return R.ok(roles.catalogEntry(id));
    }

    private static void permitRead() {
        if (ManagedReadInvocation.callerForModule(RoleCatalogController.class) == null) {
            StpUtil.checkRole(RoleType.SUPER_ADMIN);
        }
    }

    /**
     * 角色目录查询条件，默认第一页且每页 20 条。
     */
    public record Query(@Size(max = 100) String name, Boolean enable, @Positive Integer pageNo,
                        @Positive @Max(100) Integer pageSize) {

        int pageNumber() {
            return pageNo == null ? 1 : pageNo;
        }

        int size() {
            return pageSize == null ? 20 : pageSize;
        }
    }
}
