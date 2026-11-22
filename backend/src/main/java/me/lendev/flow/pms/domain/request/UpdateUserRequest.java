package me.lendev.flow.pms.domain.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Size;

import java.util.List;

import jakarta.validation.Valid;
import me.lendev.flow.pms.department.DepartmentModels.ReplaceMembershipsRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * 更新用户
 *
 * @author lendev
 */
@Data
public class UpdateUserRequest {

    /**
     * 为 {@code null} 时保持角色分配不变；为空列表时清空该用户的全部角色。
     */
    private @Nullable List<@NotNull @Positive Long> roleIds;

    private Boolean enable;

    /**
     * 可选的部门归属；编辑时省略该字段则保留现有归属。
     */
    @Valid
    private ReplaceMembershipsRequest memberships;

    /**
     * 省略时保留现有中文姓名，传入空字符串或 null 时清除。
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 100, message = "{validation.name-cn.size}")
    private String nameCn;

    /**
     * 省略时保留现有英文名，传入空字符串或 null 时清除。
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 254, message = "{validation.domain-account.size}")
    private String domainAccount;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 16, message = "{validation.mobile.size}")
    @ToString.Exclude
    private String mobile;

}
