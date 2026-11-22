package me.lendev.flow.audit;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.query.*;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.PermissionCode;
import me.lendev.flow.common.request.PageRequest;
import me.lendev.flow.common.response.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 操作审计管理端 REST 接口：提供日志查询、详情查看、CSV 导出、队列状态与人工重试能力。
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@Tag(name = "操作审计")
public class AuditController {

    private final AuditQueryService queries;
    private final AuditReadService reads;
    private final AuditExportService exports;
    private final AuditAdminService admin;

    /**
     * 权限校验：超级管理员直接放行；其他用户须先具备 {@code AUDIT_VIEW}， 再按当前接口要求的按钮权限二次校验。
     *
     * @param button 当前接口要求的按钮权限码
     */
    private void permit(String button) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(PermissionCode.AUDIT_VIEW);
            if (!PermissionCode.AUDIT_VIEW.equals(button)) {
                StpUtil.checkPermission(button);
            }
        }
    }

    /**
     * 分页查询操作审计日志摘要。
     */
    @GetMapping("/logs")
    public R<Page<Map<String, Object>>> logs(@Validated AuditQueryRequest request) {
        permit(PermissionCode.AUDIT_VIEW);
        return R.ok(queries.logs(request));
    }

    /**
     * 查看单条审计日志详情及对象变更明细，查看动作本身记录审计。
     */
    @GetMapping("/logs/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id, @Validated PageRequest request) {
        permit(PermissionCode.AUDIT_DETAIL);
        return R.ok(reads.detail(id, request));
    }

    /**
     * 按筛选条件导出审计日志 CSV（上限 5000 条），表头语言随请求 Locale 变化，导出动作记录审计。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> export(@Validated AuditQueryRequest request, Locale locale) {
        permit(PermissionCode.AUDIT_EXPORT);
        var download = exports.export(request, locale);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=operation-audit.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag())
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(download.content());
    }

    /**
     * 提供查询界面所需的操作与模块下拉选项。
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        permit(PermissionCode.AUDIT_VIEW);
        return R.ok(queries.options());
    }

    /**
     * 查询审计队列各状态积压数量与最早未完成任务时间。
     */
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        permit(PermissionCode.AUDIT_VIEW);
        return R.ok(queries.status());
    }

    /**
     * 分页查询 outbox 队列记录，默认仅返回 RETRY/DEAD。
     */
    @GetMapping("/outbox")
    public R<Page<Map<String, Object>>> outbox(@Validated PageRequest request, @RequestParam(required = false) String status) {
        permit(PermissionCode.AUDIT_RETRY);
        return R.ok(queries.outbox(request, status));
    }

    /**
     * 人工重试一条 RETRY/DEAD 队列记录，须携带乐观锁版本号，重试动作记录审计。
     */
    @PostMapping("/outbox/{id}/retry")
    public R<Void> retry(@PathVariable Long id, @Validated @RequestBody AuditRetryRequest request) {
        permit(PermissionCode.AUDIT_RETRY);
        admin.retry(id, request);
        return R.ok();
    }
}
