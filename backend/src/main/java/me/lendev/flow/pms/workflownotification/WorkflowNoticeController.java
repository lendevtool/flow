package me.lendev.flow.pms.workflownotification;

import static me.lendev.flow.common.auth.PermissionCode.NOTIFICATION_GROUP_VIEW;
import static me.lendev.flow.common.auth.PermissionCode.WORKFLOW_NOTIFICATION_DELIVERY_VIEW;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.DeliveryRow;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.Detail;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.Query;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为通知群组管理员提供流程投递查询。
 */
@RestController
@RequestMapping("/notification-groups/workflow-deliveries")
@Tag(name = "流程通知投递")
@RequiredArgsConstructor
public class WorkflowNoticeController {

    private final WorkflowNoticeQueries queries;
    private final MessageSource messages;

    /**
     * 按流程、场景、状态与创建时间分页查询。
     */
    @GetMapping
    public R<Page<DeliveryRow>> list(@Valid Query filter) {
        permit();
        return R.ok(queries.list(filter));
    }

    /**
     * 查询一个投递及其收件人渠道状态。
     */
    @GetMapping("/{id}")
    public ResponseEntity<R<Detail>> detail(@PathVariable @Positive long id, Locale locale) {
        permit();
        var detail = queries.detail(id);
        if (detail != null) {
            return ResponseEntity.ok(R.ok(detail));
        }
        return ResponseEntity.status(404).body(new R<Detail>().setCode(404)
                .setMessage(messages.getMessage("error.workflow-notification.delivery-not-found", null, locale)));
    }

    private static void permit() {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(NOTIFICATION_GROUP_VIEW);
            StpUtil.checkPermission(WORKFLOW_NOTIFICATION_DELIVERY_VIEW);
        }
    }
}
