-- H2 development/test schema, equivalent audit columns/constraints to the MySQL migration.
DROP TABLE IF EXISTS sys_operation_change;
DROP TABLE IF EXISTS sys_operation_log;
DROP TABLE IF EXISTS sys_audit_outbox;
CREATE TABLE sys_audit_outbox
(
    id                  BIGINT                        NOT NULL AUTO_INCREMENT,
    `eventId`           VARCHAR(36)                   NOT NULL,
    `operationId`       VARCHAR(36)                   NOT NULL,
    `requestId`         VARCHAR(64),
    `traceId`           VARCHAR(64),
    `batchId`           VARCHAR(64),
    `schemaVersion`     INTEGER     DEFAULT 1         NOT NULL,
    `occurredAt`        DATETIME(3)                   NOT NULL,
    `moduleCode`        VARCHAR(32)                   NOT NULL,
    `operationCode`     VARCHAR(96)                   NOT NULL,
    `phase`             VARCHAR(32) DEFAULT 'RESULT'  NOT NULL,
    `result`            VARCHAR(16)                   NOT NULL,
    `payload`           LONGTEXT                      NOT NULL,
    `payloadBytes`      BIGINT                        NOT NULL,
    `payloadHash`       VARCHAR(64)                   NOT NULL,
    `status`            VARCHAR(16) DEFAULT 'PENDING' NOT NULL,
    `attempts`          INTEGER     DEFAULT 0         NOT NULL,
    `failureCount`      INTEGER     DEFAULT 0         NOT NULL,
    `manualRetryCount`  INTEGER     DEFAULT 0         NOT NULL,
    `nextRetryAt`       DATETIME(3),
    `prepareDeadlineAt` DATETIME(3),
    `workerId`          VARCHAR(128),
    `leaseToken`        VARCHAR(36),
    `lockedUntil`       DATETIME(3),
    `rowVersion`        BIGINT      DEFAULT 0         NOT NULL,
    `createdAt`         DATETIME(3)                   NOT NULL,
    `updatedAt`         DATETIME(3)                   NOT NULL,
    `processedAt`       DATETIME(3),
    `lastErrorCode`     VARCHAR(64),
    `lastErrorMessage`  VARCHAR(1000),
    `lastRetriedBy`     VARCHAR(128),
    `lastRetriedAt`     DATETIME(3),
    CONSTRAINT pk_audit_outbox PRIMARY KEY (id),
    CONSTRAINT uq_audit_outbox_event UNIQUE (`eventId`),
    CONSTRAINT ck_audit_outbox_schema CHECK (`schemaVersion` > 0),
    CONSTRAINT ck_audit_outbox_payload CHECK (`payloadBytes` > 0),
    CONSTRAINT ck_audit_outbox_counts CHECK (`attempts` >= 0 AND `failureCount` >= 0 AND `manualRetryCount` >= 0 AND
                                             `rowVersion` >= 0),
    CONSTRAINT ck_audit_outbox_result CHECK (`result` IN ('SUCCESS', 'FAILURE', 'PARTIAL', 'NO_CHANGE', 'UNKNOWN')),
    CONSTRAINT ck_audit_outbox_status CHECK (`status` IN
                                             ('PREPARED', 'PENDING', 'PROCESSING', 'RETRY', 'DONE', 'DEAD')),
    CONSTRAINT ck_audit_outbox_lease CHECK (
        (`status` = 'PROCESSING' AND `workerId` IS NOT NULL AND `leaseToken` IS NOT NULL AND `lockedUntil` IS NOT NULL)
            OR (`status` <> 'PROCESSING' AND `workerId` IS NULL AND `leaseToken` IS NULL AND `lockedUntil` IS NULL)
        ),
    CONSTRAINT ck_audit_outbox_prepare CHECK (
        (`status` = 'PREPARED' AND `prepareDeadlineAt` IS NOT NULL)
            OR (`status` <> 'PREPARED' AND `prepareDeadlineAt` IS NULL)
        ),
    CONSTRAINT ck_audit_outbox_done CHECK (
        (`status` = 'DONE' AND `processedAt` IS NOT NULL)
            OR (`status` <> 'DONE' AND `processedAt` IS NULL)
        ),
    CONSTRAINT ck_audit_outbox_due CHECK (`status` NOT IN ('PENDING', 'RETRY') OR `nextRetryAt` IS NOT NULL),
    CONSTRAINT ck_audit_outbox_manual CHECK (
        (`lastRetriedBy` IS NULL AND `lastRetriedAt` IS NULL)
            OR (`lastRetriedBy` IS NOT NULL AND `lastRetriedAt` IS NOT NULL)
        )
);

