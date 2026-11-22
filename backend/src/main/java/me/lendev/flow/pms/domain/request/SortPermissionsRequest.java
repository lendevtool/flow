package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 资源排序请求。父菜单为空时排列根菜单下的一级菜单，ID 列表按目标展示顺序提交。
 */
public record SortPermissionsRequest(@Positive Long parentId,
                                     @NotEmpty @Size(max = 5000) List<@NotNull @Positive Long> ids) {

}
