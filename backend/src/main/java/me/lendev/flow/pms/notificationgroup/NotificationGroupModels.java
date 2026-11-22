package me.lendev.flow.pms.notificationgroup;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 通知群组管理的请求和响应契约；收件人只包含账号标识和显示字段。
 */
public final class NotificationGroupModels {

    static final int MAX_USERS = 200;
    static final int MAX_DEPARTMENTS = 100;
    static final int MAX_SCENE_GROUPS = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String SCENE_CODE_REGEX = "[a-z][a-z0-9_.]{0,63}";
    private static final Pattern SCENE_CODE = Pattern.compile(SCENE_CODE_REGEX);

    private NotificationGroupModels() {
    }

    /**
     * 校验部署登记的场景编码格式；失败审计也用此规则筛除不安全的目标标识。
     *
     * @param code 场景编码
     * @return 符合格式时返回 {@code true}
     */
    public static boolean validSceneCode(String code) {
        return code != null && SCENE_CODE.matcher(code).matches();
    }

    public record Query(@Positive Integer pageNo, @Positive @Max(MAX_PAGE_SIZE) Integer pageSize,
                        @Size(max = 100) String keyword, Boolean enabled) {

        int limit() {
            return pageSize == null ? 20 : pageSize;
        }

        long offset() {
            return ((pageNo == null ? 1 : pageNo) - 1L) * limit();
        }
    }

    public record DepartmentSelection(@NotNull @Positive Long departmentId, Boolean includeDescendants) {

        public DepartmentSelection {
            includeDescendants = Boolean.TRUE.equals(includeDescendants);
        }
    }

    public record CreateGroupRequest(
            @NotBlank String name, String description,
            @NotNull @Size(max = MAX_USERS) List<@NotNull @Positive @Max(Integer.MAX_VALUE) Long> userIds,
            @NotNull @Size(max = MAX_DEPARTMENTS) List<@NotNull @Valid DepartmentSelection> departments) {

    }

    public record UpdateGroupRequest(
            @NotBlank String name, String description,
            @NotNull @Size(max = MAX_USERS) List<@NotNull @Positive @Max(Integer.MAX_VALUE) Long> userIds,
            @NotNull @Size(max = MAX_DEPARTMENTS) List<@NotNull @Valid DepartmentSelection> departments,
            @NotNull @PositiveOrZero Long expectedVersion) {

    }

    public record GroupStatusRequest(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long expectedVersion) {

    }

    public record ReplaceSceneGroupsRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = MAX_SCENE_GROUPS) List<@NotNull @Positive Long> groupIds) {

    }

    public record PreviewRequest(
            @Positive Long groupId,
            @NotNull @Size(max = MAX_USERS) List<@NotNull @Positive @Max(Integer.MAX_VALUE) Long> userIds,
            @NotNull @Size(max = MAX_DEPARTMENTS) List<@NotNull @Valid DepartmentSelection> departments,
            @Positive Integer pageNo, @Positive @Max(MAX_PAGE_SIZE) Integer pageSize) {

        Query page() {
            return new Query(pageNo, pageSize, null, null);
        }
    }

    public enum SourceStatus {
        ACTIVE, DISABLED, MISSING
    }

    public record UserOption(Long userId, String nameCn, String nameEn, String username) {

    }

    public record GroupUser(Long userId, String nameCn, String nameEn, String username, SourceStatus status) {

    }

    public record DepartmentOption(Long departmentId, Long parentId, String name, int sortOrder) {

    }

    public record GroupDepartment(Long departmentId, String name, String path, boolean includeDescendants,
                                  SourceStatus status) {

    }

    public record GroupListItem(Long id, String name, String description, boolean enabled, long version,
                                long userCount, long departmentCount, long usageCount, OffsetDateTime updatedAt) {

    }

    public record GroupDetail(Long id, String name, String description, boolean enabled, long version,
                              int createdBy, int updatedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              List<GroupUser> users, List<GroupDepartment> departments) {

    }

    public record GroupOption(Long id, String name) {

    }

    public record SceneOption(String sceneCode, String nameCn, String nameEn) {

    }

    public record SceneListItem(String sceneCode, String nameCn, String nameEn, long version, String sceneType,
                                boolean enabled, String titleTemplate, String summaryTemplate,
                                Long mailTemplateId, long groupCount) {

    }

    public record SceneDetail(String sceneCode, String nameCn, String nameEn, long version, List<GroupOption> groups,
                              String sceneType, boolean enabled, String titleTemplate, String summaryTemplate,
                              Long mailTemplateId) {

    }
}