CREATE INDEX idx_audit_outbox_due
    ON sys_audit_outbox (`status`, `nextRetryAt`, id);

CREATE INDEX idx_audit_outbox_lease
    ON sys_audit_outbox (`status`, `lockedUntil`, id);

CREATE INDEX idx_audit_outbox_prepare
    ON sys_audit_outbox (`status`, `prepareDeadlineAt`, id);

CREATE INDEX idx_audit_outbox_done
    ON sys_audit_outbox (`status`, `processedAt`, id);

CREATE TABLE sys_operation_log
(
    id                   BIGINT                       NOT NULL AUTO_INCREMENT,
    `eventId`            VARCHAR(36)                  NOT NULL,
    `operationId`        VARCHAR(36)                  NOT NULL,
    `requestId`          VARCHAR(64),
    `traceId`            VARCHAR(64),
    `batchId`            VARCHAR(64),
    `schemaVersion`      INTEGER     DEFAULT 1        NOT NULL,
    `phase`              VARCHAR(32) DEFAULT 'RESULT' NOT NULL,
    `startedAt`          DATETIME(3)                  NOT NULL,
    `occurredAt`         DATETIME(3)                  NOT NULL,
    `indexedAt`          DATETIME(3)                  NOT NULL,
    `durationMs`         BIGINT,
    `actorType`          VARCHAR(16)                  NOT NULL,
    `actorId`            VARCHAR(128),
    `actorAccount`       VARCHAR(64),
    `actorName`          VARCHAR(100)                 NOT NULL,
    `actorRoleCode`      VARCHAR(64),
    `actorRoleName`      VARCHAR(100),
    `claimedAccount`     VARCHAR(64),
    `moduleCode`         VARCHAR(32)                  NOT NULL,
    `operationCode`      VARCHAR(96)                  NOT NULL,
    `action`             VARCHAR(32)                  NOT NULL,
    `summary`            VARCHAR(1000)                NOT NULL,
    `objectCount`        INTEGER     DEFAULT 0        NOT NULL,
    `changedObjectCount` INTEGER     DEFAULT 0        NOT NULL,
    `fieldChangeCount`   INTEGER     DEFAULT 0        NOT NULL,
    `result`             VARCHAR(16)                  NOT NULL,
    `httpStatus`         SMALLINT,
    `businessCode`       VARCHAR(64),
    `errorCode`          VARCHAR(64),
    `errorMessage`       VARCHAR(1000),
    `sourceIp`           VARCHAR(64),
    `requestMethod`      VARCHAR(16),
    `requestPath`        VARCHAR(512),
    `sourceType`         VARCHAR(16)                  NOT NULL,
    `payloadHash`        VARCHAR(64)                  NOT NULL,
    `payloadBytes`       BIGINT                       NOT NULL,
    CONSTRAINT pk_operation_log PRIMARY KEY (id),
    CONSTRAINT uq_operation_log_event UNIQUE (`eventId`),
    CONSTRAINT ck_operation_log_schema CHECK (`schemaVersion` > 0),
    CONSTRAINT ck_operation_log_counts CHECK (`objectCount` >= 0 AND `changedObjectCount` >= 0 AND
                                              `changedObjectCount` <= `objectCount` AND `fieldChangeCount` >= 0 AND
                                              `payloadBytes` > 0),
    CONSTRAINT ck_operation_log_duration CHECK (`durationMs` IS NULL OR `durationMs` >= 0),
    CONSTRAINT ck_operation_log_result CHECK (`result` IN ('SUCCESS', 'FAILURE', 'PARTIAL', 'NO_CHANGE', 'UNKNOWN')),
    CONSTRAINT ck_operation_log_actor CHECK (`actorType` IN ('USER', 'SYSTEM', 'SERVICE', 'ANONYMOUS')),
    CONSTRAINT ck_operation_log_user CHECK (`actorType` <> 'USER' OR
                                            (`actorId` IS NOT NULL AND `actorAccount` IS NOT NULL)),
    CONSTRAINT ck_operation_log_http CHECK (`httpStatus` IS NULL OR (`httpStatus` >= 100 AND `httpStatus` <= 599))
);

