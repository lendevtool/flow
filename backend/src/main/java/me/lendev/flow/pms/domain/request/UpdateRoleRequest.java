package me.lendev.flow.pms.domain.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Size;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 更新角色
 *
 * @author lendev
 */
@Data
public class UpdateRoleRequest {

    private String name;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;
    private Boolean enable;

    private List<@NotNull @Positive Long> permissionIds;


}
