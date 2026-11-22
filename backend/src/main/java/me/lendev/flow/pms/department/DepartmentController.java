package me.lendev.flow.pms.department;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.pms.department.DepartmentModels.*;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import org.springframework.web.bind.annotation.*;

/**
 * 组织架构管理接口。
 */
@RestController
@RequestMapping(DepartmentController.ROOT)
@RequiredArgsConstructor
@Tag(name = "组织架构")
public class DepartmentController {

    static final String ROOT = "/department";

    private final DepartmentService service;

    @GetMapping("/members")
    public R<Page<Member>> members(@Valid MemberQuery query) {
        permit(DEPARTMENT_VIEW);
        return R.ok(service.members(query));
    }

    @GetMapping("/users")
    public R<Page<Member>> candidates(@Valid MemberQuery query) {
        permit(DEPARTMENT_VIEW);
        return R.ok(service.candidates(query));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody DepartmentRequest request) {
        permit(DEPARTMENT_EDIT);
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        permit(DEPARTMENT_EDIT);
        service.update(id, request);
        return R.ok();
    }

    @PutMapping("/{id}/leader")
    public R<Void> setLeader(@PathVariable Long id, @Valid @RequestBody LeaderRequest request) {
        permit(DEPARTMENT_LEADER);
        service.setLeader(id, request);
        return R.ok();
    }

    @PutMapping("/members")
    public R<Void> moveMembers(@Valid @RequestBody MembersRequest request) {
        permit(DEPARTMENT_MEMBERS);
        service.moveMembers(request);
        return R.ok();
    }

    /**
     * 获取指定用户的全部部门归属与编辑版本。
     */
    @GetMapping("/users/{userId}/memberships")
    public R<Memberships> memberships(@PathVariable Long userId) {
        permit(DEPARTMENT_VIEW);
        return R.ok(service.memberships(userId));
    }

    /**
     * 在版本一致时替换指定用户的全部部门归属。
     */
    @PutMapping("/users/{userId}/memberships")
    public R<Void> replaceMemberships(@PathVariable Long userId, @Valid @RequestBody ReplaceMembershipsRequest request) {
        permit(DEPARTMENT_MEMBERS);
        service.replaceMemberships(userId, request);
        return R.ok();
    }

    /**
     * 处理明确指定的批量新增、退出或调动。
     */
    @PostMapping("/member-changes")
    public R<Void> changeMembers(@Valid @RequestBody MemberChangeRequest request) {
        permit(DEPARTMENT_MEMBERS);
        switch (request.action()) {
            case ADD -> service.addMembers(request);
            case REMOVE -> service.removeMembers(request);
            case TRANSFER -> service.transferMembers(request);
        }
        return R.ok();
    }

    static void permit(String code) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(DEPARTMENT_VIEW);
            if (!DEPARTMENT_VIEW.equals(code)) {
                StpUtil.checkPermission(code);
            }
        }
    }
}
