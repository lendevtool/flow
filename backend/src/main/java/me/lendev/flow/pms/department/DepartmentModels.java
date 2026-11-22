package me.lendev.flow.pms.department;

import java.util.List;

import jakarta.validation.constraints.*;

/**
 * 组织架构请求与响应契约。
 */
public final class DepartmentModels {

    public static final int MAX_MEMBER_BATCH = 100;

    private DepartmentModels() {
    }

    public record Department(Long id, Long parentId, String name, int sortOrder, boolean enabled,
                             Long leaderUserId, String leaderName) {

    }

    public record Member(Long id, String username, String avatar, String nameCn, String nameEn,
                         Long departmentId, String departmentName, boolean enabled, long departmentVersion,
                         List<DepartmentRef> departments, List<DepartmentRef> matchedDepartments) {

    }

    public record DepartmentRef(Long id, String name) {

    }

    public record Memberships(Long primaryDepartmentId, long departmentVersion, List<DepartmentRef> departments) {

    }

    public enum MemberAction {
        ADD, REMOVE, TRANSFER
    }

    public record VersionedUser(@NotNull @Positive Long userId, @NotNull @PositiveOrZero Long expectedVersion) {

    }

    public record MemberChangeRequest(@NotNull MemberAction action, @Positive Long sourceDepartmentId,
                                      @Positive Long targetDepartmentId,
                                      @NotEmpty @Size(max = MAX_MEMBER_BATCH) List<@NotNull @jakarta.validation.Valid VersionedUser> users) {

    }

    public record InitialMemberships(@NotNull List<@NotNull @Positive Long> departmentIds,
                                     @Positive Long primaryDepartmentId) {

    }

    public record ReplaceMembershipsRequest(@NotNull List<@NotNull @Positive Long> departmentIds,
                                            @Positive Long primaryDepartmentId,
                                            @NotNull @PositiveOrZero Long expectedVersion) {

    }

    public record DepartmentRequest(@Positive Long parentId, @NotBlank @Size(max = 100) String name,
                                    @NotNull @PositiveOrZero Integer sortOrder, @NotNull Boolean enabled) {

    }

    public record LeaderRequest(@Positive Long userId) {

    }

    public record MembersRequest(@NotEmpty @Size(max = MAX_MEMBER_BATCH) List<@NotNull @Positive Long> userIds,
                                 @Positive Long departmentId) {

    }

    public record MemberQuery(@Positive Long departmentId, Boolean unassigned, Boolean includeDescendants,
                              @Size(max = 100) String keyword, Boolean enable, @Positive Integer pageNo,
                              @Min(1) @Max(100) Integer pageSize) {

        int size() {
            return pageSize == null ? 20 : pageSize;
        }
    }
}
