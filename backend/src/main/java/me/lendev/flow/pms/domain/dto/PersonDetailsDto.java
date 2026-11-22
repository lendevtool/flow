package me.lendev.flow.pms.domain.dto;

import java.util.List;

import me.lendev.flow.pms.department.DepartmentModels.DepartmentRef;

/**
 * 人员名片的公开业务字段，部门列表同时包含主部门及兼岗部门。
 */
public record PersonDetailsDto(Long id, String username, String nameCn, String nameEn, String email,
                               Long primaryDepartmentId, List<DepartmentRef> departments, List<RoleDto> roles) {

}
