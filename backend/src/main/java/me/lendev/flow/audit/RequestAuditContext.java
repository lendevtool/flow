package me.lendev.flow.audit;

import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;
import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.validSceneCode;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.common.auth.SaTokenConfigure;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.department.DepartmentModels.MemberChangeRequest;
import me.lendev.flow.pms.department.DepartmentModels.MembersRequest;
import me.lendev.flow.pms.department.DepartmentModels.VersionedUser;
import me.lendev.flow.pms.mapper.*;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 基于 HTTP 请求上下文的 {@code AuditContext} 实现：生成请求级审计作用域， 并从 Sa-Token 会话固定操作者身份；无请求上下文时回退到 {@code AuditSystemContext}。
 */
@Component
@RequiredArgsConstructor
public class RequestAuditContext implements AuditContext {

    private static final String REQUEST_ID = RequestAuditContext.class.getName() + ".requestId";
    private static final String SYSTEM_POLICY = "SYSTEM_POLICY";
    private static final String DOCUMENT = "DOCUMENT";
    private final UserMapper users;
    private final ProfileMapper profiles;
    private final RoleMapper roles;
    private final Clock clock;

    /**
     * 生成请求级审计作用域：固定操作者身份、来源 IP、请求方法与路径，并提取安全的目标对象引用。
     *
     * <p>LOGIN/MFA_LOGIN 操作以匿名身份开启，登录成功后再由外部执行器固定真实身份；
     * 无 HTTP 请求上下文时来源记为 SYSTEM。请求 ID 首次生成后写入请求属性与响应头，供全链路关联。
     */
    @Override
    public AuditScope start(AuditOperation operation, Object[] args) {
        var attributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes servlet = attributes instanceof ServletRequestAttributes value ? value : null;
        HttpServletRequest request = servlet == null ? null : servlet.getRequest();
        String requestId = requestId(servlet, request);
        String claimed = operation == AuditOperation.LOGIN && args.length > 0 && args[0] instanceof LoginRequest login
                ? clean(login.getUsername(), 64) : null;
        Object path = request == null ? null : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return AuditScope.builder().operationId(operationId(operation, args)).requestId(requestId)
                .startedAt(clock.instant()).startedNanos(System.nanoTime())
                .actor((operation == AuditOperation.LOGIN || operation == AuditOperation.MFA_LOGIN)
                        ? AuditActor.anonymous() : currentActor())
                .claimedAccount(claimed).sourceIp(request == null ? null : clean(request.getRemoteAddr(), 64))
                .requestMethod(request == null ? null : clean(request.getMethod(), 16))
                .requestPath(path == null ? null : clean(path.toString(), 512))
                .sourceType(request == null ? "SYSTEM" : "API").targets(targets(operation, args)).build();
    }

    /**
     * 计算操作 ID：文档清理复用父操作 ID 以关联同一清理链路，其余操作生成新的 UUID。
     */
    private static String operationId(AuditOperation operation, Object[] args) {
        if (operation == AuditOperation.DOCUMENT_CLEANUP && args.length > 2
                && args[2] instanceof String parentOperationId) {
            return parentOperationId;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 读取或生成请求级 ID：首次生成后缓存到请求属性并写入 X-Request-ID 响应头，同一请求内复用。
     */
    private String requestId(ServletRequestAttributes servlet, HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String requestId = (String) request.getAttribute(REQUEST_ID);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, requestId);
            var response = servlet.getResponse();
            if (response != null) {
                response.setHeader("X-Request-ID", requestId);
            }
        }
        return requestId;
    }

