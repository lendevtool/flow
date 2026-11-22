package me.lendev.flow.pms.workflownotification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.SendMode;
import me.lendev.flow.mail.MailModels.SendResult;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Submission;
import me.lendev.flow.mail.MailOperationException;
import me.lendev.flow.mail.MailService;
import me.lendev.flow.mail.MailStore;
import me.lendev.flow.mail.MailStore.SendReservation;
import me.lendev.flow.pms.inapp.InAppMessageService;
import me.lendev.flow.pms.inapp.InAppMessageService.Write;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.SceneTrigger;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.TaskTrigger;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-notice-queue;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "workflow.notification.delivery.enabled=true",
        "workflow.notification.delivery.poll-ms=86400000"
})
@ActiveProfiles({"h2", "test"})
@Import(JdbcFixtureConfiguration.class)
class WorkflowNoticeQueueTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    WorkflowNoticeQueue queue;
    @Autowired
    WorkflowNoticeProgress progress;
    @Autowired
    WorkflowNoticeWorker worker;
    @Autowired
    WorkflowNoticeQueries queries;
    @Autowired
    WorkflowSceneCatalog scenes;
    @Autowired
    InAppMessageService inbox;
    @Autowired
    MailStore mailStore;

    private SceneTrigger trigger;

    @BeforeEach
    void fixtures() {
        String key = UUID.randomUUID().toString();
        String code = "test." + key.replace("-", "");
        jdbc.update("""
                INSERT INTO sys_notification_scene
                    (scene_code,name_cn,name_en,scene_type,in_app_title_template,enabled,version,activation_version,updated_at)
                VALUES (?, '进展', 'Progress', 'WORKFLOW_EXTRA', '节点 {{node.name}}', TRUE, 0, 0, CURRENT_TIMESTAMP)
                """, code);
        jdbc.update("""
                INSERT INTO wf_definition (code,revision,name,stepsJson,createdBy,schemaVersion)
                VALUES (?,1,'Workflow','[]',0,2)
                """, key);
        long definition = jdbc.queryForObject("SELECT id FROM wf_definition WHERE code=?", Long.class, key);
        jdbc.update("""
                INSERT INTO wf_instance (definitionId,title,requestScope,requestKey,requestHash,stepsStateJson,currentStepNo)
                VALUES (?,'Protected','SYSTEM',?,?,'[]',1)
                """, definition, key, "0".repeat(64));
        long flow = jdbc.queryForObject("SELECT id FROM wf_instance WHERE requestKey=?", Long.class, key);
        jdbc.update("""
                INSERT INTO wf_node_instance (flowInstanceId,nodeKey,stageNo,attemptNo,status)
                VALUES (?,'first',1,1,'DONE')
                """, flow);
        long node = jdbc.queryForObject("SELECT id FROM wf_node_instance WHERE flowInstanceId=?", Long.class, flow);
        jdbc.update("""
                INSERT INTO wf_flow_event (flowInstanceId,fromNodeInstanceId,eventType,actorUserId,requestKey,requestHash)
                VALUES (?,?,'APPROVED',0,?,?)
                """, flow, node, key, "0".repeat(64));
        long event = jdbc.queryForObject("SELECT id FROM wf_flow_event WHERE flowInstanceId=?", Long.class, flow);
        trigger = new SceneTrigger(flow, node, event, code, false, "审核", null);
    }

    @Test
    void rollsBackWithWorkflowAndKeepsOneDeliveryForRepeatedTriggers() {
        transaction.executeWithoutResult(status -> {
            queue.enqueueScenes(List.of(trigger));
            status.setRollbackOnly();
        });
        assertEquals(0, count());

        enqueue();
        enqueue();

        assertEquals(1, count());
        assertEquals("PENDING", status());
    }

    @Test
    void oldEpochAndExpiredLeaseCannotPrepareOrOverwriteTheCurrentWorker() {
        enqueue();
        long id = id();
        var stale = progress.claim(id);
        jdbc.update("UPDATE wf_notification_delivery SET lease_expires_at=? WHERE id=?", "2000-01-01 00:00:00", id);
        var current = progress.claim(id);
        assertNull(progress.claim(id));
        assertFalse(progress.finish(stale, "STALE"));
        assertThrows(WorkflowNoticeProgress.LeaseLost.class, () -> progress.freeze(stale, null));

        jdbc.update("UPDATE sys_notification_scene SET version=2, activation_version=2 WHERE scene_code=?",
                trigger.sceneCode());
        assertTrue(progress.freeze(current, null));
        assertEquals("SKIPPED", status());
        assertEquals("SCENE_ACTIVATION_CHANGED",
                jdbc.queryForObject("SELECT last_error_code FROM wf_notification_delivery WHERE id=?", String.class, id));
    }

    @Test
    void sceneChangeRereadsConfigurationAndRetriesUseOnlyTheFrozenRecipients() {
        int enabled = user(null);
        int disabledAfterPreparation = user(null);
        int joinedAfterPreparation = user(null);
        long original = group(enabled, disabledAfterPreparation);
        group(enabled);
        enqueue();
        var claim = progress.claim(id());
        var oldSnapshot = scenes.prepare(trigger.sceneCode());
        jdbc.update("""
                UPDATE sys_notification_scene SET version=1, in_app_title_template='{{event.type}} {{node.name}}'
                WHERE scene_code=?
                """, trigger.sceneCode());

        assertFalse(progress.freeze(claim, oldSnapshot));
        assertTrue(progress.freeze(claim, scenes.prepare(trigger.sceneCode())));
        assertEquals(2, queries.detail(id()).recipients().size());

        jdbc.update("DELETE FROM sys_notification_group_user WHERE group_id=?", original);
        group(joinedAfterPreparation);
        jdbc.update("UPDATE `user` SET enable=FALSE WHERE id=?", disabledAfterPreparation);
        jdbc.update("UPDATE sys_notification_scene SET version=2, enabled=FALSE WHERE scene_code=?", trigger.sceneCode());
        expireLease(id());
        worker.deliver(id());

        var detail = queries.detail(id());
        assertEquals("PARTIAL", detail.delivery().status().name());
        assertEquals(1L, detail.delivery().sceneVersion());
        assertEquals(List.of(enabled, disabledAfterPreparation),
                detail.recipients().stream().map(WorkflowNoticeModels.RecipientRow::recipientUserId).toList());
        var first = detail.recipients().getFirst();
        assertNotNull(first.messageId());
        assertEquals("通过 审核", jdbcClient.sql("SELECT title FROM sys_in_app_message WHERE id=?")
                .param(first.messageId()).query(String.class).single());
        assertEquals("RECIPIENT_DISABLED", detail.recipients().getLast().inAppErrorCode());
    }

    @Test
    void recoversAnAcceptedSendAfterTheTaskClosesAndTheRecipientChanges() {
        int userId = user("original@example.com");
        var mailFixture = enableTaskMail();
        long id = enqueueTask(userId);
        var lease = progress.claim(id);
        var frozen = progress.mailRequest(lease, userId);
        assertEquals("original@example.com", frozen.recipient().address());

        jdbc.update("UPDATE profile SET email='current@example.com' WHERE userId=?", userId);
        jdbc.update("UPDATE wb_todo SET status='CANCELLED' WHERE id=(SELECT todoId FROM wf_node_task WHERE assigneeUserId=?)",
                userId);
        expireLease(id);
        var recovered = progress.claim(id);
        assertEquals(frozen, progress.mailRequest(recovered, userId));
        var accepted = reserve(id, userId, mailFixture, frozen.recipient());
        long detailId = accepted.details().getFirst().id();
        assertTrue(mailStore.claim(detailId));
        mailStore.finish(detailId, new Submission(Status.ACCEPTED, null, null, null, List.of()));
        expireLease(id);
        jdbc.update("UPDATE `user` SET enable=FALSE WHERE id=?", userId);

        worker.deliver(id);

        var detail = queries.detail(id);
        assertEquals("DONE", detail.delivery().status().name());
        assertEquals("ACCEPTED", detail.recipients().getFirst().mailStatus().name());
        assertEquals(accepted.log().id(), detail.recipients().getFirst().mailLogId());
        assertNull(jdbc.queryForObject("SELECT mail_request_json FROM wf_notification_recipient WHERE delivery_id=?",
                String.class, id));
    }

    @Test
    void retryBudgetExhaustionRetainsTheUnconfirmedMailLog() {
        int userId = user("pending@example.com");
        var mailFixture = enableTaskMail();
        long id = enqueueTask(userId);
        var reserved = reserve(id, userId, mailFixture, new Recipient("pending@example.com", "收件人"));
        assertTrue(mailStore.claim(reserved.details().getFirst().id()));

        worker.deliver(id);

        var retry = queries.detail(id);
        assertEquals("RETRY", retry.delivery().status().name());
        assertEquals("MAIL_RESULT_PENDING", retry.delivery().lastErrorCode());
        jdbc.update("UPDATE wf_notification_delivery SET attempt_count=4, next_attempt_at=? WHERE id=?",
                "2000-01-01 00:00:00", id);

        worker.deliver(id);

        var detail = queries.detail(id);
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals(5, detail.delivery().attemptCount());
        assertEquals("UNKNOWN", detail.recipients().getFirst().mailStatus().name());
        assertEquals(reserved.log().id(), detail.recipients().getFirst().mailLogId());
        assertEquals("MAIL_RESULT_UNCONFIRMED", detail.recipients().getFirst().mailErrorCode());
    }

    @Test
    void pollPreparesTheCurrentSceneAndWritesTheInboxMessageExactlyOnce() {
        int userId = user(null);
        group(userId);
        var longNodeName = "验".repeat(220);
        var notice = new SceneTrigger(trigger.flowInstanceId(), trigger.nodeInstanceId(), trigger.eventId(),
                trigger.sceneCode(), false, longNodeName, null);
        transaction.executeWithoutResult(status -> queue.enqueueScenes(List.of(notice)));

        worker.poll();
        worker.poll();

        var detail = queries.detail(id());
        assertEquals("DONE", detail.delivery().status().name());
        assertEquals(1, detail.delivery().attemptCount());
        var recipient = detail.recipients().getFirst();
        assertEquals("WRITTEN", recipient.inAppStatus().name());
        assertEquals("OFF", recipient.mailStatus().name());
        assertEquals("节点 " + "验".repeat(197), jdbcClient.sql("SELECT title FROM sys_in_app_message WHERE id=?")
                .param(recipient.messageId()).query(String.class).single());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_in_app_message WHERE recipient_user_id=?",
                Integer.class, userId));
    }

    @Test
    void stopsAQueuedSceneDisabledBetweenTheReadAndTheFreeze() {
        group(user(null));
        enqueue();
        var claim = progress.claim(id());
        var snapshot = scenes.prepare(trigger.sceneCode());
        jdbc.update("UPDATE sys_notification_scene SET version=1, enabled=FALSE WHERE scene_code=?", trigger.sceneCode());

        assertTrue(progress.freeze(claim, snapshot));

        var detail = queries.detail(id());
        assertEquals("SKIPPED", detail.delivery().status().name());
        assertEquals("SCENE_DISABLED", detail.delivery().lastErrorCode());
        assertTrue(detail.recipients().isEmpty());
    }

    @Test
    void emptyBindingsSkipWithoutSchedulingARecipient() {
        enqueue();

        worker.deliver(id());

        var detail = queries.detail(id());
        assertEquals("SKIPPED", detail.delivery().status().name());
        assertEquals("NO_GROUPS", detail.delivery().lastErrorCode());
        assertTrue(detail.recipients().isEmpty());
    }

    @Test
    void invalidBindingsFailBeforeWritingInboxMessages() {
        int userId = user(null);
        long group = group(userId);
        jdbc.update("UPDATE sys_notification_group SET enabled=FALSE WHERE id=?", group);
        enqueue();

        worker.deliver(id());

        var detail = queries.detail(id());
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals("CONFIGURATION_INVALID", detail.delivery().lastErrorCode());
        assertTrue(detail.recipients().isEmpty());
    }

    @Test
    void optionalMailWithoutAnAddressSkipsWhileTheTaskMessageRemainsWritten() {
        int userId = user(null);
        enableTaskMail();
        long id = enqueueTask(userId);

        worker.deliver(id);

        assertSkippedTaskMail(id, "MAIL_ADDRESS_UNAVAILABLE");
    }

    @Test
    void closeBeforeFreezingMailAssessesTheTaskAndSkipsTheSend() {
        int userId = user("future@example.com");
        enableTaskMail();
        long id = enqueueTask(userId);
        jdbc.update("UPDATE wb_todo SET status='CANCELLED' WHERE assigneeUserId=?", userId);

        worker.deliver(id);

        assertSkippedTaskMail(id, "TASK_CLOSED");
    }

    @Test
    void disableBeforeFreezingMailSkipsEvenWhenAnAddressExists() {
        int userId = user("future@example.com");
        enableTaskMail();
        long id = enqueueTask(userId);
        jdbc.update("UPDATE `user` SET enable=FALSE WHERE id=?", userId);

        worker.deliver(id);

        assertSkippedTaskMail(id, "RECIPIENT_DISABLED");
    }

    @Test
    void staleFifthAttemptWithFrozenParametersAndNoLogNeverResubmits() {
        int userId = user("future@example.com");
        enableTaskMail();
        long id = enqueueTask(userId);
        jdbc.update("UPDATE wf_notification_delivery SET attempt_count=4 WHERE id=?", id);
        var fifth = progress.claim(id);
        assertNotNull(progress.mailRequest(fifth, userId));
        expireLease(id);

        worker.deliver(id);

        var detail = queries.detail(id);
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals(5, detail.delivery().attemptCount());
        assertEquals("MAIL_NOT_SUBMITTED", detail.recipients().getFirst().mailErrorCode());
        assertNull(detail.recipients().getFirst().mailLogId());
    }

    @Test
    void transientPreSubmissionFailureRetriesWithTheSameFrozenParameters() {
        int userId = user("stable@example.com");
        long id = enqueueTaskWithMail(userId);
        var submission = mock(MailService.class);
        var error = mock(MailOperationException.class);
        when(error.code()).thenReturn("MAIL_PROVIDER_UNAVAILABLE");
        when(error.status()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
        when(submission.sendByTemplateId(anyLong(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(error)
                .thenAnswer(call -> {
                    var submitted = call.getArgument(1, Recipient.class);
                    assertEquals("stable@example.com", submitted.address());
                    return new SendResult(12L, Status.ACCEPTED);
                });
        var processor = new WorkflowNoticeWorker(progress, scenes, submission);

        processor.deliver(id);

        assertEquals("RETRY", queries.detail(id).delivery().status().name());
        assertEquals("MAIL_PROVIDER_UNAVAILABLE", queries.detail(id).delivery().lastErrorCode());
        jdbc.update("UPDATE profile SET email='changed@example.com' WHERE userId=?", userId);
        makeRetryDue(id);
        processor.deliver(id);

        var detail = queries.detail(id);
        assertEquals("DONE", detail.delivery().status().name());
        assertEquals(12L, detail.recipients().getFirst().mailLogId());
        assertNull(detail.recipients().getFirst().mailErrorCode());
    }

    @Test
    void rejectsAnUnrenderableMailWithNoRetryAndKeepsTheTaskMessage() {
        long id = enqueueTaskWithMail(user("candidate@example.com"));
        var submission = mock(MailService.class);
        when(submission.sendByTemplateId(anyLong(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new BadRequestException("sample variable"));

        new WorkflowNoticeWorker(progress, scenes, submission).deliver(id);

        var detail = queries.detail(id);
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals("WRITTEN", detail.recipients().getFirst().inAppStatus().name());
        assertEquals("MAIL_TEMPLATE_RENDER_FAILED", detail.recipients().getFirst().mailErrorCode());
        assertNull(detail.recipients().getFirst().mailLogId());
    }

    @Test
    void exceptionAfterProviderReservationReconcilesExistingReceipt() {
        int userId = user("receipt@example.com");
        long id = enqueueTaskWithMail(userId);
        var submission = mock(MailService.class);
        var receipt = new MailService.SubmissionReceipt(13L, Status.PARTIAL, null);
        when(submission.findSubmission("SYSTEM:WORKFLOW_NOTIFICATION", "wf-notify:" + id + ":" + userId))
                .thenReturn(Optional.empty(), Optional.of(receipt));
        when(submission.sendByTemplateId(anyLong(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("interrupted after provider call"));

        new WorkflowNoticeWorker(progress, scenes, submission).deliver(id);

        var detail = queries.detail(id);
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals("UNKNOWN", detail.recipients().getFirst().mailStatus().name());
        assertEquals("MAIL_PARTIAL_RESULT", detail.recipients().getFirst().mailErrorCode());
        assertEquals(13L, detail.recipients().getFirst().mailLogId());
    }

    @Test
    void exhaustedInboxAttemptMarksUnfinishedChannelsFailed() {
        int userId = user("late@example.com");
        var setup = enableTaskMail();
        group(userId);
        jdbc.update("UPDATE sys_notification_scene SET mail_template_id=? WHERE scene_code=?", setup.template(), trigger.sceneCode());
        enqueue();
        jdbc.update("UPDATE wf_notification_delivery SET attempt_count=4 WHERE id=?", id());
        var fifth = progress.claim(id());
        assertTrue(progress.freeze(fifth, scenes.prepare(trigger.sceneCode())));
        expireLease(id());

        worker.deliver(id());

        var detail = queries.detail(id());
        assertEquals("FAILED", detail.delivery().status().name());
        assertEquals("FAILED", detail.recipients().getFirst().inAppStatus().name());
        assertEquals("FAILED", detail.recipients().getFirst().mailStatus().name());
        assertEquals("RETRY_EXHAUSTED", detail.recipients().getFirst().inAppErrorCode());
        assertEquals("RETRY_EXHAUSTED", detail.recipients().getFirst().mailErrorCode());
    }

    private void assertSkippedTaskMail(long id, String reason) {
        var detail = queries.detail(id);
        assertEquals("SKIPPED", detail.delivery().status().name());
        assertEquals(reason, detail.delivery().lastErrorCode());
        var recipient = detail.recipients().getFirst();
        assertEquals("SKIPPED", recipient.mailStatus().name());
        assertNull(recipient.mailLogId());
        assertNotNull(recipient.messageId());
    }

    private long enqueueTaskWithMail(int userId) {
        enableTaskMail();
        return enqueueTask(userId);
    }

    private void makeRetryDue(long id) {
        jdbc.update("UPDATE wf_notification_delivery SET next_attempt_at=? WHERE id=?",
                LocalDateTime.of(2000, 1, 1, 0, 0), id);
    }

    private int user(String email) {
        String username = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO `user` (username,password,enable) VALUES (?, 'fixture', TRUE)", username);
        int id = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO profile (userId,email) VALUES (?, ?)", id, email);
        return id;
    }

    private long group(int... members) {
        String name = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sys_notification_group (name,created_by,updated_by,created_at,updated_at)
                VALUES (?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, name);
        long id = jdbc.queryForObject("SELECT id FROM sys_notification_group WHERE name=?", Long.class, name);
        for (int member : members) {
            jdbc.update("INSERT INTO sys_notification_group_user (group_id,user_id) VALUES (?,?)", id, member);
        }
        jdbc.update("INSERT INTO sys_notification_scene_group (scene_code,group_id) VALUES (?,?)", trigger.sceneCode(), id);
        return id;
    }

    private long enqueueTask(int userId) {
        jdbc.update("UPDATE wf_node_instance SET status='ACTIVE' WHERE id=?", trigger.nodeInstanceId());
        jdbc.update("""
                INSERT INTO wb_todo (title,origin,assigneeUserId,assigneeName,scheduleMode)
                VALUES ('节点','FLOW',?,'收件人','NONE')
                """, userId);
        long todo = jdbc.queryForObject("SELECT id FROM wb_todo WHERE assigneeUserId=?", Long.class, userId);
        jdbc.update("INSERT INTO wf_node_task (nodeInstanceId,todoId,assigneeUserId,identity) VALUES (?,?,?,'PRIMARY')",
                trigger.nodeInstanceId(), todo, userId);
        long task = jdbc.queryForObject("SELECT id FROM wf_node_task WHERE todoId=?", Long.class, todo);
        transaction.executeWithoutResult(status -> {
            long message = inbox.publish(new Write(userId, "WORKFLOW", "TASK:" + task + ":TASK_ASSIGNED",
                    "TASK_ASSIGNED", "新待办", null, "WORKFLOW_TASK", task));
            queue.enqueueTaskMail(new TaskTrigger(trigger.flowInstanceId(), trigger.nodeInstanceId(), task,
                    trigger.eventId(), userId, message, false, "节点"));
        });
        return jdbc.queryForObject("SELECT id FROM wf_notification_delivery WHERE task_id=?", Long.class, task);
    }

    private MailFixture enableTaskMail() {
        String mailCode = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sys_mail_account (code,name,provider,mailboxAddress,configJson,credentialCiphertext,
                    createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?, 'Sender','GRAPH','send@example.com','{}','encrypted',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, mailCode);
        long account = jdbc.queryForObject("SELECT id FROM sys_mail_account WHERE code=?", Long.class, mailCode);
        jdbc.update("""
                INSERT INTO sys_mail_template (code,name,accountId,subjectTemplate,bodyTemplate,
                    createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?, 'Notice',?,'Subject','Body',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, mailCode, account);
        long template = jdbc.queryForObject("SELECT id FROM sys_mail_template WHERE code=?", Long.class, mailCode);
        jdbc.update("UPDATE sys_notification_scene SET mail_template_id=? WHERE scene_code='workflow.task.assigned'", template);
        return new MailFixture(account, template, mailCode);
    }

    private MailStore.StoredSend reserve(long id, int userId, MailFixture fixture, Recipient recipient) {
        var reservation = new SendReservation(fixture.account(), fixture.template(), fixture.code(), fixture.code(), 1,
                "send@example.com", Provider.GRAPH, SendMode.INDIVIDUAL, "SYSTEM:WORKFLOW_NOTIFICATION", "wf-notify:" + id,
                "wf-notify:" + id + ":" + userId, "a".repeat(64), 1);
        return mailStore.reserve(reservation, List.of(List.of(recipient)));
    }

    private void expireLease(long id) {
        jdbc.update("UPDATE wf_notification_delivery SET lease_expires_at=? WHERE id=?", "2000-01-01 00:00:00", id);
    }

    private record MailFixture(long account, long template, String code) {
    }

    private void enqueue() {
        transaction.executeWithoutResult(status -> queue.enqueueScenes(List.of(trigger)));
    }

    private int count() {
        return jdbcClient.sql("SELECT COUNT(*) FROM wf_notification_delivery WHERE event_id=?")
                .param(trigger.eventId()).query(Integer.class).single();
    }

    private long id() {
        return jdbcClient.sql("SELECT id FROM wf_notification_delivery WHERE event_id=?")
                .param(trigger.eventId()).query(Long.class).single();
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM wf_notification_delivery WHERE id=?", String.class, id());
    }
}