CREATE INDEX idx_operation_log_time
    ON sys_operation_log (`occurredAt`, id);

CREATE INDEX idx_operation_log_actor
    ON sys_operation_log (`actorId`, `occurredAt`, id);

CREATE INDEX idx_operation_log_module
    ON sys_operation_log (`moduleCode`, `occurredAt`, id);

CREATE INDEX idx_operation_log_operation
    ON sys_operation_log (`operationId`, id);

CREATE TABLE sys_operation_change
(
    id                 BIGINT                       NOT NULL AUTO_INCREMENT,
    `logId`            BIGINT                       NOT NULL,
    `objectNo`         INTEGER                      NOT NULL,
    `entryType`        VARCHAR(16) DEFAULT 'CHANGE' NOT NULL,
    `objectType`       VARCHAR(64)                  NOT NULL,
    `objectId`         VARCHAR(128)                 NOT NULL,
    `objectCode`       VARCHAR(128),
    `objectName`       VARCHAR(256)                 NOT NULL,
    `parentType`       VARCHAR(64),
    `parentId`         VARCHAR(128),
    `occurredAt`       DATETIME(3)                  NOT NULL,
    `snapshotVersion`  INTEGER     DEFAULT 1        NOT NULL,
    `snapshotScope`    VARCHAR(32)                  NOT NULL,
    `beforeData`       LONGTEXT,
    `afterData`        LONGTEXT,
    `changes`          LONGTEXT                     NOT NULL,
    `fieldChangeCount` INTEGER     DEFAULT 0        NOT NULL,
    CONSTRAINT pk_operation_change PRIMARY KEY (id),
    CONSTRAINT uq_operation_change_no UNIQUE (`logId`, `objectNo`),
    CONSTRAINT fk_operation_change_log FOREIGN KEY (`logId`) REFERENCES sys_operation_log (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operation_change_no CHECK (`objectNo` >= 0 AND `snapshotVersion` > 0 AND `fieldChangeCount` >= 0),
    CONSTRAINT ck_operation_change_type CHECK (`entryType` IN ('CHANGE', 'REFERENCE')),
    CONSTRAINT ck_operation_change_scope CHECK (`snapshotScope` IN ('FULL_BUSINESS_FIELDS', 'CHANGED_FIELDS', 'NONE')),
    CONSTRAINT ck_operation_change_ref CHECK (`entryType` <> 'REFERENCE' OR
                                              (`snapshotScope` = 'NONE' AND `beforeData` IS NULL AND
                                               `afterData` IS NULL AND `fieldChangeCount` = 0)),
    CONSTRAINT ck_operation_change_parent CHECK (
        (`parentType` IS NULL AND `parentId` IS NULL)
            OR (`parentType` IS NOT NULL AND `parentId` IS NOT NULL)
        )
);

CREATE INDEX idx_operation_change_object
    ON sys_operation_change (`objectType`, `objectId`, `occurredAt`, id);

CREATE INDEX idx_operation_change_parent ON sys_operation_change (`parentType`, `parentId`, `occurredAt`, id);

CREATE INDEX idx_audit_outbox_created ON sys_audit_outbox (`status`, `createdAt`, id);