    /**
     * 固定当前操作者身份：优先使用显式系统身份，其次从 Sa-Token 会话读取登录用户并补全姓名与角色。
     *
     * <p>Servlet/Sa-Token 上下文缺失时绝不静默提升为已认证身份：后台调用须显式提供系统身份，
     * 否则抛出异常；未认证的 HTTP 请求返回匿名身份。
     */
    @Override
    public AuditActor currentActor() {
        if (RequestContextHolder.getRequestAttributes() == null && AuditSystemContext.actor() != null) {
            return AuditSystemContext.actor();
        }
        // Servlet/Sa-Token 上下文缺失时，绝不静默提升为已认证身份。
        Object id;
        try {
            id = StpUtil.getLoginIdDefaultNull();
        } catch (cn.dev33.satoken.exception.SaTokenContextException exception) {
            if (AuditSystemContext.actor() != null) {
                return AuditSystemContext.actor();
            }
            throw new IllegalStateException("后台审计操作需要显式提供系统身份");
        }
        if (id == null) {
            if (RequestContextHolder.getRequestAttributes() == null) {
                throw new IllegalStateException("后台审计操作需要显式提供系统身份");
            }
            return AuditActor.anonymous();
        }
        var user = users.selectOneById(Long.valueOf(id.toString()));
        if (user == null) {
            throw new IllegalStateException("无法固定审计操作者身份");
        }
        var profile = profiles.selectOneByCondition(PROFILE.USER_ID.eq(user.getId()));
        String name = profile == null || profile.getNickName() == null || profile.getNickName().isBlank()
                ? user.getUsername() : profile.getNickName();
        String roleCode = SaTokenConfigure.currentRole();
        var role = roleCode.isBlank() ? null : roles.selectOneByCondition(ROLE.CODE.eq(roleCode));
        return new AuditActor("USER", user.getId().toString(), clean(user.getUsername(), 64), clean(name, 100),
                roleCode.isBlank() ? null : clean(roleCode, 64), role == null ? null : clean(role.getName(), 100));
    }

    /**
     * 按操作类型从入口参数提取安全的目标对象引用：仅保留标识与名称，绝不复制业务载荷。
     * 成员类操作去重并限制最多 100 个对象。
     */
    private static List<AuditObject> targets(AuditOperation operation, Object[] args) {
        Object first = args.length == 0 ? null : args[0];
        return switch (operation) {
            case TRADING_CALENDAR_INITIALIZE, TRADING_CALENDAR_BATCH, TRADING_CALENDAR_IMPORT ->
                    List.of(new AuditObject("TRADING_CALENDAR", "TRADING_CALENDAR", null, "交易日历", null, null));
            case POLICY_UPDATE, POLICY_RESET -> first instanceof String code
                    ? List.of(new AuditObject(SYSTEM_POLICY, code, code, code, null, null)) : List.of();
            case NOTIFICATION_SCENE_GROUPS_REPLACE, WORKFLOW_NOTIFICATION_SCENE_UPDATE ->
                    first instanceof String code ? sceneTargets(code) : List.of();
            case WORKFLOW_NOTIFICATION_SCENE_CREATE ->
                    first instanceof WorkflowSceneService.Write request ? sceneTargets(request.sceneCode()) : List.of();
            case MFA_KEY_STAGE, MFA_KEY_REFRESH -> List.of(new AuditObject(SYSTEM_POLICY,
                    "MFA_KEY_MANAGEMENT", "MFA_KEY_MANAGEMENT", "MFA 密钥管理", null, null));
            case MFA_ROTATION_START, MFA_ROTATION_COMPLETE, MFA_ROTATION_FAIL -> List.of(new AuditObject(SYSTEM_POLICY,
                    "MFA_KEY_ROTATION", "MFA_KEY_ROTATION", "MFA 密钥轮转", null, null));
            default -> membershipTargets(operation, args, first);
        };
    }

    private static List<AuditObject> sceneTargets(String code) {
        return validSceneCode(code) ? List.of(new AuditObject("NOTIFICATION_SCENE", code, code, code, null, null)) : List.of();
    }

    private static List<AuditObject> membershipTargets(AuditOperation operation, Object[] args, Object first) {
        if (operation == AuditOperation.DEPARTMENT_MEMBERS_UPDATE && first instanceof MembersRequest request
                && request.userIds() != null) {
            return userReferences(request.userIds().stream());
        }
        if (first instanceof MemberChangeRequest request && request.users() != null) {
            return userReferences(request.users().stream().filter(Objects::nonNull).map(VersionedUser::userId));
        }
        return targetsById(operation, args);
    }

    private static List<AuditObject> userReferences(Stream<Long> ids) {
        return ids.filter(id -> id != null && id > 0).distinct().limit(100)
                .map(id -> new AuditObject("USER", id.toString(), null, id.toString(), null, null)).toList();
    }

