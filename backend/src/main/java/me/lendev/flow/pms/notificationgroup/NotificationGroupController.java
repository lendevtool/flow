package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.common.auth.PermissionCode.NOTIFICATION_GROUP_EDIT;
import static me.lendev.flow.common.auth.PermissionCode.NOTIFICATION_GROUP_VIEW;
import static me.lendev.flow.common.auth.PermissionCode.NOTIFICATION_SCENE_RECIPIENT_EDIT;
import static me.lendev.flow.common.auth.PermissionCode.WORKFLOW_NOTIFICATION_SCENE_EDIT;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;

import java.util.List;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import org.springframework.web.bind.annotation.*;

/**
 * 通知群组配置、收件人预览与场景绑定管理接口。
 */
@RestController
@RequestMapping("/notification-groups")
@RequiredArgsConstructor
@Tag(name = "通知群组")
public class NotificationGroupController {

    private final NotificationGroupService service;
    private final WorkflowSceneService workflowScenes;

    /**
     * 分页查询群组配置摘要。
     */
    @GetMapping
    public R<Page<GroupListItem>> list(@Valid Query query) {
        permit(NOTIFICATION_GROUP_VIEW);
        return R.ok(service.list(query));
    }

    /**
     * 回显配置来源，包括已停用或已删除的来源。
     */
    @GetMapping("/{id}")
    public R<GroupDetail> detail(@PathVariable @Positive Long id) {
        permit(NOTIFICATION_GROUP_VIEW);
        return R.ok(service.detail(id));
    }

    /**
     * 创建默认启用的群组。
     */
    @PostMapping
    public R<GroupDetail> create(@Valid @RequestBody CreateGroupRequest request) {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.createGroup(request));
    }

    /**
     * 校验版本并保存群组名称、说明与收件人来源。
     */
    @PutMapping("/{id}")
    public R<GroupDetail> update(@PathVariable @Positive Long id, @Valid @RequestBody UpdateGroupRequest request) {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.updateGroup(id, request));
    }

    /**
     * 启用或停用群组；停用前必须解除绑定。
     */
    @PutMapping("/{id}/status")
    public R<GroupDetail> status(@PathVariable @Positive Long id, @Valid @RequestBody GroupStatusRequest request) {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.setGroupEnabled(id, request));
    }

    /**
     * 按当前组织架构分页预览已保存群组的收件人。
     */
    @GetMapping("/{id}/recipients")
    public R<Page<UserOption>> recipients(@PathVariable @Positive Long id, @Valid Query query) {
        permit(NOTIFICATION_GROUP_VIEW);
        return R.ok(service.recipients(id, query));
    }

    /**
     * 分页预览草稿的收件人，不写入配置。
     */
    @PostMapping("/preview")
    public R<Page<UserOption>> preview(@Valid @RequestBody PreviewRequest request) {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.preview(request));
    }

    /**
     * 分页查询引用群组的场景。
     */
    @GetMapping("/{id}/usages")
    public R<Page<SceneOption>> usages(@PathVariable @Positive Long id, @Valid Query query) {
        permit(NOTIFICATION_GROUP_VIEW);
        return R.ok(service.usages(id, query));
    }

    /**
     * 分页搜索可配置的启用账号。
     */
    @GetMapping("/candidates/users")
    public R<Page<UserOption>> candidates(@Valid Query query) {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.userCandidates(query));
    }

    /**
     * 获取启用部门节点，供客户端按父节点构造选择树。
     */
    @GetMapping("/candidates/departments")
    public R<List<DepartmentOption>> departments() {
        permit(NOTIFICATION_GROUP_EDIT);
        return R.ok(service.departmentCandidates());
    }

    /**
     * 分页搜索可绑定的启用群组。
     */
    @GetMapping("/options")
    public R<Page<GroupOption>> options(@Valid Query query) {
        permit(NOTIFICATION_SCENE_RECIPIENT_EDIT);
        return R.ok(service.options(query));
    }

    /**
     * 分页查询已登记的场景。
     */
    @GetMapping("/scenes")
    public R<Page<SceneListItem>> scenes(@Valid Query query) {
        permitSceneRead();
        return R.ok(service.scenes(query));
    }

    /**
     * 获取场景版本与群组绑定。
     */
    @GetMapping("/scenes/{sceneCode}")
    public R<SceneDetail> scene(@PathVariable @Pattern(regexp = SCENE_CODE_REGEX) String sceneCode) {
        permitSceneRead();
        return R.ok(service.sceneDetail(sceneCode));
    }

    /**
     * 创建可绑定多个通知群组的流程附加场景。
     */
    @PostMapping("/scenes/workflow")
    public R<SceneDetail> createWorkflowScene(@Valid @RequestBody WorkflowSceneService.Write request) {
        permit(WORKFLOW_NOTIFICATION_SCENE_EDIT);
        return R.ok(workflowScenes.create(request));
    }

    /**
     * 编辑附加场景，或设置系统办理提醒的可选邮件模板。
     */
    @PutMapping("/scenes/workflow/{sceneCode}")
    public R<SceneDetail> updateWorkflowScene(@PathVariable @Pattern(regexp = SCENE_CODE_REGEX) String sceneCode,
                                              @Valid @RequestBody WorkflowSceneService.Write request) {
        permit(WORKFLOW_NOTIFICATION_SCENE_EDIT);
        return R.ok(workflowScenes.update(sceneCode, request));
    }

    /**
     * 查询停用场景会影响的已发布模板版本和运行实例数量。
     */
    @GetMapping("/scenes/workflow/{sceneCode}/usages")
    public R<WorkflowSceneService.Usages> workflowSceneUsages(
            @PathVariable @Pattern(regexp = SCENE_CODE_REGEX) String sceneCode) {
        permit(WORKFLOW_NOTIFICATION_SCENE_EDIT);
        return R.ok(workflowScenes.usages(sceneCode));
    }

    /**
     * 获取流程场景可选的邮件模板标识。
     */
    @GetMapping("/scenes/workflow/mail-templates")
    public R<List<WorkflowSceneService.MailOption>> workflowMailTemplates() {
        permit(WORKFLOW_NOTIFICATION_SCENE_EDIT);
        return R.ok(workflowScenes.mailTemplates());
    }

    /**
     * 校验版本后整组替换场景绑定，允许清空。
     */
    @PutMapping("/scenes/{sceneCode}/groups")
    public R<SceneDetail> replaceSceneGroups(@PathVariable @Pattern(regexp = SCENE_CODE_REGEX) String sceneCode,
                                             @Valid @RequestBody ReplaceSceneGroupsRequest request) {
        permit(NOTIFICATION_SCENE_RECIPIENT_EDIT);
        return R.ok(service.replaceSceneGroups(sceneCode, request));
    }

    private static void permit(String code) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(NOTIFICATION_GROUP_VIEW);
            if (!NOTIFICATION_GROUP_VIEW.equals(code)) {
                StpUtil.checkPermission(code);
            }
        }
    }

    private static void permitSceneRead() {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(NOTIFICATION_GROUP_VIEW);
            StpUtil.checkPermissionOr(NOTIFICATION_SCENE_RECIPIENT_EDIT, WORKFLOW_NOTIFICATION_SCENE_EDIT);
        }
    }
}
