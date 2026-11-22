package me.lendev.flow.pms.directory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 用户工作目录的分页条件和可公开的工作信息。
 */
public final class UserDirectoryModels {

    private UserDirectoryModels() {
    }

    /**
     * 用户目录分页条件。关键字匹配中文名或英文名，部门条件匹配主部门。
     */
    public record Query(@Positive @Max(1_000_000) Integer pageNo, @Positive @Max(100) Integer pageSize,
                        @Size(max = 100) String keyword, Boolean enabled, @Positive Long departmentId) {
    }

    /**
     * 用户工作目录条目。未填写的姓名及未分配的主部门字段为空。
     */
    public record Entry(long id, String nameCn, String nameEn, boolean enabled, Long departmentId,
                        String departmentName) {
    }
}