    /**
     * 按操作类型将入口参数中的主键映射为对象引用；主键缺失或非正数时返回空列表。
     */
    private static List<AuditObject> targetsById(AuditOperation operation, Object[] args) {
        Long first = args.length > 0 && args[0] instanceof Long id ? id : null;
        Long second = args.length > 1 && args[1] instanceof Long id ? id : null;
        if (operation == AuditOperation.ROLE_PERMISSION_ADD && args.length > 0
                && args[0] instanceof me.lendev.flow.pms.domain.request.AddRolePermissionsRequest request) {
            first = request.getId();
        }
        if (operation == AuditOperation.USER_PASSWORD_CHANGE) {
            first = StpUtil.getLoginIdAsLong();
        }
        if (first == null || first <= 0) {
            return List.of();
        }
        return switch (operation) {
            case PARAM_GROUP_UPDATE, PARAM_GROUP_DELETE, PARAM_ITEM_CREATE, PARAM_ITEM_SORT ->
                    List.of(new AuditObject("PARAM_GROUP", first.toString(), null, first.toString(), null, null));
            case PARAM_ITEM_UPDATE, PARAM_ITEM_DELETE -> second == null || second <= 0 ? List.of() :
                    List.of(new AuditObject("PARAM_ITEM", second.toString(), null, second.toString(), "PARAM_GROUP", first.toString()));
            case DEPARTMENT_UPDATE, DEPARTMENT_LEADER_UPDATE ->
                    List.of(new AuditObject("DEPARTMENT", first.toString(), null, first.toString(), null, null));
            case NOTIFICATION_GROUP_UPDATE, NOTIFICATION_GROUP_STATUS_UPDATE ->
                    List.of(new AuditObject("NOTIFICATION_GROUP", first.toString(), null, first.toString(), null, null));
            case DEPARTMENT_MEMBERSHIPS_REPLACE, USER_UPDATE, USER_DELETE, USER_PROFILE_UPDATE, USER_ROLE_REPLACE,
                 USER_PASSWORD_RESET, USER_PASSWORD_CHANGE, MFA_BIND, MFA_PREFERENCE ->
                    List.of(new AuditObject("USER", first.toString(), null, first.toString(), null, null));
            case ROLE_UPDATE, ROLE_DELETE, ROLE_PERMISSION_ADD, ROLE_USER_ADD, ROLE_USER_REMOVE ->
                    List.of(new AuditObject("ROLE", first.toString(), null, first.toString(), null, null));
            case PERMISSION_UPDATE, PERMISSION_DELETE, PERMISSION_SORT ->
                    List.of(new AuditObject("PERMISSION", first.toString(), null, first.toString(), null, null));
            case MAIL_ACCOUNT_UPDATE ->
                    List.of(new AuditObject("MAIL_ACCOUNT", first.toString(), null, first.toString(), null, null));
            case MAIL_TEMPLATE_UPDATE ->
                    List.of(new AuditObject("MAIL_TEMPLATE", first.toString(), null, first.toString(), null, null));
            case TODO_FLOW_DEFINE_EDIT, TODO_FLOW_PUBLISH, TODO_FLOW_RETIRE ->
                    List.of(new AuditObject("WORKFLOW_DEFINITION", first.toString(), null, first.toString(), null, null));
            case DOCUMENT_EDIT, DOCUMENT_BIND, DOCUMENT_DETACH, DOCUMENT_DELETE ->
                    List.of(new AuditObject(DOCUMENT, first.toString(), null, first.toString(), null, null));
            case DOCUMENT_UPLOAD, DOCUMENT_PUBLISH, DOCUMENT_DOWNLOAD, DOCUMENT_CLEANUP ->
                    second == null ? List.of(new AuditObject(DOCUMENT, first.toString(), null, first.toString(), null, null))
                            : List.of(new AuditObject("DOCUMENT_VERSION", second.toString(), null, second.toString(),
                            DOCUMENT, first.toString()));
            case INSTITUTION_EDIT, INSTITUTION_STATUS ->
                    List.of(new AuditObject("INSTITUTION", first.toString(), null, first.toString(), null, null));
            case AUDIT_DETAIL_VIEW ->
                    List.of(new AuditObject("AUDIT_LOG", first.toString(), null, first.toString(), null, null));
            case AUDIT_RETRY ->
                    List.of(new AuditObject("AUDIT_OUTBOX", first.toString(), null, first.toString(), null, null));
            default -> List.of();
        };
    }

    /**
     * 去除控制字符并截断长度，防止审计日志注入与超长载荷。
     */
    static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String safe = value.replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
