package me.lendev.flow.audit;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import me.lendev.flow.common.auth.rate.TooManyRequestsException;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.pms.notificationgroup.NotificationGroupFailure;
import org.springframework.dao.DataAccessException;

/**
 * 审计错误事实：仅收录应用可控的稳定文案，绝不复制 {@code Throwable.getMessage()}， 避免底层异常细节泄露进审计记录。
 *
 * @param code         稳定的错误分类码
 * @param businessCode 业务错误码，无则为 {@code null}
 * @param message      面向审计展示的中文描述
 */
public record AuditError(String code, String businessCode, String message) {

    private static final String CONFLICT = "CONFLICT";

    /**
     * 将异常归类为稳定的审计错误事实。
     *
     * @param error 业务或基础设施抛出的异常
     * @return 仅含固定文案的错误事实；未识别类型统一归为 OPERATION_FAILED
     */
    static AuditError from(Throwable error) {
        if (error instanceof AuditResultMismatchException) {
            return new AuditError("AUDIT_RESULT_MISMATCH", "500", "流程审计配置异常，操作未提交");
        }
        if (error instanceof BizException biz) {
            return new AuditError("BUSINESS_REJECTED", String.valueOf(biz.getCode().getCode()), biz.getCode().getMsg());
        }
        if (error instanceof me.lendev.flow.common.auth.mfa.MfaUnavailableException) {
            return new AuditError("MFA_UNAVAILABLE", "503", "MFA 服务不可用");
        }
        if (error instanceof me.lendev.flow.common.auth.domain.DomainUnavailableException) {
            return new AuditError("DOMAIN_UNAVAILABLE", "503", "域认证服务不可用");
        }
        if (error instanceof me.lendev.flow.pms.department.MembershipConflictException) {
            return new AuditError(CONFLICT, "409", "部门归属冲突");
        }
        if (error instanceof NotificationGroupFailure notificationGroup) {
            return notificationGroupError(notificationGroup);
        }
        if (error instanceof me.lendev.flow.policy.PolicyConflictException) {
            return new AuditError(CONFLICT, "409", "策略版本冲突");
        }
        if (error instanceof me.lendev.flow.calendar.TradingCalendarConflictException) {
            return new AuditError(CONFLICT, "409", "交易日历预览已失效");
        }
        if (error instanceof me.lendev.flow.workbench.todo.TodoConflictException) {
            return new AuditError(CONFLICT, "409", "工作台数据发生冲突");
        }
        if (error instanceof me.lendev.flow.document.DocumentFailure document) {
            return switch (document.getStatus()) {
                case CONFLICT -> new AuditError(CONFLICT, "409", "文档归属、版本或状态冲突");
                case NOT_FOUND -> new AuditError("NOT_FOUND", "404", "文档或文件版本不存在");
                case SERVICE_UNAVAILABLE -> new AuditError("DOCUMENT_STORAGE_UNAVAILABLE", "503", "文档存储操作未完成");
                default ->
                        new AuditError("DOCUMENT_REJECTED", String.valueOf(document.getStatus().value()), "文档操作被拒绝");
            };
        }
        if (error instanceof BadRequestException) {
            return new AuditError("BAD_REQUEST", "400", "请求未通过业务校验");
        }
        if (error instanceof TooManyRequestsException) {
            return new AuditError("RATE_LIMITED", null, "请求过于频繁");
        }
        if (error instanceof NotLoginException) {
            return new AuditError("UNAUTHENTICATED", null, "登录凭证无效");
        }
        if (error instanceof NotPermissionException) {
            return new AuditError("FORBIDDEN", null, "无操作权限");
        }
        return fromInfrastructureFailure(error);
    }

    /**
     * 将通知群组和场景的业务拒绝转换为固定审计分类，不保留原始请求参数。
     */
    private static AuditError notificationGroupError(NotificationGroupFailure failure) {
        return switch (failure.status()) {
            case CONFLICT -> new AuditError(CONFLICT, "409", "通知群组或通知场景配置冲突");
            case NOT_FOUND -> new AuditError("NOT_FOUND", "404", "通知群组或通知场景不存在");
            default -> new AuditError("BAD_REQUEST", "400", "通知群组或通知场景配置无效");
        };
    }

    /**
     * 将基础设施异常归类为稳定的错误事实，未识别类型统一归为 OPERATION_FAILED。
     */
    private static AuditError fromInfrastructureFailure(Throwable error) {
        if (error instanceof org.springframework.data.redis.RedisConnectionFailureException
                || error instanceof io.lettuce.core.RedisException) {
            return new AuditError("REDIS_UNAVAILABLE", null, "Redis操作结果未能确认");
        }
        if (error instanceof DataAccessException) {
            return new AuditError("DATABASE_ERROR", null, "数据库操作未完成");
        }
        return new AuditError("OPERATION_FAILED", null, "操作未完成");
    }
}
