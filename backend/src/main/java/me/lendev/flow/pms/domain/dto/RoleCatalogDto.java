package me.lendev.flow.pms.domain.dto;

/**
 * 可通过接口授权读取的角色基本信息。
 */
public record RoleCatalogDto(long id, String code, String name, String nameEn, boolean enable) {
}
