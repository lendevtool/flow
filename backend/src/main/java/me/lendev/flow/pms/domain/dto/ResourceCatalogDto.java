package me.lendev.flow.pms.domain.dto;

/**
 * 可通过接口授权读取的资源目录信息。
 */
public record ResourceCatalogDto(long id, Long parentId, String code, String name, String nameEn, String type,
                                 boolean enable, Integer order) {
}
