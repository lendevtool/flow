package me.lendev.flow.pms.notificationgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.GroupRow;
import org.springframework.stereotype.Service;

/**
 * 供业务场景在首次准备投递时解析收件人；只读取配置，既不发送消息，也不保存投递批次。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationRecipientResolver {

    private static final int MAX_RECIPIENTS = 5000;

    private final NotificationGroupStore store;
    private final NotificationReadSnapshot snapshot;
    private final Clock clock;

    /**
     * 从独立只读快照解析场景。调用方须在业务提交后发起首次解析；收到 {@link Status#READY}
     * 时将结果与本次投递标识一并持久化，重试前再校验固定名单中的账号仍可投递。
     *
     * @param sceneCode 业务模块登记的可信场景编码
     * @return 解析结果；只有 READY 提供可投递的、有序的唯一账号 ID
     */
    public Result resolveForScene(String sceneCode) {
        long start = System.nanoTime();
        boolean validCode = NotificationGroupModels.validSceneCode(sceneCode);
        Result result = validCode ? snapshot.read(() -> resolve(sceneCode))
                : result(Status.CONFIGURATION_INVALID, null, null, List.of(), List.of());
        log.info("通知场景收件人解析完成，sceneCode={} sceneVersion={} status={} groupCount={} returnedUserCount={} requestId={} elapsedMs={}",
                validCode ? sceneCode : null, result.sceneVersion(), result.status(), result.groups().size(),
                result.userIds().size(), AuditParticipants.currentRequestId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return result;
    }

    /**
     * 批量读取绑定、群组及配置。全部群组有效才进入收件人查询；以 5001 为查询上界判定超限。
     */
    Result resolve(String sceneCode) {
        var scene = store.scene(sceneCode, false);
        if (scene == null || !scene.enabled()) {
            return result(Status.CONFIGURATION_INVALID, sceneCode, null, List.of(), List.of());
        }
        long version = scene.version();
        List<Long> ids = store.groupIds(sceneCode);
        if (ids.isEmpty()) {
            return result(Status.NO_GROUPS, sceneCode, version, List.of(), List.of());
        }
        List<GroupRow> groups = store.groups(ids);
        if (groups.size() != ids.size() || groups.stream().anyMatch(group -> !group.enabled())) {
            return result(Status.CONFIGURATION_INVALID, sceneCode, version, List.of(), List.of());
        }
        List<GroupVersion> versions = groups.stream().map(group -> new GroupVersion(group.id(), group.version())).toList();
        var scope = snapshot.recipients(store.users(ids), store.departments(ids));
        List<Long> userIds = store.recipientIds(scope, MAX_RECIPIENTS + 1);
        if (userIds.size() > MAX_RECIPIENTS) {
            return result(Status.RECIPIENT_LIMIT_EXCEEDED, sceneCode, version, versions, List.of());
        }
        return result(userIds.isEmpty() ? Status.NO_RECIPIENTS : Status.READY, sceneCode, version, versions, userIds);
    }

    private Result result(Status status, String sceneCode, Long sceneVersion, List<GroupVersion> groups, List<Long> userIds) {
        return new Result(status, sceneCode, sceneVersion, clock.instant().atOffset(ZoneOffset.UTC), groups,
                List.copyOf(userIds));
    }

    public enum Status {
        READY, NO_GROUPS, NO_RECIPIENTS, CONFIGURATION_INVALID, RECIPIENT_LIMIT_EXCEEDED
    }

    /**
     * 场景解析在同一数据库快照中读取到的群组版本。
     */
    public record GroupVersion(Long groupId, long version) {

    }

    /**
     * 独立于后续配置和组织架构变更的解析结果。
     */
    public record Result(Status status, String sceneCode, Long sceneVersion, OffsetDateTime resolvedAt,
                         List<GroupVersion> groups, List<Long> userIds) {

    }
}
