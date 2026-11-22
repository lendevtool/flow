package me.lendev.flow.pms.audit;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.domain.entity.*;
import me.lendev.flow.pms.department.DepartmentAuditHandler;
import me.lendev.flow.pms.department.DepartmentModels.DepartmentRef;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

/**
 * 系统管理模块的审计快照工具：显式列举参与审计的业务字段。 密码等凭据字段绝不从实体序列化进快照，避免敏感信息落入审计日志。
 */
@Component
@RequiredArgsConstructor
public class PmsAuditSnapshots {

    private static final String ENABLE = "enable";
    private static final String NAME_EN = "nameEn";

    private final AuditSnapshots snapshots;

    public AuditObject roleRef(Role role) {
        return new AuditObject("ROLE", role.getId().toString(), role.getCode(), role.getName(), null, null);
    }

    public AuditObject permissionRef(Permission resource) {
        // 树形父节点是可变的业务字段，不属于审计对象的身份标识，故不放入 AuditObject。
        return new AuditObject("PERMISSION", resource.getId().toString(), resource.getCode(), resource.getName(), null, null);
    }

    public AuditSnapshots.Capture role(Role role, List<Long> permissions, List<Long> users) {
        var fields = fields(role, "code", "编码", "name", "名称", NAME_EN, "英文名称", ENABLE, "启用");
        if (permissions != null) {
            fields.put("permissionIds", AuditField.visible("权限集合", ids(permissions)));
        }
        if (users != null) {
            fields.put("userIds", AuditField.visible("用户集合", ids(users)));
        }
        return snapshots.capture(roleRef(role), fields);
    }

    public AuditSnapshots.Capture permission(Permission resource, List<Long> roleIds) {
        var fields = fields(resource, "code", "编码", "name", "名称", NAME_EN, "英文名称", "type", "类型", "parentId", "父资源ID",
                "path", "路径", "redirect", "重定向", "icon", "图标", "component", "组件", "layout", "布局",
                "keepAlive", "缓存页面", "method", "方法", "description", "描述", "show", "显示", ENABLE, "启用", "order", "排序");
        if (roleIds != null) {
            fields.put("roleIds", AuditField.visible("关联角色", ids(roleIds)));
        }
        return snapshots.capture(permissionRef(resource), fields);
    }

    public AuditObject userRef(User user) {
        return new AuditObject("USER", user.getId().toString(), user.getUsername(), user.getUsername(), null, null);
    }

    public AuditSnapshots.Capture user(User user, Profile profile, List<Long> roles, List<DepartmentRef> memberships) {
        var fields = fields(user, "username", "账号", "nameCn", "中文姓名", NAME_EN, "英文名称",
                "domainAccount", "域账号", ENABLE, "启用", "departmentId", "主部门");
        if (memberships != null) {
            fields.put("departments", DepartmentAuditHandler.membershipField(memberships));
        }
        fields.put("mobile", AuditField.hidden("手机号", user.getMobile()));
        String[] names = {"nickName", "昵称", "gender", "性别", "avatar", "头像", "address", "地址", "email", "邮箱"};
        var wrapper = profile == null ? null : new BeanWrapperImpl(profile);
        for (int i = 0; i < names.length; i += 2) {
            fields.put("profile." + names[i], profile == null ? AuditField.missing(names[i + 1], true)
                    : AuditField.visible(names[i + 1], wrapper.getPropertyValue(names[i])));
        }
        fields.put("roleIds", AuditField.visible("角色集合", ids(roles)));
        return snapshots.capture(userRef(user), fields);
    }

    public static List<String> ids(List<Long> values) {
        if (values.size() > 5000) {
            throw BadRequestException.localized("error.assignment.limit");
        }
        return values.stream().distinct().sorted().map(String::valueOf).toList();
    }

    public static void binding(java.lang.reflect.Method method, Class<?>... parameters) {
        bindingResult(method, void.class, parameters);
    }

    public static void bindingResult(java.lang.reflect.Method method, Class<?> result, Class<?>... parameters) {
        if (!Arrays.equals(method.getParameterTypes(), parameters) || method.getReturnType() != result) {
            throw new IllegalStateException("系统管理审计绑定的方法签名不一致: " + method.getName());
        }
    }

    private Map<String, AuditField> fields(Object bean, String... names) {
        Map<String, AuditField> result = new LinkedHashMap<>();
        var wrapper = new BeanWrapperImpl(bean);
        for (int i = 0; i < names.length; i += 2) {
            result.put(names[i], AuditField.visible(names[i + 1], wrapper.getPropertyValue(names[i])));
        }
        return result;
    }
}
