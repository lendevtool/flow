package me.lendev.flow.pms.directory;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.pms.directory.UserDirectoryModels.Entry;
import me.lendev.flow.pms.directory.UserDirectoryModels.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供用户工作目录的只读查询，供管理员和获得相应接口授权的应用使用。
 */
@RestController
@RequestMapping("/user/directory/entries")
@EndpointGrantRead("user")
@RequiredArgsConstructor
@Tag(name = "用户工作目录")
public class UserDirectoryController {

    private final UserDirectoryService service;

    /**
     * 分页查询工作目录，按用户 ID 升序返回。
     */
    @GetMapping
    @Operation(summary = "分页查询用户工作目录")
    public R<Page<Entry>> page(@Valid Query query) {
        permitRead();
        return R.ok(service.page(query));
    }

    /**
     * 按用户目录 ID 查询指定用户的工作信息。
     */
    @GetMapping("/{id}")
    @Operation(summary = "按 ID 查询用户工作目录条目")
    public R<Entry> get(@PathVariable @Positive long id) {
        permitRead();
        return R.ok(service.get(id));
    }

    private static void permitRead() {
        if (ManagedReadInvocation.callerForModule(UserDirectoryController.class) == null) {
            StpUtil.checkRole(RoleType.SUPER_ADMIN);
        }
    }
}
