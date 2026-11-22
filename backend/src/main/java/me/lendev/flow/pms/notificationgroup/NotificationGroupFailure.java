package me.lendev.flow.pms.notificationgroup;

import org.springframework.http.HttpStatus;

/**
 * 通知群组业务拒绝；异常消息仅保存固定的国际化键，不包含请求值。
 */
public final class NotificationGroupFailure extends RuntimeException {

    private final HttpStatus status;
    private final ConflictReason reason;
    private final Long usageCount;

    private NotificationGroupFailure(HttpStatus status, String key, ConflictReason reason, Long usageCount) {
        super("error.notification-group." + key);
        this.status = status;
        this.reason = reason;
        this.usageCount = usageCount;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * @return 稳定冲突原因；非冲突返回 {@code null}
     */
    public ConflictReason reason() {
        return reason;
    }

    /**
     * @return 阻止停用的场景引用数；其他异常返回 {@code null}
     */
    public Long usageCount() {
        return usageCount;
    }

    static NotificationGroupFailure invalidInput() {
        return badRequest("invalid");
    }

    static NotificationGroupFailure badRequest(String key) {
        return new NotificationGroupFailure(HttpStatus.BAD_REQUEST, key, null, null);
    }

    static NotificationGroupFailure missingGroup() {
        return new NotificationGroupFailure(HttpStatus.NOT_FOUND, "not-found", null, null);
    }

    static NotificationGroupFailure missingScene() {
        return new NotificationGroupFailure(HttpStatus.NOT_FOUND, "scene-not-found", null, null);
    }

    static NotificationGroupFailure nameConflict() {
        return new NotificationGroupFailure(HttpStatus.CONFLICT, "name-conflict", ConflictReason.NAME_CONFLICT, null);
    }

    static NotificationGroupFailure versionConflict() {
        return new NotificationGroupFailure(HttpStatus.CONFLICT, "version-conflict", ConflictReason.VERSION_CONFLICT, null);
    }

    static NotificationGroupFailure sceneCodeConflict() {
        return new NotificationGroupFailure(HttpStatus.CONFLICT, "workflow-scene-code-conflict", ConflictReason.SCENE_CODE_CONFLICT, null);
    }

    static NotificationGroupFailure groupInUse(long usageCount) {
        return new NotificationGroupFailure(HttpStatus.CONFLICT, "in-use", ConflictReason.GROUP_IN_USE, usageCount);
    }

    public enum ConflictReason {
        NAME_CONFLICT, VERSION_CONFLICT, GROUP_IN_USE, SCENE_CODE_CONFLICT
    }
}
