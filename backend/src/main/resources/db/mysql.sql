SET NAMES utf8mb4;
SET
FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for permission
-- ----------------------------
DROP TABLE IF EXISTS wf_notification_recipient;
DROP TABLE IF EXISTS wf_notification_delivery;
DROP TABLE IF EXISTS wf_definition_notification_ref;
DROP TABLE IF EXISTS sys_in_app_message;
DROP TABLE IF EXISTS sys_notification_scene_group;
DROP TABLE IF EXISTS sys_notification_scene;
DROP TABLE IF EXISTS sys_notification_group_department;
DROP TABLE IF EXISTS sys_notification_group_user;
DROP TABLE IF EXISTS sys_notification_group;
DROP TABLE IF EXISTS biz_doc_generation;
DROP TABLE IF EXISTS biz_doc_template_version;
DROP TABLE IF EXISTS biz_doc_template;
DROP TABLE IF EXISTS `doc_document_version`;
DROP TABLE IF EXISTS `doc_document`;
DROP TABLE IF EXISTS `wb_todo_personal_value`;
DROP TABLE IF EXISTS `wb_todo_personal_dimension`;
DROP TABLE IF EXISTS `wb_todo_business_link`;
DROP TABLE IF EXISTS `wb_todo_business_context`;
DROP TABLE IF EXISTS `wb_todo`;
DROP TABLE IF EXISTS `wb_todo_series`;
DROP TABLE IF EXISTS `biz_institution_change`;
DROP TABLE IF EXISTS `biz_institution`;
DROP TABLE IF EXISTS `wf_instance`;
DROP TABLE IF EXISTS `wf_definition`;
DROP TABLE IF EXISTS `permission`;
CREATE TABLE `permission`
(
    `id`          int(11)                                                       NOT NULL AUTO_INCREMENT,
    `name`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    `nameEn`      varchar(100) NULL     DEFAULT NULL,
    `code`        varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin         NOT NULL,
    `type`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    `parentId`    int(11)                                                       NULL     DEFAULT NULL,
    `path`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `redirect`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `icon`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `component`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `layout`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `keepAlive`   tinyint(4)                                                    NULL     DEFAULT NULL,
    `method`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `show`        tinyint(4)                                                    NOT NULL DEFAULT 1 COMMENT '是否展示在页面菜单',
    `enable`      tinyint(4)                                                    NOT NULL DEFAULT 1,
    `order`       int(11)                                                       NULL     DEFAULT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `IDX_30e166e8c6359970755c5727a2` (`code`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 21
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of permission
-- ----------------------------
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (1, '资源管理', 'Resource_Mgt', 'MENU', 2, '/pms/resource', NULL, 'i-fe:list',
        '/src/views/pms/resource/index.vue', NULL, NULL, NULL, NULL, 1, 1, 1);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (2, '系统管理', 'SysMgt', 'MENU', NULL, NULL, NULL, 'i-fe:grid', NULL, NULL, NULL, NULL, NULL, 1, 1, 2);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (3, '角色管理', 'RoleMgt', 'MENU', 2, '/pms/role', NULL, 'i-fe:user-check', '/src/views/pms/role/index.vue',
        NULL, NULL, NULL, NULL, 1, 1, 2);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (4, '用户管理', 'UserMgt', 'MENU', 2, '/pms/user', NULL, 'i-fe:user', '/src/views/pms/user/index.vue', NULL, 1,
        NULL, NULL, 1, 1, 3);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (5, '分配用户', 'RoleUser', 'MENU', 3, '/pms/role/user/:roleId', NULL, 'i-fe:user-plus',
        '/src/views/pms/role/role-user.vue', 'full', NULL, NULL, NULL, 0, 1, 1);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (6, '业务示例', 'Demo', 'MENU', NULL, NULL, NULL, 'i-fe:grid', NULL, NULL, NULL, NULL, NULL, 1, 1, 1);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (7, '图片上传', 'ImgUpload', 'MENU', 6, '/demo/upload', NULL, 'i-fe:image', '/src/views/demo/upload/index.vue',
        '', 1, NULL, NULL, 1, 1, 2);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (8, '个人资料', 'UserProfile', 'MENU', NULL, '/profile', NULL, 'i-fe:user', '/src/views/profile/index.vue', NULL,
        NULL, NULL, NULL, 0, 1, 99);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (9, '基础功能', 'Base', 'MENU', NULL, '', NULL, 'i-fe:grid', NULL, '', NULL, NULL, NULL, 1, 1, 0);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (10, '基础组件', 'BaseComponents', 'MENU', 9, '/base/components', NULL, 'i-me:awesome',
        '/src/views/base/index.vue', NULL, NULL, NULL, NULL, 1, 1, 1);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (11, 'Unocss', 'Unocss', 'MENU', 9, '/base/unocss', NULL, 'i-me:awesome', '/src/views/base/unocss.vue', NULL,
        NULL, NULL, NULL, 1, 1, 2);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (12, 'KeepAlive', 'KeepAlive', 'MENU', 9, '/base/keep-alive', NULL, 'i-me:awesome',
        '/src/views/base/keep-alive.vue', NULL, 1, NULL, NULL, 1, 1, 3);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (13, '创建新用户', 'AddUser', 'BUTTON', 4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1, 1, 1);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (14, '图标 Icon', 'Icon', 'MENU', 9, '/base/icon', NULL, 'i-fe:feather', '/src/views/base/unocss-icon.vue', '',
        NULL, NULL, NULL, 1, 1, 0);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (15, 'MeModal', 'TestModal', 'MENU', 9, '/testModal', NULL, 'i-me:dialog', '/src/views/base/test-modal.vue',
        NULL, NULL, NULL, NULL, 1, 1, 5);
INSERT INTO `permission` (`id`, `name`, `code`, `type`, `parentId`, `path`, `redirect`, `icon`, `component`, `layout`,
                          `keepAlive`, `method`, `description`, `show`, `enable`, `order`)
VALUES (16, '超管专属', 'SuperAdmin', 'BUTTON', 4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1, 1, 1);

-- ----------------------------
-- Table structure for profile
-- ----------------------------
DROP TABLE IF EXISTS `profile`;
CREATE TABLE `profile`
(
    `id`                   int(11)                                                       NOT NULL AUTO_INCREMENT,
    `gender`               int(11)                                                       NULL     DEFAULT NULL,
    `avatar`               varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',
    `address`              varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `email`                varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `userId`               int(11)                                                       NOT NULL,
    `nickName`             varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL     DEFAULT NULL,
    `colorMode`            varchar(8)                                                    NOT NULL DEFAULT 'auto',
    `primaryColor`         varchar(64)                                                   NOT NULL DEFAULT '#316C72',
    `language`             varchar(8)                                                    NOT NULL DEFAULT 'zh-CN',
    `layout`               varchar(8)                                                    NOT NULL DEFAULT 'normal',
    `tableSize`            varchar(8)                                                    NOT NULL DEFAULT 'medium',
    `layoutSettingVisible` boolean                                                       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `IDX_a24972ebd73b106250713dcddd` (`userId`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of profile
-- ----------------------------

-- ----------------------------
-- Table structure for role
-- ----------------------------
DROP TABLE IF EXISTS `role`;
CREATE TABLE `role`
(
    `id`     int(11)                                               NOT NULL AUTO_INCREMENT,
    `code`   varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `name`   varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `nameEn` varchar(100) NULL     DEFAULT NULL,
    `enable` tinyint(4)                                            NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `IDX_ee999bb389d7ac0fd967172c41` (`code`) USING BTREE,
    UNIQUE INDEX `IDX_ae4578dcaed5adff96595e6166` (`name`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 3
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of role
-- ----------------------------
INSERT INTO `role` (`id`, `code`, `name`, `enable`)
VALUES (1, 'SUPER_ADMIN', '超级管理员', 1);
INSERT INTO `role` (`id`, `code`, `name`, `enable`)
VALUES (2, 'ROLE_QA', '质检员', 1);

-- ----------------------------
-- Table structure for role_permissions_permission
-- ----------------------------
DROP TABLE IF EXISTS `role_permissions_permission`;
CREATE TABLE `role_permissions_permission`
(
    `roleId`       int(11) NOT NULL,
    `permissionId` int(11) NOT NULL,
    PRIMARY KEY (`roleId`, `permissionId`) USING BTREE,
    INDEX          `IDX_b36cb2e04bc353ca4ede00d87b` (`roleId`) USING BTREE,
    INDEX          `IDX_bfbc9e263d4cea6d7a8c9eb3ad` (`permissionId`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of role_permissions_permission
-- ----------------------------
INSERT INTO `role_permissions_permission`
VALUES (2, 1);
INSERT INTO `role_permissions_permission`
VALUES (2, 2);
INSERT INTO `role_permissions_permission`
VALUES (2, 3);
INSERT INTO `role_permissions_permission`
VALUES (2, 4);
INSERT INTO `role_permissions_permission`
VALUES (2, 5);
INSERT INTO `role_permissions_permission`
VALUES (2, 9);
INSERT INTO `role_permissions_permission`
VALUES (2, 10);
INSERT INTO `role_permissions_permission`
VALUES (2, 11);
INSERT INTO `role_permissions_permission`
VALUES (2, 12);
INSERT INTO `role_permissions_permission`
VALUES (2, 14);
INSERT INTO `role_permissions_permission`
VALUES (2, 15);

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS sys_user_mfa;
DROP TABLE IF EXISTS sys_department_member;
DROP TABLE IF EXISTS sys_department;
DROP TABLE IF EXISTS sys_department_write_lock;
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`
(
    `id`                         int(11)                                                       NOT NULL AUTO_INCREMENT,
    `username`                   varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin         NOT NULL,
    `nameCn`                     varchar(100) NULL     DEFAULT NULL,
    `nameEn`                     varchar(100) NULL     DEFAULT NULL,
    `domainAccount`              VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    `mobile`                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `password`                   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    `identityKey`                varchar(36) CHARACTER SET ascii COLLATE ascii_bin             NOT NULL,
    `passwordChangedAt`          DATETIME(3)                                                   NULL,
    `temporaryPasswordExpiresAt` DATETIME(3)                                                   NULL,
    `passwordChangeRequired`     BOOLEAN                                                       NOT NULL DEFAULT FALSE,
    `enable`                     tinyint(4)                                                    NOT NULL DEFAULT 1,
    `createTime`                 datetime(6)                                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updateTime`                 datetime(6)                                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `IDX_78a916df40e02a9deb1c4b75ed` (`username`) USING BTREE,
    UNIQUE INDEX `uk_user_identity` (`identityKey`),
    UNIQUE INDEX `uk_user_domain_account` (`domainAccount`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user
-- ----------------------------

-- ----------------------------
-- Table structure for user_roles_role
-- ----------------------------
DROP TABLE IF EXISTS `user_roles_role`;
CREATE TABLE `user_roles_role`
(
    `userId` int(11) NOT NULL,
    `roleId` int(11) NOT NULL,
    PRIMARY KEY (`userId`, `roleId`) USING BTREE,
    INDEX    `IDX_5f9286e6c25594c6b88c108db7` (`userId`) USING BTREE,
    INDEX    `IDX_4be2f7adf862634f5f803d246b` (`roleId`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_roles_role
-- ----------------------------


-- Protect against concurrent deletion as well as invalid service inputs.
ALTER TABLE `permission`
    ADD CONSTRAINT fk_permission_parent FOREIGN KEY (`parentId`) REFERENCES `permission` (`id`) ON DELETE RESTRICT;
ALTER TABLE `profile`
    ADD CONSTRAINT fk_profile_user FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE;
ALTER TABLE `user_roles_role`
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE;
ALTER TABLE `user_roles_role`
    ADD CONSTRAINT fk_user_role_role FOREIGN KEY (`roleId`) REFERENCES `role` (`id`) ON DELETE CASCADE;
ALTER TABLE `role_permissions_permission`
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (`roleId`) REFERENCES `role` (`id`) ON DELETE CASCADE;
ALTER TABLE `role_permissions_permission`
    ADD CONSTRAINT fk_role_permission_permission FOREIGN KEY (`permissionId`) REFERENCES `permission` (`id`) ON DELETE CASCADE;

-- Unified parameter management
CREATE TABLE IF NOT EXISTS `sys_param_group`
(
    `version`
    BIGINT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '事务内缓存版本',
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `code`
    VARCHAR
(
    64
) COLLATE utf8mb4_bin NOT NULL COMMENT '参数分类编码',
    `name` VARCHAR
(
    100
) NOT NULL COMMENT '参数分类名称',
    `nameEn` varchar
(
    100
) NULL DEFAULT NULL,
    `enable` TINYINT NOT NULL DEFAULT 1,
    `sort` INT NOT NULL DEFAULT 0,
    `remark` VARCHAR
(
    500
) DEFAULT NULL,
    `fieldConfig` JSON DEFAULT NULL COMMENT '固定列配置及扩展字段定义',
    `createdBy` BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedBy` BIGINT DEFAULT NULL COMMENT '更新人用户ID',
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) ON UPDATE CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_param_group_code`
(
    `code`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci COMMENT ='参数分类';

CREATE TABLE IF NOT EXISTS `sys_param_item`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `groupId`
    BIGINT
    NOT
    NULL,
    `code`
    VARCHAR
(
    64
) COLLATE utf8mb4_bin NOT NULL COMMENT '分类内唯一编码',
    `name` VARCHAR
(
    100
) NOT NULL,
    `nameEn` varchar
(
    100
) NULL DEFAULT NULL,
    `value1` VARCHAR
(
    512
) DEFAULT NULL,
    `value2` VARCHAR
(
    512
) DEFAULT NULL,
    `value3` VARCHAR
(
    512
) DEFAULT NULL,
    `value4` VARCHAR
(
    512
) DEFAULT NULL,
    `value5` VARCHAR
(
    512
) DEFAULT NULL,
    `value6` VARCHAR
(
    512
) DEFAULT NULL,
    `value7` VARCHAR
(
    512
) DEFAULT NULL,
    `value8` VARCHAR
(
    512
) DEFAULT NULL,
    `value9` VARCHAR
(
    512
) DEFAULT NULL,
    `extra` JSON DEFAULT NULL COMMENT '扩展属性',
    `enable` TINYINT NOT NULL DEFAULT 1,
    `sort` INT NOT NULL DEFAULT 0,
    `remark` VARCHAR
(
    500
) DEFAULT NULL,
    `createdBy` BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedBy` BIGINT DEFAULT NULL COMMENT '更新人用户ID',
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) ON UPDATE CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_param_item_group_code`
(
    `groupId`,
    `code`
),
    KEY `idx_param_item_group_enable_sort`
(
    `groupId`,
    `enable`,
    `sort`,
    `id`
),
    CONSTRAINT `fk_param_item_group` FOREIGN KEY
(
    `groupId`
) REFERENCES `sys_param_group`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci COMMENT ='参数明细，兼容字典';

CREATE TABLE IF NOT EXISTS `sys_field_definition`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `code`
    VARCHAR
(
    64
) COLLATE utf8mb4_bin NOT NULL COMMENT '字段编码',
    `name` VARCHAR
(
    100
) NOT NULL COMMENT '中文名称',
    `nameEn` VARCHAR
(
    100
) NOT NULL COMMENT '英文名称',
    `dataType` VARCHAR
(
    24
) NOT NULL,
    `required` TINYINT NOT NULL DEFAULT 0,
    `defaultValue` JSON DEFAULT NULL,
    `textMinLength` INT DEFAULT NULL,
    `textMaxLength` INT DEFAULT NULL,
    `textFormat` VARCHAR
(
    32
) DEFAULT NULL,
    `textPattern` VARCHAR
(
    512
) DEFAULT NULL,
    `textCharLimitEnabled` TINYINT NOT NULL DEFAULT 0,
    `textAllowChinese` TINYINT NOT NULL DEFAULT 0,
    `textAllowEnglish` TINYINT NOT NULL DEFAULT 0,
    `textAllowDigits` TINYINT NOT NULL DEFAULT 0,
    `textAllowSpace` TINYINT NOT NULL DEFAULT 0,
    `textAllowSeparators` TINYINT NOT NULL DEFAULT 0,
    `textAllowChinesePunctuation` TINYINT NOT NULL DEFAULT 0,
    `textAllowEnglishPunctuation` TINYINT NOT NULL DEFAULT 0,
    `numberPositiveOnly` TINYINT NOT NULL DEFAULT 0,
    `integerMin` BIGINT DEFAULT NULL,
    `integerMax` BIGINT DEFAULT NULL,
    `decimalMin` DECIMAL
(
    38,
    18
) DEFAULT NULL,
    `decimalMax` DECIMAL
(
    38,
    18
) DEFAULT NULL,
    `decimalPrecision` TINYINT DEFAULT NULL,
    `decimalScale` TINYINT DEFAULT NULL,
    `dateMin` DATE DEFAULT NULL,
    `dateMax` DATE DEFAULT NULL,
    `dateTimeMin` DATETIME
(
    3
) DEFAULT NULL,
    `dateTimeMax` DATETIME
(
    3
) DEFAULT NULL,
    `paramGroupId` BIGINT DEFAULT NULL,
    `enumMinItems` INT DEFAULT NULL,
    `enumMaxItems` INT DEFAULT NULL,
    `enable` TINYINT NOT NULL DEFAULT 1,
    `remark` VARCHAR
(
    500
) DEFAULT NULL,
    `createdBy` BIGINT DEFAULT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedBy` BIGINT DEFAULT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) ON UPDATE CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `id`
),
    CONSTRAINT `uk_field_definition_code` UNIQUE
(
    `code`
),
    KEY `idx_field_definition_param_group`
(
    `paramGroupId`
),
    CONSTRAINT `fk_field_definition_param_group`
    FOREIGN KEY
(
    `paramGroupId`
) REFERENCES `sys_param_group`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci COMMENT ='字段定义';

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`)
SELECT 'FIELD_DATA_TYPE',
       '字段数据类型',
       'Field Data Types' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'FIELD_DATA_TYPE');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'TEXT', '文本', 'Text', 10
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'TEXT');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'INTEGER', '整数', 'Integer', 20
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'INTEGER');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'DECIMAL', '小数', 'Decimal', 30
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DECIMAL');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'BOOLEAN', '布尔', 'Boolean', 40
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'BOOLEAN');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'DATE', '日期', 'Date', 50
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DATE');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'DATETIME', '日期时间', 'Date and Time', 60
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DATETIME');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'ENUM', '枚举单选', 'Single Select', 70
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'ENUM');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'ENUM_MULTI', '枚举多选', 'Multiple Select', 80
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'ENUM_MULTI');

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`)
SELECT 'FIELD_TEXT_FORMAT',
       '字段文本格式',
       'Field Text Formats' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'FIELD_TEXT_FORMAT');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'EMAIL', '邮箱', 'Email', 10
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_FORMAT'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'EMAIL');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'URL', '网址', 'URL', 20
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_FORMAT'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'URL');

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`)
SELECT 'FIELD_TEXT_CHARACTER',
       '字段文本字符类别',
       'Field Text Character Classes' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'FIELD_TEXT_CHARACTER');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'CHINESE', '中文', 'Chinese', 10
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'CHINESE');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'ENGLISH', '英文字母', 'English Letters', 20
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'ENGLISH');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'DIGITS', '数字', 'Digits', 30
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIGITS');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'SPACE', '空格', 'Space', 40
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'SPACE');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'SEPARATORS', '常用连接符', 'Separators', 50
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'SEPARATORS');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'CHINESE_PUNCTUATION', '中文标点', 'Chinese Punctuation', 60
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'CHINESE_PUNCTUATION');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'ENGLISH_PUNCTUATION', '英文标点', 'English Punctuation', 70
FROM `sys_param_group` g
WHERE g.code = 'FIELD_TEXT_CHARACTER'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'ENGLISH_PUNCTUATION');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '字段管理',
       'Field Management',
       'FieldMgt',
       'MENU',
       p.id,
       '/pms/field',
       'i-fe:list',
       '/src/views/pms/field/index.vue',
       1,
       1,
       10
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'FieldMgt');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '新增字段',
       'Create Field',
       'FieldCreate',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'FieldMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'FieldCreate');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '编辑字段',
       'Edit Field',
       'FieldEdit',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'FieldMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'FieldEdit');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '删除字段',
       'Delete Field',
       'FieldDelete',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'FieldMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'FieldDelete');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`, `order`)
SELECT '参数管理',
       'ParamMgt',
       'MENU',
       p.id,
       '/pms/param',
       'i-fe:sliders',
       '/src/views/pms/param/index.vue',
       1,
       1,
       4
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamMgt');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '新建参数', 'ParamCreate', 'BUTTON', p.id, 1, 1, 1
FROM `permission` p
WHERE p.code = 'ParamMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamCreate');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '编辑参数', 'ParamEdit', 'BUTTON', p.id, 1, 1, 2
FROM `permission` p
WHERE p.code = 'ParamMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamEdit');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '删除参数', 'ParamDelete', 'BUTTON', p.id, 1, 1, 3
FROM `permission` p
WHERE p.code = 'ParamMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamDelete');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '参数排序', 'ParamSort', 'BUTTON', p.id, 1, 1, 4
FROM `permission` p
WHERE p.code = 'ParamMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamSort');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '刷新参数缓存', 'ParamRefresh', 'BUTTON', p.id, 1, 1, 5
FROM `permission` p
WHERE p.code = 'ParamMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'ParamRefresh');

SET
FOREIGN_KEY_CHECKS = 1;


-- Audit tables for a fresh development database. Incremental deployments use the migration.
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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE INDEX idx_operation_change_object
    ON sys_operation_change (`objectType`, `objectId`, `occurredAt`, id);

CREATE INDEX idx_operation_change_parent ON sys_operation_change (`parentType`, `parentId`, `occurredAt`, id);

CREATE INDEX idx_audit_outbox_created ON sys_audit_outbox (`status`, `createdAt`, id);

-- Audit viewer capabilities are separate. Grant the menu and required buttons to a role explicitly.
INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`, `order`)
SELECT '操作日志',
       'AuditView',
       'MENU',
       p.id,
       '/pms/audit',
       'i-fe:file-text',
       '/src/views/pms/audit/index.vue',
       1,
       1,
       5
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'AuditView');
INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '查看日志详情', 'AuditDetail', 'BUTTON', p.id, 1, 1, 1
FROM `permission` p
WHERE p.code = 'AuditView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'AuditDetail');
INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '导出操作日志', 'AuditExport', 'BUTTON', p.id, 1, 1, 2
FROM `permission` p
WHERE p.code = 'AuditView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'AuditExport');
INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '查看并重试投递', 'AuditRetry', 'BUTTON', p.id, 1, 1, 3
FROM `permission` p
WHERE p.code = 'AuditView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'AuditRetry');


CREATE TABLE IF NOT EXISTS sys_user_mfa
(
    user_id
    INT
    NOT
    NULL,
    binding_id
    VARCHAR
(
    36
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    personal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    secret_ciphertext VARCHAR
(
    128
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_version VARCHAR
(
    32
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_used_step BIGINT NOT NULL,
    recovery_hashes JSON NOT NULL,
    PRIMARY KEY
(
    user_id
),
    CONSTRAINT fk_user_mfa_user FOREIGN KEY
(
    user_id
) REFERENCES `user`
(
    id
) ON DELETE CASCADE,
    CONSTRAINT ck_user_mfa_recovery CHECK
(
    JSON_TYPE
(
    recovery_hashes
) = 'ARRAY')
    ) ENGINE = InnoDB;

CREATE INDEX idx_user_mfa_key_version ON sys_user_mfa (key_version, user_id);

DROP TABLE IF EXISTS sys_mfa_key_rotation;
CREATE TABLE IF NOT EXISTS sys_mfa_key_rotation
(
    id
    INT
    NOT
    NULL,
    active_key_id
    VARCHAR
(
    32
) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_since DATETIME
(
    3
) NULL,
    last_completed_at DATETIME
(
    3
) NULL,
    status VARCHAR
(
    16
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NULL,
    used_versions JSON NOT NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT ck_mfa_rotation_singleton CHECK
(
    id =
    1
),
    CONSTRAINT ck_mfa_rotation_status CHECK
(
    status
    IN
(
    'IDLE',
    'RUNNING',
    'COMPLETED',
    'FAILED'
))
    ) ENGINE = InnoDB;

INSERT INTO sys_mfa_key_rotation (id, active_key_id, active_since, status, used_versions)
SELECT 1,
       NULL,
       NULL,
       'IDLE',
       '[]' WHERE NOT EXISTS (SELECT 1 FROM sys_mfa_key_rotation WHERE id = 1);

DROP TABLE IF EXISTS sys_mfa_key_settings;
DROP TABLE IF EXISTS sys_mfa_key_material;
CREATE TABLE IF NOT EXISTS sys_mfa_key_material
(
    key_id
    VARCHAR
(
    32
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_ciphertext VARCHAR
(
    128
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME
(
    3
) NOT NULL,
    published_at DATETIME
(
    3
) NULL,
    PRIMARY KEY
(
    key_id
)
    ) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sys_mfa_key_settings
(
    id
    INT
    NOT
    NULL,
    revision
    BIGINT
    NOT
    NULL
    DEFAULT
    0,
    target_key_id
    VARCHAR
(
    32
) CHARACTER SET ascii COLLATE ascii_bin NULL,
    published_at DATETIME
(
    3
) NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT ck_mfa_key_settings_singleton CHECK
(
    id =
    1
)
    ) ENGINE = InnoDB;

INSERT INTO sys_mfa_key_settings (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM sys_mfa_key_settings WHERE id = 1);

DROP TABLE IF EXISTS sys_system_policy;
CREATE TABLE IF NOT EXISTS sys_system_policy
(
    policy_code
    VARCHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config JSON NOT NULL,
    schema_version INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME
(
    3
) NOT NULL,
    updated_at DATETIME
(
    3
) NOT NULL,
    updated_by BIGINT DEFAULT NULL,
    PRIMARY KEY
(
    policy_code
),
    CONSTRAINT ck_system_policy_config CHECK
(
    JSON_TYPE
(
    config
) = 'OBJECT'),
    CONSTRAINT ck_system_policy_schema_version CHECK
(
    schema_version >
    0
),
    CONSTRAINT ck_system_policy_revision CHECK
(
    revision >
    0
)
    ) ENGINE = InnoDB;

-- 只补齐缺失的默认策略，保留已保存的配置和版本。
INSERT INTO sys_system_policy (policy_code, config, schema_version, revision, created_at, updated_at)
SELECT 'AUTH_SECURITY',
       '{
          "password": {"minLength": 12, "requiredCharacterTypes": ["uppercase", "lowercase", "digit", "special"], "rejectUsername": true, "rejectCurrent": true, "maxAgeSeconds": 0, "temporaryTtlSeconds": 86400},
          "bootstrap": {"requirePasswordChange": true},
          "session": {"idleTimeoutSeconds": 1800, "absoluteTimeoutSeconds": 28800, "allowConcurrent": true},
          "captcha": {"required": true, "ttlSeconds": 60},
          "rateLimit": {"enabled": true, "windowSeconds": 60, "loginPerIp": 30, "loginPerAccount": 10, "captchaPerIp": 30, "changePasswordPerAccount": 5},
          "login": {"localEnabled": true, "domainEnabled": false},
          "mfa": {"required": false, "keyRotationDays": 365, "keyReminderDays": 30}
      }',
       4,
       1,
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3) WHERE NOT EXISTS (SELECT 1 FROM sys_system_policy WHERE policy_code = 'AUTH_SECURITY');

INSERT INTO `permission` (`name`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`, `order`)
SELECT '认证安全策略',
       'AuthPolicyMgt',
       'MENU',
       p.id,
       '/pms/auth-policy',
       'i-fe:shield',
       '/src/views/pms/auth-policy/index.vue',
       1,
       1,
       6
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'AuthPolicyMgt');

-- Organization structure
CREATE TABLE sys_department
(
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id      BIGINT NULL,
    name           VARCHAR(100) NOT NULL,
    sort_order     INT          NOT NULL DEFAULT 0,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    leader_user_id INT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES sys_department (id),
    CONSTRAINT fk_department_leader FOREIGN KEY (leader_user_id) REFERENCES `user` (id),
    CONSTRAINT ck_department_sort CHECK (sort_order >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
CREATE INDEX idx_department_parent_sort ON sys_department (parent_id, sort_order, id);
CREATE INDEX idx_department_leader ON sys_department (leader_user_id);
ALTER TABLE `user`
    ADD COLUMN `departmentId` BIGINT NULL;
CREATE INDEX idx_user_department ON `user` (`departmentId`, `enable`, id);
ALTER TABLE `user`
    ADD COLUMN `departmentVersion` BIGINT NOT NULL DEFAULT 0;

CREATE TABLE sys_department_member
(
    user_id       INT    NOT NULL,
    department_id BIGINT NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, department_id),
    CONSTRAINT fk_department_member_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_department_member_department FOREIGN KEY (department_id) REFERENCES sys_department (id)
) ENGINE = InnoDB;
CREATE INDEX idx_department_member_department ON sys_department_member (department_id, user_id);
CREATE INDEX idx_user_primary_membership ON `user` (id, `departmentId`);
ALTER TABLE `user`
    ADD CONSTRAINT fk_user_primary_membership
        FOREIGN KEY (id, `departmentId`) REFERENCES sys_department_member (user_id, department_id);

CREATE TABLE sys_department_write_lock
(
    id INT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_department_write_lock CHECK (id = 1)
) ENGINE = InnoDB;
INSERT INTO sys_department_write_lock (id)
VALUES (1);

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '组织架构',
       'Organization',
       'DepartmentView',
       'MENU',
       id,
       '/pms/department',
       'i-fe:users',
       '/src/views/pms/department/index.vue',
       1,
       1,
       7
FROM `permission`
WHERE code = 'SysMgt';
INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '维护部门',
       'Manage departments',
       'DepartmentEdit',
       'BUTTON',
       id,
       1,
       1,
       1
FROM `permission`
WHERE code = 'DepartmentView';
INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '维护负责人',
       'Manage department leaders',
       'DepartmentLeader',
       'BUTTON',
       id,
       1,
       1,
       2
FROM `permission`
WHERE code = 'DepartmentView';
INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '调整部门成员',
       'Manage department members',
       'DepartmentMembers',
       'BUTTON',
       id,
       1,
       1,
       3
FROM `permission`
WHERE code = 'DepartmentView';

-- 交易日历：市场由参数管理提供。
CREATE TABLE IF NOT EXISTS `sys_trading_calendar_day`
(
    `marketItemId`
    BIGINT
    NOT
    NULL,
    `calendarDate`
    DATE
    NOT
    NULL,
    `calendarDate8`
    CHAR
(
    8
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `isTradingDay` TINYINT NOT NULL,
    `holidayName` VARCHAR
(
    100
) DEFAULT NULL,
    `source` VARCHAR
(
    24
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `remark` VARCHAR
(
    500
) DEFAULT NULL,
    `createdBy` BIGINT DEFAULT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedBy` BIGINT DEFAULT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) ON UPDATE CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `marketItemId`,
    `calendarDate`
),
    CONSTRAINT `fk_trading_calendar_market_item`
    FOREIGN KEY
(
    `marketItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `ck_trading_calendar_status` CHECK
(
    `isTradingDay`
    IN
(
    0,
    1
)),
    CONSTRAINT `ck_trading_calendar_source` CHECK
(
    `source`
    IN
(
    'INIT',
    'EXCEL',
    'MANUAL'
)),
    CONSTRAINT `ck_trading_calendar_date8` CHECK
(
    `calendarDate8` =
    CONCAT(
    LPAD(
    YEAR
(
    `calendarDate`
), 4, '0'),
    LPAD
(
    MONTH
(
    `calendarDate`
), 2, '0'), LPAD
(
    DAYOFMONTH
(
    `calendarDate`
), 2, '0')))
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`, `fieldConfig`)
SELECT 'TRADING_MARKET',
       '交易市场',
       'Trading Markets',
       '{"fixedFields":{"value1":{"label":"时区","type":"text","required":true,"showInTable":true},"value2":{"label":"图标键","type":"text","required":true,"showInTable":true}}}' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'TRADING_MARKET');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `value2`, `sort`)
SELECT g.id, 'CN', '中国内地', 'Mainland China', 'Asia/Shanghai', 'cn', 10
FROM `sys_param_group` g
WHERE g.code = 'TRADING_MARKET'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'CN');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `value2`, `sort`)
SELECT g.id, 'HK', '中国香港', 'Hong Kong', 'Asia/Hong_Kong', 'hk', 20
FROM `sys_param_group` g
WHERE g.code = 'TRADING_MARKET'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'HK');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `value2`, `sort`)
SELECT g.id, 'US', '美国', 'United States', 'America/New_York', 'us', 30
FROM `sys_param_group` g
WHERE g.code = 'TRADING_MARKET'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'US');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `value2`, `sort`)
SELECT g.id, 'SG', '新加坡', 'Singapore', 'Asia/Singapore', 'sg', 40
FROM `sys_param_group` g
WHERE g.code = 'TRADING_MARKET'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'SG');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `value2`, `sort`)
SELECT g.id, 'JP', '日本', 'Japan', 'Asia/Tokyo', 'jp', 50
FROM `sys_param_group` g
WHERE g.code = 'TRADING_MARKET'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'JP');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `icon`, `show`, `enable`, `order`)
SELECT '业务信息',
       'Business Information',
       'BusinessInfo',
       'MENU',
       'i-fe:briefcase',
       1,
       1,
       3 WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'BusinessInfo');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '交易日历',
       'Trading Calendar',
       'TradingCalendarMgt',
       'MENU',
       p.id,
       '/pms/trading-calendar',
       'i-fe:calendar',
       '/src/views/pms/trading-calendar/index.vue',
       1,
       1,
       11
FROM `permission` p
WHERE p.code = 'BusinessInfo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'TradingCalendarMgt');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '初始化交易日历',
       'Initialize trading calendar',
       'TradingCalendarInit',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'TradingCalendarMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'TradingCalendarInit');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '设置交易日历',
       'Edit trading calendar',
       'TradingCalendarEdit',
       'BUTTON',
       p.id,
       0,
       1,
       2
FROM `permission` p
WHERE p.code = 'TradingCalendarMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'TradingCalendarEdit');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '导入交易日历',
       'Import trading calendar',
       'TradingCalendarImport',
       'BUTTON',
       p.id,
       0,
       1,
       3
FROM `permission` p
WHERE p.code = 'TradingCalendarMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'TradingCalendarImport');

-- 工作台待办、周期及流程。业务人员 ID 与名称为历史快照，个人标记随账号删除。
CREATE TABLE IF NOT EXISTS `wb_todo_series`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `ownerUserId`
    INT
    NOT
    NULL,
    `ownerName`
    VARCHAR
(
    100
) NOT NULL,
    `assigneeUserId` INT NOT NULL,
    `title` VARCHAR
(
    200
) NOT NULL,
    `description` TEXT,
    `priority` VARCHAR
(
    12
) NOT NULL DEFAULT 'NORMAL',
    `frequency` VARCHAR
(
    16
) NOT NULL,
    `intervalValue` INT NOT NULL,
    `weekdaysMask` SMALLINT DEFAULT NULL,
    `monthDayMode` VARCHAR
(
    12
) DEFAULT NULL,
    `anchorDate` DATE NOT NULL,
    `untilDate` DATE DEFAULT NULL,
    `durationDays` INT DEFAULT NULL,
    `dueOffsetDays` INT DEFAULT NULL,
    `marketItemId` BIGINT DEFAULT NULL,
    `startTradeOffset` INT DEFAULT NULL,
    `endTradeOffset` INT DEFAULT NULL,
    `dueTradeOffset` INT DEFAULT NULL,
    `dimension1ItemId` BIGINT DEFAULT NULL,
    `dimension2ItemId` BIGINT DEFAULT NULL,
    `dimension3ItemId` BIGINT DEFAULT NULL,
    `dimension4ItemId` BIGINT DEFAULT NULL,
    `dimension5ItemId` BIGINT DEFAULT NULL,
    `status` VARCHAR
(
    12
) NOT NULL DEFAULT 'ACTIVE',
    `ruleRevision` INT NOT NULL DEFAULT 1,
    `lastBaseDate` DATE DEFAULT NULL,
    `nextBaseDate` DATE DEFAULT NULL,
    `effectiveFromDate` DATE NOT NULL,
    `nextCheckAt` DATETIME
(
    3
) DEFAULT NULL,
    `blockedDate` DATE DEFAULT NULL,
    `blockReason` VARCHAR
(
    32
) DEFAULT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `id`
),
    KEY `idx_todo_series_due`
(
    `status`,
    `nextCheckAt`,
    `id`
),
    KEY `idx_todo_series_owner`
(
    `ownerUserId`,
    `status`,
    `id`
),
    CONSTRAINT `ck_todo_series_interval` CHECK
(
    `intervalValue`
    >=
    1
),
    CONSTRAINT `fk_todo_series_market` FOREIGN KEY
(
    `marketItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_series_dimension1` FOREIGN KEY
(
    `dimension1ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_series_dimension2` FOREIGN KEY
(
    `dimension2ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_series_dimension3` FOREIGN KEY
(
    `dimension3ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_series_dimension4` FOREIGN KEY
(
    `dimension4ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_series_dimension5` FOREIGN KEY
(
    `dimension5ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `wb_todo`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `title`
    VARCHAR
(
    200
) NOT NULL,
    `description` TEXT,
    `origin` VARCHAR
(
    16
) NOT NULL,
    `priority` VARCHAR
(
    12
) NOT NULL DEFAULT 'NORMAL',
    `status` VARCHAR
(
    16
) NOT NULL DEFAULT 'OPEN',
    `ownerUserId` INT DEFAULT NULL,
    `ownerName` VARCHAR
(
    100
) DEFAULT NULL,
    `departmentId` BIGINT DEFAULT NULL,
    `assigneeUserId` INT NOT NULL,
    `assigneeName` VARCHAR
(
    100
) NOT NULL,
    `sourceModule` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `businessType` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `businessId` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `requestScope` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `requestKey` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `requestHash` CHAR
(
    64
) DEFAULT NULL,
    `scheduleMode` VARCHAR
(
    12
) NOT NULL,
    `startDate` DATE DEFAULT NULL,
    `endDate` DATE DEFAULT NULL,
    `dueDate` DATE DEFAULT NULL,
    `marketItemId` BIGINT DEFAULT NULL,
    `tradeBaseDate` DATE DEFAULT NULL,
    `startTradeOffset` INT DEFAULT NULL,
    `endTradeOffset` INT DEFAULT NULL,
    `dueTradeOffset` INT DEFAULT NULL,
    `dimension1ItemId` BIGINT DEFAULT NULL,
    `dimension2ItemId` BIGINT DEFAULT NULL,
    `dimension3ItemId` BIGINT DEFAULT NULL,
    `dimension4ItemId` BIGINT DEFAULT NULL,
    `dimension5ItemId` BIGINT DEFAULT NULL,
    `seriesId` BIGINT DEFAULT NULL,
    `seriesRevision` INT DEFAULT NULL,
    `seriesBaseDate` DATE DEFAULT NULL,
    `occurrenceDate` DATE DEFAULT NULL,
    `occurrenceEdited` BOOLEAN NOT NULL DEFAULT FALSE,
    `closedAt` DATETIME
(
    3
) DEFAULT NULL,
    `closedBy` INT DEFAULT NULL,
    `cancelKind` VARCHAR
(
    16
) DEFAULT NULL,
    `cancelReason` VARCHAR
(
    500
) DEFAULT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_todo_request`
(
    `requestScope`,
    `requestKey`
),
    UNIQUE KEY `uk_todo_occurrence`
(
    `seriesId`,
    `seriesBaseDate`,
    `seriesRevision`
),
    KEY `idx_todo_series_occurrence`
(
    `seriesId`,
    `occurrenceDate`
),
    KEY `idx_todo_business`
(
    `sourceModule`,
    `businessType`,
    `businessId`
),
    KEY `idx_todo_assignee`
(
    `assigneeUserId`,
    `status`,
    `dueDate`,
    `id`
),
    KEY `idx_todo_owner`
(
    `ownerUserId`,
    `status`,
    `createdAt`,
    `id`
),
    KEY `idx_todo_assignee_plan`
(
    `assigneeUserId`,
    `endDate`,
    `startDate`
),
    KEY `idx_todo_assignee_due`
(
    `assigneeUserId`,
    `dueDate`
),
    CONSTRAINT `ck_todo_plan_dates` CHECK
(
(
    `startDate`
    IS
    NULL
    AND
    `endDate`
    IS
    NULL
)
    OR
(
    `startDate`
    IS
    NOT
    NULL
    AND
    `endDate`
    IS
    NOT
    NULL
    AND
    `endDate`
    >=
    `startDate`
)
    ),
    CONSTRAINT `fk_todo_series` FOREIGN KEY
(
    `seriesId`
) REFERENCES `wb_todo_series`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_market` FOREIGN KEY
(
    `marketItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_dimension1` FOREIGN KEY
(
    `dimension1ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_dimension2` FOREIGN KEY
(
    `dimension2ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_dimension3` FOREIGN KEY
(
    `dimension3ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_dimension4` FOREIGN KEY
(
    `dimension4ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_todo_dimension5` FOREIGN KEY
(
    `dimension5ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `wb_todo_business_context`
(
    `todoId`
    BIGINT
    NOT
    NULL
    COMMENT
    '待办 ID，引用 wb_todo.id，每条待办最多保存一份业务上下文',
    `recordModule`
    VARCHAR
(
    64
) NOT NULL COMMENT '所属单据的业务模块编码，用于定位单据查询和授权策略',
    `recordType` VARCHAR
(
    64
) NOT NULL COMMENT '所属单据的业务对象类型编码，与模块编码共同标识对象种类',
    `recordId` VARCHAR
(
    128
) NOT NULL COMMENT '所属单据的稳定主键，按原始大小写保存的字符串',
    `taskKindCode` VARCHAR
(
    64
) NOT NULL COMMENT '待办在所属单据中的任务环节编码，用于场景筛选和解析展示名称',
    `createdAt` DATETIME
(
    3
) NOT NULL COMMENT '业务上下文首次写入时间，UTC 毫秒精度，历史补写时记录实际写入时间',
    PRIMARY KEY
(
    `todoId`
),
    KEY `idx_todo_business_context_record`
(
    `recordModule`,
    `recordType`,
    `recordId`,
    `todoId`
),
    CONSTRAINT `fk_todo_business_context_todo` FOREIGN KEY
(
    `todoId`
)
    REFERENCES `wb_todo`
(
    `id`
) ON DELETE CASCADE ON UPDATE RESTRICT
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin
    COMMENT = '待办业务上下文，记录用于查询和授权的所属单据及任务环节';

CREATE TABLE IF NOT EXISTS `wb_todo_business_link`
(
    `todoId`
    BIGINT
    NOT
    NULL
    COMMENT
    '待办 ID，引用 wb_todo_business_context.todoId，用于定位所属单据和待办',
    `refModule`
    VARCHAR
(
    64
) NOT NULL COMMENT '归集对象的业务模块编码',
    `refType` VARCHAR
(
    64
) NOT NULL COMMENT '归集对象的业务对象类型编码，与模块编码共同标识对象种类',
    `refId` VARCHAR
(
    128
) NOT NULL COMMENT '归集对象的稳定主键，按原始大小写保存的字符串',
    `createdAt` DATETIME
(
    3
) NOT NULL COMMENT '归集关系首次写入时间，UTC 毫秒精度，历史补写时记录实际写入时间',
    PRIMARY KEY
(
    `todoId`,
    `refModule`,
    `refType`,
    `refId`
),
    KEY `idx_todo_business_link_ref`
(
    `refModule`,
    `refType`,
    `refId`,
    `todoId`
),
    CONSTRAINT `fk_todo_business_link_context` FOREIGN KEY
(
    `todoId`
)
    REFERENCES `wb_todo_business_context`
(
    `todoId`
) ON DELETE CASCADE ON UPDATE RESTRICT
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin
    COMMENT = '待办业务归集关系，支持从多个业务对象查询同一条待办';

CREATE TABLE IF NOT EXISTS `wb_todo_personal_dimension`
(
    `userId`
    INT
    NOT
    NULL,
    `slotNo`
    SMALLINT
    NOT
    NULL,
    `name`
    VARCHAR
(
    100
) NOT NULL,
    `optionsJson` LONGTEXT NOT NULL,
    `enable` BOOLEAN NOT NULL DEFAULT FALSE,
    `version` BIGINT NOT NULL DEFAULT 0,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `userId`,
    `slotNo`
),
    CONSTRAINT `ck_todo_personal_slot` CHECK
(
    `slotNo`
    BETWEEN
    1
    AND
    5
),
    CONSTRAINT `fk_todo_personal_user` FOREIGN KEY
(
    `userId`
) REFERENCES `user`
(
    `id`
) ON DELETE CASCADE
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `wb_todo_personal_value`
(
    `userId`
    INT
    NOT
    NULL,
    `todoId`
    BIGINT
    NOT
    NULL,
    `slotNo`
    SMALLINT
    NOT
    NULL,
    `optionKey`
    VARCHAR
(
    100
) COLLATE utf8mb4_0900_bin NOT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    PRIMARY KEY
(
    `userId`,
    `todoId`,
    `slotNo`
),
    KEY `idx_todo_personal_filter`
(
    `userId`,
    `slotNo`,
    `optionKey`,
    `todoId`
),
    CONSTRAINT `fk_todo_personal_value_todo` FOREIGN KEY
(
    `todoId`
) REFERENCES `wb_todo`
(
    `id`
) ON DELETE CASCADE,
    CONSTRAINT `fk_todo_personal_value_slot` FOREIGN KEY
(
    `userId`,
    `slotNo`
)
    REFERENCES `wb_todo_personal_dimension`
(
    `userId`,
    `slotNo`
) ON DELETE CASCADE
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `wf_definition`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `code`
    VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定的模板编码；同一编码可有多个修订版本',
    `revision` INT NOT NULL COMMENT '同一模板编码下的修订号，从 1 递增',
    `name` VARCHAR
(
    200
) NOT NULL COMMENT '模板名称',
    `status` VARCHAR
(
    12
) NOT NULL DEFAULT 'DRAFT' COMMENT '模板状态：DRAFT 草稿、PUBLISHED 已发布、RETIRED 已停用',
    `stepsJson` LONGTEXT NOT NULL COMMENT '节点定义 JSON；版本 1 为固定步骤，版本 2 为两级角色节点',
    `createdBy` INT NOT NULL COMMENT '创建者用户 ID；可选示例脚本记录为 0',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '创建时间',
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '最后更新时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '用于防止并发覆盖的版本号',
    `schemaVersion` SMALLINT NOT NULL DEFAULT 1 COMMENT '定义数据格式版本：1 固定步骤，2 两级角色节点',
    `fieldsJson` LONGTEXT DEFAULT NULL COMMENT '字段配置 JSON；草稿存字段编码，已发布版本含校验规则及字典快照',
    `publishedAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '首次发布时间（UTC）；草稿或历史发布时间未知时为空',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_definition_revision`
(
    `code`,
    `revision`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='流程模板及发布版本';

CREATE TABLE IF NOT EXISTS `wf_instance`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `definitionId`
    BIGINT
    NOT
    NULL
    COMMENT
    '发起时锁定的模板版本 ID',
    `title`
    VARCHAR
(
    200
) NOT NULL COMMENT '流程实例标题',
    `ownerUserId` INT DEFAULT NULL COMMENT '发起人用户 ID；无用户上下文的业务发起可为空',
    `ownerName` VARCHAR
(
    100
) DEFAULT NULL COMMENT '发起人显示名称快照',
    `sourceModule` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '已登记的业务模块编码；工作台独立发起时为空',
    `businessType` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '来源业务类型编码；例如 PURCHASE_ORDER',
    `businessId` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '来源业务记录 ID，以字符串保存；同一个业务单可以有多次流程',
    `requestScope` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '发起幂等范围；工作台为 USER:用户ID，业务为 MODULE:模块编码',
    `requestKey` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '发起方为本次业务动作生成的稳定幂等键',
    `requestHash` CHAR
(
    64
) NOT NULL COMMENT '用于检查同键重试的规范化请求 SHA-256 摘要',
    `stepsStateJson` LONGTEXT NOT NULL COMMENT '版本 1 节点运行状态 JSON；版本 2 写空数组，运行状态见节点明细',
    `currentStepNo` INT NOT NULL COMMENT '当前一级节点顺序，从 1 开始',
    `status` VARCHAR
(
    12
) NOT NULL DEFAULT 'RUNNING' COMMENT '实例状态：RUNNING 进行中、DONE 已完成、CANCELLED 已取消',
    `dimension1ItemId` BIGINT DEFAULT NULL COMMENT '统一待办公共维度槽位 1 的参数项 ID',
    `dimension2ItemId` BIGINT DEFAULT NULL COMMENT '统一待办公共维度槽位 2 的参数项 ID',
    `dimension3ItemId` BIGINT DEFAULT NULL COMMENT '统一待办公共维度槽位 3 的参数项 ID',
    `dimension4ItemId` BIGINT DEFAULT NULL COMMENT '统一待办公共维度槽位 4 的参数项 ID',
    `dimension5ItemId` BIGINT DEFAULT NULL COMMENT '统一待办公共维度槽位 5 的参数项 ID',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '用于防止并发覆盖的版本号',
    `startedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '实际发起时间',
    `closedAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '结束时间',
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '最后更新时间',
    `fieldValuesJson` LONGTEXT DEFAULT NULL COMMENT '流程字段当前值 JSON；键为字段编码，附件值为文档版本引用',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_instance_request`
(
    `requestScope`,
    `requestKey`
),
    KEY `idx_wf_instance_owner`
(
    `ownerUserId`,
    `status`,
    `startedAt`
),
    -- 支持业务模块按来源定位实例，也允许同一业务单授权后再次发起。
    KEY `idx_wf_instance_source`
(
    `sourceModule`,
    `businessType`,
    `businessId`,
    `id`
),
    CONSTRAINT `fk_wf_instance_definition` FOREIGN KEY
(
    `definitionId`
) REFERENCES `wf_definition`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_instance_dimension1` FOREIGN KEY
(
    `dimension1ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_instance_dimension2` FOREIGN KEY
(
    `dimension2ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_instance_dimension3` FOREIGN KEY
(
    `dimension3ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_instance_dimension4` FOREIGN KEY
(
    `dimension4ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_instance_dimension5` FOREIGN KEY
(
    `dimension5ItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='流程实例、业务来源和当前字段值';

-- 同一个节点的每次重开增加轮次，历史行用于审批记录和精准退回。
CREATE TABLE IF NOT EXISTS `wf_node_instance`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `flowInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属流程实例 ID',
    `nodeKey`
    VARCHAR
(
    64
) NOT NULL COMMENT '发布模板内的稳定节点标识',
    `parentNodeInstanceId` BIGINT DEFAULT NULL COMMENT '父汇总节点轮次 ID；一级节点为空',
    `stageNo` INT NOT NULL COMMENT '所属一级节点的顺序号',
    `attemptNo` INT NOT NULL COMMENT '节点执行轮次，从 1 开始；重开时递增',
    `roleId` INT DEFAULT NULL COMMENT '分派使用的角色 ID 快照；汇总节点为空',
    `roleName` VARCHAR
(
    100
) DEFAULT NULL COMMENT '分派时的角色名称快照',
    `mode` VARCHAR
(
    16
) DEFAULT NULL COMMENT '分派方式：PRIMARY 单人、BROADCAST 广播、ALL 会签、MULTI_ALL 多角色会签；汇总节点为空',
    `status` VARCHAR
(
    24
) NOT NULL COMMENT '轮次状态：ACTIVE、WAITING_ASSIGNMENT、DONE、RETURNED、WITHDRAWN 或 CANCELLED',
    `activatedAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '待办成功激活时间；待分派时为空',
    `closedAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '结束时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_node_attempt`
(
    `flowInstanceId`,
    `nodeKey`,
    `attemptNo`
),
    KEY `idx_wf_node_active`
(
    `flowInstanceId`,
    `stageNo`,
    `status`
),
    CONSTRAINT `fk_wf_node_flow` FOREIGN KEY
(
    `flowInstanceId`
) REFERENCES `wf_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_node_parent` FOREIGN KEY
(
    `parentNodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='流程节点的每轮执行状态';

-- 精准退回子节点或收回时，单独记录沿用结果的其他已完成子轮次。
CREATE TABLE IF NOT EXISTS `wf_node_reuse`
(
    `parentNodeInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '沿用结果的新汇总轮次 ID',
    `childNodeInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '被沿用的已完成子节点轮次 ID',
    PRIMARY
    KEY
(
    `parentNodeInstanceId`,
    `childNodeInstanceId`
),
    CONSTRAINT `fk_wf_reuse_parent` FOREIGN KEY
(
    `parentNodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_reuse_child` FOREIGN KEY
(
    `childNodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='汇总节点沿用的已完成子节点轮次';

-- 主备必须属于角色成员，撤销成员关系时清理配置。主办唯一键允许多行空值。
CREATE TABLE IF NOT EXISTS `wf_role_handler`
(
    `userId`
    INT
    NOT
    NULL
    COMMENT
    '现有角色成员的用户 ID',
    `roleId`
    INT
    NOT
    NULL
    COMMENT
    '现有角色成员的角色 ID',
    `kind`
    VARCHAR
(
    12
) NOT NULL COMMENT '处理身份：PRIMARY 主办、BACKUP 备办',
    `primaryRoleId` INT DEFAULT NULL COMMENT '主办填所属角色 ID，备办留空；用于限制每角色至多一名主办',
    PRIMARY KEY
(
    `userId`,
    `roleId`
),
    UNIQUE KEY `uk_wf_role_primary`
(
    `primaryRoleId`
),
    KEY `idx_wf_role_members`
(
    `roleId`,
    `kind`
),
    CONSTRAINT `fk_wf_role_member` FOREIGN KEY
(
    `userId`,
    `roleId`
)
    REFERENCES `user_roles_role`
(
    `userId`,
    `roleId`
) ON DELETE CASCADE,
    CONSTRAINT `ck_wf_role_primary` CHECK
(
(
    `kind` =
    'PRIMARY'
    AND
    `primaryRoleId`
    IS
    NOT
    NULL
    AND
    `primaryRoleId` =
    `roleId`
)
    OR
(
    `kind` =
    'BACKUP'
    AND
    `primaryRoleId`
    IS
    NULL
)
    )
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='角色成员的流程主办和备办配置';

-- 转办时段重叠由应用在同一用户行锁下校验。任务历史独立保留。
CREATE TABLE IF NOT EXISTS `wf_user_delegation`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `ownerUserId`
    INT
    NOT
    NULL
    COMMENT
    '设置休假转办的本人用户 ID',
    `delegateUserId`
    INT
    NOT
    NULL
    COMMENT
    '指定转办人用户 ID',
    `startsAt`
    DATETIME
(
    3
) NOT NULL COMMENT '生效时间，包含此时刻',
    `endsAt` DATETIME
(
    3
) NOT NULL COMMENT '到期时间，截至此时刻前有效',
    `cancelledAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '提前终止时间；未提前终止时为空',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '创建时间',
    PRIMARY KEY
(
    `id`
),
    KEY `idx_wf_delegation_window`
(
    `ownerUserId`,
    `startsAt`,
    `endsAt`
),
    CONSTRAINT `ck_wf_delegation_window` CHECK
(
    `startsAt` <
    `endsAt`
    AND
    `ownerUserId`
    <>
    `delegateUserId`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='个人资料中的休假转办时段';

-- 收件人及身份在分派时固定。待办自动关闭时不补写人工处理时间或意见。
CREATE TABLE IF NOT EXISTS `wf_node_task`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `nodeInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属节点执行轮次 ID',
    `todoId`
    BIGINT
    NOT
    NULL
    COMMENT
    '对应统一工作台待办 ID',
    `assigneeUserId`
    INT
    NOT
    NULL
    COMMENT
    '实际收到该条待办的用户 ID',
    `identity`
    VARCHAR
(
    16
) NOT NULL COMMENT '收件身份：PRIMARY、DELEGATE、BROADCAST 或 COUNTERSIGN',
    `delegationId` BIGINT DEFAULT NULL COMMENT '转办待办对应的休假记录 ID',
    `delegatedFromTaskId` BIGINT DEFAULT NULL COMMENT '转办待办对应的原主办任务 ID',
    `processedBy` INT DEFAULT NULL COMMENT '实际提交处理结果的用户 ID',
    `result` VARCHAR
(
    16
) DEFAULT NULL COMMENT '处理结果：SUBMIT、APPROVE 或 REJECT；无人提交时为空',
    `opinion` TEXT COMMENT '审批意见的 Markdown 原文',
    `processedAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '提交处理结果的时间',
    `attachmentSnapshotJson` LONGTEXT DEFAULT NULL COMMENT '提交时引用的附件快照，保留字段编码和锁定版本',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_task_todo`
(
    `todoId`
),
    UNIQUE KEY `uk_wf_task_receiver`
(
    `nodeInstanceId`,
    `assigneeUserId`
),
    KEY `idx_wf_task_receiver`
(
    `assigneeUserId`,
    `nodeInstanceId`
),
    KEY `idx_wf_task_actor`
(
    `processedBy`,
    `processedAt`
),
    CONSTRAINT `fk_wf_task_node` FOREIGN KEY
(
    `nodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_task_todo` FOREIGN KEY
(
    `todoId`
) REFERENCES `wb_todo`
(
    `id`
),
    CONSTRAINT `fk_wf_task_delegation` FOREIGN KEY
(
    `delegationId`
) REFERENCES `wf_user_delegation`
(
    `id`
),
    CONSTRAINT `fk_wf_task_delegated_from` FOREIGN KEY
(
    `delegatedFromTaskId`
) REFERENCES `wf_node_task`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='节点轮次与统一待办的关联及处理记录';

-- 同一个动作请求键只在本实例内生效，流程状态和事件在同一事务提交。
CREATE TABLE IF NOT EXISTS `wf_flow_event`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `flowInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属流程实例 ID',
    `fromNodeInstanceId`
    BIGINT
    DEFAULT
    NULL
    COMMENT
    '动作发生时的来源节点轮次 ID',
    `toNodeInstanceId`
    BIGINT
    DEFAULT
    NULL
    COMMENT
    '推进、驳回或收回后的目标节点轮次 ID',
    `taskId`
    BIGINT
    DEFAULT
    NULL
    COMMENT
    '触发动作的节点任务 ID；无对应任务时为空',
    `eventType`
    VARCHAR
(
    24
) NOT NULL COMMENT '流程事件类型；例如 STARTED、RETURNED、RECALLED、COMPLETED、CANCELLED',
    `actorUserId` INT NOT NULL COMMENT '触发动作的用户 ID；系统事件使用系统主体 ID',
    `reason` VARCHAR
(
    500
) DEFAULT NULL COMMENT '人工驳回、取消或其他变更原因',
    `requestKey` VARCHAR
(
    128
) NOT NULL COMMENT '实例范围内的流程动作幂等键',
    `requestHash` CHAR
(
    64
) NOT NULL COMMENT '规范化动作请求的 SHA-256 摘要',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '创建时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_event_request`
(
    `flowInstanceId`,
    `requestKey`
),
    KEY `idx_wf_event_history`
(
    `flowInstanceId`,
    `createdAt`,
    `id`
),
    CONSTRAINT `fk_wf_event_flow` FOREIGN KEY
(
    `flowInstanceId`
) REFERENCES `wf_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_event_from` FOREIGN KEY
(
    `fromNodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_event_to` FOREIGN KEY
(
    `toNodeInstanceId`
) REFERENCES `wf_node_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_event_task` FOREIGN KEY
(
    `taskId`
) REFERENCES `wf_node_task`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='流程动作、流转历史和幂等记录';

-- 只为已登记来源且订阅了该事件的流程实例创建投递记录。
-- 同一事件只需投递给其来源模块，主键直接使用流程事件 ID。
CREATE TABLE IF NOT EXISTS `wf_business_delivery`
(
    `eventId`
    BIGINT
    NOT
    NULL
    COMMENT
    '来源流程事件 ID，也是对业务模块交付的唯一事件 ID',
    `status`
    VARCHAR
(
    16
) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING 待投递、PROCESSING 已领取、DELIVERED 已确认、FAILED 等待人工重试',
    `attemptCount` INT NOT NULL DEFAULT 0 COMMENT '累计领取投递次数，用于重试退避和排查',
    `nextAttemptAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '下一次可领取时间；结束时为空',
    `leaseToken` CHAR
(
    36
) DEFAULT NULL COMMENT '当前处理者的随机租约令牌；确认时校验',
    `leaseExpiresAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '当前处理租约到期时间',
    `payloadJson` LONGTEXT NOT NULL COMMENT '固定版本的事件载荷 JSON，含业务引用、实例版本及授权输出字段快照',
    `deliveredAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '业务确认成功时间',
    `lastError` VARCHAR
(
    500
) DEFAULT NULL COMMENT '最近一次投递失败的脱敏摘要',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '创建时间',
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '最后更新时间',
    PRIMARY KEY
(
    `eventId`
),
    KEY `idx_wf_delivery_pending`
(
    `status`,
    `nextAttemptAt`,
    `eventId`
),
    KEY `idx_wf_delivery_lease`
(
    `status`,
    `leaseExpiresAt`,
    `eventId`
),
    CONSTRAINT `fk_wf_delivery_event` FOREIGN KEY
(
    `eventId`
) REFERENCES `wf_flow_event`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='业务流程事件的持久化投递队列';

-- 文档中心负责文件内容及版本；流程仅登记有权上传的任务及字段。
CREATE TABLE IF NOT EXISTS `wf_attachment_upload`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '主键',
    `flowInstanceId`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属流程实例 ID',
    `nodeTaskId`
    BIGINT
    NOT
    NULL
    COMMENT
    '有权上传该附件的节点任务 ID',
    `fieldCode`
    VARCHAR
(
    64
) NOT NULL COMMENT '模板中配置的附件字段编码',
    `uploaderUserId` INT NOT NULL COMMENT '实际上传人的用户 ID',
    `documentId` BIGINT NOT NULL COMMENT '文档中心文档 ID',
    `versionId` BIGINT NOT NULL COMMENT '文档中心文件版本 ID',
    `boundAt` DATETIME
(
    3
) DEFAULT NULL COMMENT '文件挂接及锁定成功时间；临时上传时为空',
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
) COMMENT '创建时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_wf_upload_version`
(
    `versionId`
),
    KEY `idx_wf_task_upload`
(
    `nodeTaskId`,
    `fieldCode`
),
    CONSTRAINT `fk_wf_upload_flow` FOREIGN KEY
(
    `flowInstanceId`
) REFERENCES `wf_instance`
(
    `id`
),
    CONSTRAINT `fk_wf_upload_task` FOREIGN KEY
(
    `nodeTaskId`
) REFERENCES `wf_node_task`
(
    `id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='流程附件的临时上传及挂接记录';

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, 'ATTACHMENT', '附件', 'Attachment', 90
FROM `sys_param_group` g
WHERE g.code = 'FIELD_DATA_TYPE'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'ATTACHMENT');

-- 选项字典使用 WORKBENCH_TODO_DICT_ 前缀，槽位 value1 保存分类 ID。
INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`, `fieldConfig`)
SELECT 'WORKBENCH_TODO_DIMENSION',
       '待办公共维度',
       'Todo Public Dimensions',
       '{"fixedFields":{"value1":{"label":"字典分类 ID","type":"text","showInTable":true}}}' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'WORKBENCH_TODO_DIMENSION');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_1', '公共维度 1', 'Public Dimension 1', 0, 1
FROM `sys_param_group` g
WHERE g.code = 'WORKBENCH_TODO_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_1');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_2', '公共维度 2', 'Public Dimension 2', 0, 2
FROM `sys_param_group` g
WHERE g.code = 'WORKBENCH_TODO_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_2');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_3', '公共维度 3', 'Public Dimension 3', 0, 3
FROM `sys_param_group` g
WHERE g.code = 'WORKBENCH_TODO_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_3');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_4', '公共维度 4', 'Public Dimension 4', 0, 4
FROM `sys_param_group` g
WHERE g.code = 'WORKBENCH_TODO_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_4');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_5', '公共维度 5', 'Public Dimension 5', 0, 5
FROM `sys_param_group` g
WHERE g.code = 'WORKBENCH_TODO_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_5');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `icon`, `show`, `enable`, `order`)
SELECT '工作台',
       'Workbench',
       'Workbench',
       'MENU',
       'i-fe:grid',
       1,
       1,
       1 WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'Workbench');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '待办事项',
       'Todo Items',
       'WorkbenchTodo',
       'MENU',
       p.id,
       '/workbench/todos',
       'i-fe:list',
       '/src/views/workbench/todos/index.vue',
       1,
       1,
       1
FROM `permission` p
WHERE p.code = 'Workbench'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodo');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '待办日历',
       'Todo Calendar',
       'WorkbenchTodoCalendar',
       'MENU',
       p.id,
       '/workbench/todo-calendar',
       'i-fe:calendar',
       '/src/views/workbench/todo-calendar/index.vue',
       1,
       1,
       2
FROM `permission` p
WHERE p.code = 'Workbench'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoCalendar');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '新建待办',
       'Create todo',
       'WorkbenchTodoCreate',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'WorkbenchTodo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoCreate');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '指派待办',
       'Assign todo',
       'WorkbenchTodoAssign',
       'BUTTON',
       p.id,
       0,
       1,
       2
FROM `permission` p
WHERE p.code = 'WorkbenchTodo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoAssign');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '管理待办',
       'Manage todos',
       'WorkbenchTodoManage',
       'BUTTON',
       p.id,
       0,
       1,
       3
FROM `permission` p
WHERE p.code = 'WorkbenchTodo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoManage');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '配置待办公共维度',
       'Manage public todo dimensions',
       'WorkbenchTodoPublicDimensionManage',
       'BUTTON',
       p.id,
       0,
       1,
       4
FROM `permission` p
WHERE p.code = 'WorkbenchTodo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoPublicDimensionManage');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '管理流程模板',
       'Manage workflow templates',
       'WorkbenchTodoFlowManage',
       'BUTTON',
       p.id,
       0,
       1,
       5
FROM `permission` p
WHERE p.code = 'WorkbenchTodo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkbenchTodoFlowManage');

-- 文档中心

CREATE TABLE IF NOT EXISTS `doc_document`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `name`
    VARCHAR
(
    255
) NOT NULL,
    `departmentId` BIGINT DEFAULT NULL,
    `productId` BIGINT DEFAULT NULL,
    `dimension1Value` VARCHAR
(
    255
) DEFAULT NULL,
    `dimension2Value` VARCHAR
(
    255
) DEFAULT NULL,
    `dimension3Value` VARCHAR
(
    255
) DEFAULT NULL,
    `dimension4Value` VARCHAR
(
    255
) DEFAULT NULL,
    `dimension5Value` VARCHAR
(
    255
) DEFAULT NULL,
    `dimension6Value` VARCHAR
(
    255
) DEFAULT NULL,
    `sourceModule` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `businessType` VARCHAR
(
    64
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `businessId` VARCHAR
(
    128
) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    `businessVersionId` BIGINT DEFAULT NULL,
    `createdBy` INT NOT NULL,
    `updatedBy` INT NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `deletedAt` DATETIME
(
    3
) DEFAULT NULL,
    PRIMARY KEY
(
    `id`
),
    KEY `idx_doc_owner`
(
    `createdBy`,
    `deletedAt`,
    `sourceModule`,
    `id`
),
    KEY `idx_doc_business`
(
    `sourceModule`,
    `businessType`,
    `businessId`,
    `deletedAt`,
    `id`
),
    KEY `idx_doc_department`
(
    `departmentId`
),
    KEY `idx_doc_product`
(
    `productId`
),
    KEY `idx_doc_dimension1`
(
    `dimension1Value`
),
    KEY `idx_doc_dimension2`
(
    `dimension2Value`
),
    KEY `idx_doc_dimension3`
(
    `dimension3Value`
),
    KEY `idx_doc_dimension4`
(
    `dimension4Value`
),
    KEY `idx_doc_dimension5`
(
    `dimension5Value`
),
    KEY `idx_doc_dimension6`
(
    `dimension6Value`
),
    CONSTRAINT `ck_doc_business_owner` CHECK
(
(
    `sourceModule`
    IS
    NULL
    AND
    `businessType`
    IS
    NULL
    AND
    `businessId`
    IS
    NULL
    AND
    `businessVersionId`
    IS
    NULL
)
    OR
(
    `sourceModule`
    IS
    NOT
    NULL
    AND
    `businessType`
    IS
    NOT
    NULL
    AND
    `businessId`
    IS
    NOT
    NULL
)
    ),
    CONSTRAINT `fk_doc_department` FOREIGN KEY
(
    `departmentId`
) REFERENCES `sys_department`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_doc_product` FOREIGN KEY
(
    `productId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `doc_document_version`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `documentId`
    BIGINT
    NOT
    NULL,
    `versionDate`
    DATE
    DEFAULT
    NULL,
    `dailySequence`
    INT
    DEFAULT
    NULL,
    `originalFileName`
    VARCHAR
(
    255
) NOT NULL,
    `contentType` VARCHAR
(
    128
) NOT NULL,
    `sizeBytes` BIGINT NOT NULL,
    `storageKey` VARCHAR
(
    255
) COLLATE utf8mb4_0900_bin NOT NULL,
    `status` VARCHAR
(
    16
) COLLATE utf8mb4_0900_bin NOT NULL,
    `createdBy` INT NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `updatedAt` DATETIME
(
    3
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    3
),
    `cleanupAuditOperationId` VARCHAR
(
    36
) DEFAULT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_doc_version_document_id`
(
    `documentId`,
    `id`
),
    UNIQUE KEY `uk_doc_version_daily_sequence`
(
    `documentId`,
    `versionDate`,
    `dailySequence`
),
    UNIQUE KEY `uk_doc_version_storage`
(
    `storageKey`
),
    KEY `idx_doc_version_cleanup`
(
    `status`,
    `updatedAt`,
    `id`
),
    CONSTRAINT `ck_doc_version_size` CHECK
(
    `sizeBytes`
    >=
    0
),
    CONSTRAINT `ck_doc_version_status` CHECK
(
    `status`
    IN
(
    'UPLOADING',
    'READY',
    'FAILED',
    'DELETE_PENDING',
    'DELETED'
)),
    CONSTRAINT `ck_doc_version_sequence` CHECK
(
(
    `versionDate`
    IS
    NULL
    AND
    `dailySequence`
    IS
    NULL
)
    OR
(
    `versionDate`
    IS
    NOT
    NULL
    AND
    `dailySequence`
    IS
    NOT
    NULL
    AND
    `dailySequence` >
    0
)
    ),
    CONSTRAINT `ck_doc_version_ready_sequence` CHECK
(
    `status`
    <>
    'READY'
    OR
    `versionDate`
    IS
    NOT
    NULL
),
    CONSTRAINT `fk_doc_version_document` FOREIGN KEY
(
    `documentId`
) REFERENCES `doc_document`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4;

ALTER TABLE `doc_document`
    ADD CONSTRAINT `fk_doc_pinned_version` FOREIGN KEY (`id`, `businessVersionId`)
        REFERENCES `doc_document_version` (`documentId`, `id`);

-- 文档中心的产品字典和固定扩展维度。
INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`)
SELECT 'DOC_PRODUCT',
       '文档产品',
       'Document Products' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'DOC_PRODUCT');

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`, `fieldConfig`)
SELECT 'DOC_DIMENSION',
       '文档扩展维度',
       'Document Dimensions',
       '{"fixedFields":{"value1":{"label":"输入类型","type":"select","required":true,"showInTable":true,"defaultValue":"TEXT","options":[{"label":"文本","value":"TEXT"},{"label":"单选","value":"SELECT"}]},"value2":{"label":"字典分类编码","type":"text","showInTable":true}}}' WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` WHERE `code` = 'DOC_DIMENSION');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_1', '扩展维度 1', 'Dimension 1', 'TEXT', 0, 1
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_1');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_2', '扩展维度 2', 'Dimension 2', 'TEXT', 0, 2
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_2');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_3', '扩展维度 3', 'Dimension 3', 'TEXT', 0, 3
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_3');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_4', '扩展维度 4', 'Dimension 4', 'TEXT', 0, 4
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_4');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_5', '扩展维度 5', 'Dimension 5', 'TEXT', 0, 5
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_5');

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `value1`, `enable`, `sort`)
SELECT g.id, 'DIMENSION_6', '扩展维度 6', 'Dimension 6', 'TEXT', 0, 6
FROM `sys_param_group` g
WHERE g.code = 'DOC_DIMENSION'
  AND NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = 'DIMENSION_6');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '文档中心',
       'Document Center',
       'DocumentCenterView',
       'MENU',
       p.id,
       '/business/documents',
       'i-fe:file-text',
       '/src/views/business/documents/index.vue',
       1,
       1,
       12
FROM `permission` p
WHERE p.code = 'BusinessInfo'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'DocumentCenterView');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '上传文档',
       'Upload documents',
       'DocumentCenterUpload',
       'BUTTON',
       p.id,
       0,
       1,
       1
FROM `permission` p
WHERE p.code = 'DocumentCenterView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'DocumentCenterUpload');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '编辑文档',
       'Edit documents',
       'DocumentCenterEdit',
       'BUTTON',
       p.id,
       0,
       1,
       2
FROM `permission` p
WHERE p.code = 'DocumentCenterView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'DocumentCenterEdit');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '下载文档',
       'Download documents',
       'DocumentCenterDownload',
       'BUTTON',
       p.id,
       0,
       1,
       3
FROM `permission` p
WHERE p.code = 'DocumentCenterView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'DocumentCenterDownload');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT '删除文档',
       'Delete documents',
       'DocumentCenterDelete',
       'BUTTON',
       p.id,
       0,
       1,
       4
FROM `permission` p
WHERE p.code = 'DocumentCenterView'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'DocumentCenterDelete');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '流程模板',
       'Workflow Templates',
       'WorkflowTemplateMgt',
       'MENU',
       p.id,
       '/pms/workflow',
       'i-fe:git-branch',
       '/src/views/pms/workflow/index.vue',
       1,
       1,
       19
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkflowTemplateMgt');

-- 新版工作台入口。沿用工作台启动权限，处理权限按待办收件人判定。
INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '我的流程',
       'My Workflows',
       'WorkflowMine',
       'MENU',
       p.id,
       '/workbench/workflows',
       'i-fe:list',
       '/src/views/workbench/workflow/index.vue',
       1,
       1,
       3
FROM `permission` p
WHERE p.code = 'Workbench'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkflowMine');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '所有流程',
       'Workflow Catalog',
       'WorkflowCatalog',
       'MENU',
       p.id,
       '/workbench/workflow-catalog',
       'i-fe:layers',
       '/src/views/workbench/workflow/catalog.vue',
       1,
       1,
       4
FROM `permission` p
WHERE p.code = 'Workbench'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'WorkflowCatalog');

-- 邮件配置、模板和发送历史。历史引用使用限制删除的外键。
CREATE TABLE IF NOT EXISTS `sys_mail_account`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `code`
    VARCHAR
(
    64
) NOT NULL,
    `name` VARCHAR
(
    100
) NOT NULL,
    `provider` VARCHAR
(
    16
) NOT NULL,
    `mailboxAddress` VARCHAR
(
    254
) NOT NULL,
    `configJson` TEXT NOT NULL,
    `credentialCiphertext` TEXT NOT NULL,
    `enable` BOOLEAN NOT NULL DEFAULT TRUE,
    `createdBy` BIGINT NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL,
    `updatedBy` BIGINT NOT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_mail_account_code`
(
    `code`
),
    CONSTRAINT `ck_mail_account_provider` CHECK
(
    `provider`
    IN
(
    'IMAP_SMTP',
    'GRAPH'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_mail_template`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `code`
    VARCHAR
(
    64
) NOT NULL,
    `name` VARCHAR
(
    100
) NOT NULL,
    `accountId` BIGINT NOT NULL,
    `subjectTemplate` VARCHAR
(
    500
) NOT NULL,
    `bodyTemplate` TEXT NOT NULL,
    `bodyFormat` VARCHAR
(
    16
) NOT NULL DEFAULT 'MARKDOWN',
    `sendMode` VARCHAR
(
    16
) NOT NULL DEFAULT 'INDIVIDUAL',
    `revision` INT NOT NULL DEFAULT 1,
    `enable` BOOLEAN NOT NULL DEFAULT TRUE,
    `createdBy` BIGINT NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL,
    `updatedBy` BIGINT NOT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_mail_template_code`
(
    `code`
),
    KEY `ix_mail_template_account`
(
    `accountId`
),
    CONSTRAINT `fk_mail_template_account` FOREIGN KEY
(
    `accountId`
) REFERENCES `sys_mail_account`
(
    `id`
),
    CONSTRAINT `ck_mail_template_format` CHECK
(
    `bodyFormat` =
    'MARKDOWN'
),
    CONSTRAINT `ck_mail_template_send_mode` CHECK
(
    `sendMode`
    IN
(
    'INDIVIDUAL',
    'TOGETHER'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_mail_send_log`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `accountId`
    BIGINT
    NOT
    NULL,
    `templateId`
    BIGINT
    NOT
    NULL,
    `accountCode`
    VARCHAR
(
    64
) NOT NULL,
    `templateCode` VARCHAR
(
    64
) NOT NULL,
    `templateRevision` INT NOT NULL,
    `senderAddress` VARCHAR
(
    254
) NOT NULL,
    `provider` VARCHAR
(
    16
) NOT NULL,
    `sendMode` VARCHAR
(
    16
) NOT NULL,
    `callerKey` VARCHAR
(
    128
) COLLATE utf8mb4_bin NOT NULL,
    `requestId` VARCHAR
(
    64
) NOT NULL,
    `idempotencyKey` VARCHAR
(
    200
) COLLATE utf8mb4_bin,
    `requestDigest` CHAR
(
    64
),
    `recipientCount` INT NOT NULL,
    `status` VARCHAR
(
    16
) NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL,
    `finishedAt` DATETIME
(
    3
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_mail_send_caller_key`
(
    `callerKey`,
    `idempotencyKey`
),
    KEY `ix_mail_send_time`
(
    `createdAt`,
    `id`
),
    KEY `ix_mail_send_account_time`
(
    `accountId`,
    `createdAt`,
    `id`
),
    KEY `ix_mail_send_template_time`
(
    `templateId`,
    `createdAt`,
    `id`
),
    KEY `ix_mail_send_status_time`
(
    `status`,
    `createdAt`,
    `id`
),
    CONSTRAINT `fk_mail_send_account` FOREIGN KEY
(
    `accountId`
) REFERENCES `sys_mail_account`
(
    `id`
),
    CONSTRAINT `fk_mail_send_template` FOREIGN KEY
(
    `templateId`
) REFERENCES `sys_mail_template`
(
    `id`
),
    CONSTRAINT `ck_mail_send_idempotency` CHECK
(
(
    `idempotencyKey`
    IS
    NULL
    AND
    `requestDigest`
    IS
    NULL
)
    OR
(
    `idempotencyKey`
    IS
    NOT
    NULL
    AND
    `requestDigest`
    IS
    NOT
    NULL
)),
    CONSTRAINT `ck_mail_send_status` CHECK
(
    `status`
    IN
(
    'PENDING',
    'PROCESSING',
    'ACCEPTED',
    'PARTIAL',
    'FAILED',
    'UNKNOWN'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_mail_send_detail`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `sendLogId`
    BIGINT
    NOT
    NULL,
    `sequenceNo`
    INT
    NOT
    NULL,
    `recipientsJson`
    TEXT
    NOT
    NULL,
    `recipientResultsJson`
    TEXT,
    `status`
    VARCHAR
(
    16
) NOT NULL,
    `providerRequestId` VARCHAR
(
    200
),
    `errorCode` VARCHAR
(
    64
),
    `errorMessage` VARCHAR
(
    500
),
    `startedAt` DATETIME
(
    3
),
    `finishedAt` DATETIME
(
    3
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_mail_send_detail_sequence`
(
    `sendLogId`,
    `sequenceNo`
),
    CONSTRAINT `fk_mail_detail_send` FOREIGN KEY
(
    `sendLogId`
) REFERENCES `sys_mail_send_log`
(
    `id`
),
    CONSTRAINT `ck_mail_detail_status` CHECK
(
    `status`
    IN
(
    'PENDING',
    'PROCESSING',
    'ACCEPTED',
    'PARTIAL',
    'FAILED',
    'UNKNOWN'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '邮件管理',
       'Mail Management',
       'MailMgt',
       'MENU',
       p.id,
       '/pms/mail',
       'i-fe:mail',
       '/src/views/pms/mail/index.vue',
       1,
       1,
       12
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'MailMgt');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT resources.name,
       resources.nameEn,
       resources.code,
       'BUTTON',
       p.id,
       0,
       1,
       resources.sort
FROM `permission` p
         CROSS JOIN (SELECT '维护邮箱配置' AS name, 'Manage mail accounts' AS nameEn, 'MailAccount' AS code, 1 AS sort
                     UNION ALL
                     SELECT '维护邮件模板', 'Manage mail templates', 'MailTemplate', 2
                     UNION ALL
                     SELECT '查询发送日志', 'View mail send logs', 'MailLog', 3
                     UNION ALL
                     SELECT '读取收件箱', 'Read inbox', 'MailRead', 4
                     UNION ALL
                     SELECT '发送邮件', 'Send mail', 'MailSend', 5) resources
WHERE p.code = 'MailMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = resources.code);

-- 通知群组与场景绑定。依赖组织架构、英文菜单名称和操作审计迁移。
CREATE TABLE IF NOT EXISTS sys_notification_group
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    name
    VARCHAR
(
    100
) NOT NULL,
    description VARCHAR
(
    500
) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by INT NOT NULL,
    updated_by INT NOT NULL,
    created_at DATETIME
(
    6
) NOT NULL,
    updated_at DATETIME
(
    6
) NOT NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_notification_group_name UNIQUE
(
    name
),
    CONSTRAINT ck_notification_group_version CHECK
(
    version
    >=
    0
),
    INDEX idx_notification_group_enabled
(
    enabled,
    id
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_notification_group_user
(
    group_id
    BIGINT
    NOT
    NULL,
    user_id
    INT
    NOT
    NULL,
    PRIMARY
    KEY
(
    group_id,
    user_id
),
    CONSTRAINT fk_notification_group_user_group FOREIGN KEY
(
    group_id
)
    REFERENCES sys_notification_group
(
    id
) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_group_user_id CHECK
(
    user_id >
    0
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_notification_group_department
(
    group_id
    BIGINT
    NOT
    NULL,
    department_id
    BIGINT
    NOT
    NULL,
    include_descendants
    BOOLEAN
    NOT
    NULL
    DEFAULT
    FALSE,
    PRIMARY
    KEY
(
    group_id,
    department_id
),
    CONSTRAINT fk_notification_group_department_group FOREIGN KEY
(
    group_id
)
    REFERENCES sys_notification_group
(
    id
) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_group_department_department FOREIGN KEY
(
    department_id
)
    REFERENCES sys_department
(
    id
) ON DELETE RESTRICT,
    INDEX idx_notification_group_department_reverse
(
    department_id,
    group_id
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_notification_scene
(
    scene_code
    VARCHAR
(
    64
) NOT NULL,
    name_cn VARCHAR
(
    100
) NOT NULL,
    name_en VARCHAR
(
    100
) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by INT NULL,
    updated_at DATETIME
(
    6
) NOT NULL,
    PRIMARY KEY
(
    scene_code
),
    CONSTRAINT ck_notification_scene_version CHECK
(
    version
    >=
    0
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_notification_scene_group
(
    scene_code
    VARCHAR
(
    64
) NOT NULL,
    group_id BIGINT NOT NULL,
    PRIMARY KEY
(
    scene_code,
    group_id
),
    CONSTRAINT fk_notification_scene_group_scene FOREIGN KEY
(
    scene_code
)
    REFERENCES sys_notification_scene
(
    scene_code
) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_scene_group_group FOREIGN KEY
(
    group_id
)
    REFERENCES sys_notification_group
(
    id
) ON DELETE RESTRICT,
    INDEX idx_notification_scene_group_reverse
(
    group_id,
    scene_code
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '通知群组',
       'Notification Groups',
       'NotificationGroupView',
       'MENU',
       p.id,
       '/pms/notification-group',
       'i-fe:users',
       '/src/views/pms/notification-group/index.vue',
       1,
       1,
       13
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'NotificationGroupView');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT resources.name,
       resources.nameEn,
       resources.code,
       'BUTTON',
       p.id,
       0,
       1,
       resources.sort
FROM `permission` p
         CROSS JOIN (SELECT '维护通知群组'               AS name,
                            'Manage notification groups' AS nameEn,
                            'NotificationGroupEdit'      AS code,
                            1                            AS sort
                     UNION ALL
                     SELECT '维护场景绑定', 'Manage scene bindings', 'NotificationSceneRecipientEdit', 2) resources
WHERE p.code = 'NotificationGroupView'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = resources.code);

-- 一次性增量，依赖新版流程、通知群组、邮件及操作审计表已就绪。
-- wf_node_instance 和 sys_notification_scene 的列扩展应与本文件的新表一起交付。
-- 执行前核对预置编码 workflow.task.assigned、workflow.task.returned 未被其他业务占用。

ALTER TABLE sys_notification_scene
    ADD COLUMN scene_type VARCHAR(24) NOT NULL DEFAULT 'BUSINESS';
ALTER TABLE sys_notification_scene
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sys_notification_scene
    ADD COLUMN activation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_notification_scene
    ADD COLUMN in_app_title_template VARCHAR(200) NULL;
ALTER TABLE sys_notification_scene
    ADD COLUMN in_app_summary_template VARCHAR(1000) NULL;
ALTER TABLE sys_notification_scene
    ADD COLUMN mail_template_id BIGINT NULL;
ALTER TABLE sys_notification_scene
    ADD CONSTRAINT ck_wf_ns_type CHECK (scene_type IN ('BUSINESS', 'WORKFLOW_SYSTEM', 'WORKFLOW_EXTRA'));
ALTER TABLE sys_notification_scene
    ADD CONSTRAINT ck_wf_ns_activation CHECK (activation_version >= 0 AND activation_version <= version);
ALTER TABLE sys_notification_scene
    ADD CONSTRAINT ck_wf_ns_config CHECK (
        (scene_type <> 'WORKFLOW_SYSTEM' OR enabled = TRUE)
            AND (scene_type <> 'WORKFLOW_EXTRA' OR in_app_title_template IS NOT NULL)
        );
ALTER TABLE sys_notification_scene
    ADD CONSTRAINT fk_wf_ns_mail_template FOREIGN KEY (mail_template_id) REFERENCES sys_mail_template (id);
CREATE INDEX idx_wf_ns_scene_type ON sys_notification_scene (scene_type, enabled, scene_code);

ALTER TABLE wf_node_instance
    ADD COLUMN assignmentReason VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE wf_node_instance
    ADD CONSTRAINT ck_wf_node_assign_reason CHECK (assignmentReason IN ('NORMAL', 'RETURNED'));

-- 对升级前尚在待分派的轮次，用同一根轮次最近一次激活事件还原返工原因。
-- 对应子节点和根节点均处理，正常推进和收回不会被误标为返工。
CREATE
TEMPORARY TABLE tmp_wf_notification_activation AS
SELECT pending.id AS node_id, MAX(e.id) AS event_id
FROM wf_node_instance pending
         JOIN wf_node_instance target ON target.flowInstanceId = pending.flowInstanceId
    AND COALESCE(target.parentNodeInstanceId, target.id) = COALESCE(pending.parentNodeInstanceId, pending.id)
         JOIN wf_flow_event e ON e.toNodeInstanceId = target.id AND e.flowInstanceId = pending.flowInstanceId
WHERE pending.status = 'WAITING_ASSIGNMENT'
  AND e.eventType IN ('STARTED', 'ADVANCED', 'RETURNED', 'RECALLED')
GROUP BY pending.id;

UPDATE wf_node_instance
SET assignmentReason = 'RETURNED'
WHERE id IN (SELECT a.node_id
             FROM tmp_wf_notification_activation a
                      JOIN wf_flow_event e ON e.id = a.event_id
             WHERE e.eventType = 'RETURNED');
DROP
TEMPORARY TABLE tmp_wf_notification_activation;

CREATE TABLE sys_in_app_message
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_user_id INT          NOT NULL,
    source_module     VARCHAR(32)  NOT NULL,
    source_key        VARCHAR(128) NOT NULL,
    message_type      VARCHAR(32)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    summary           VARCHAR(1000) NULL,
    target_type       VARCHAR(32) NULL,
    target_id         BIGINT NULL,
    created_at        DATETIME(6)  NOT NULL,
    read_at           DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_in_app_source UNIQUE (recipient_user_id, source_module, source_key),
    CONSTRAINT ck_in_app_user CHECK (recipient_user_id > 0),
    CONSTRAINT ck_in_app_target CHECK (
        (target_type IS NULL AND target_id IS NULL)
            OR (target_type IS NOT NULL AND target_id IS NOT NULL AND target_id > 0)
        ),
    INDEX             idx_in_app_user_list (recipient_user_id, id),
    INDEX             idx_in_app_user_unread (recipient_user_id, read_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE wf_definition_notification_ref
(
    definition_id BIGINT      NOT NULL,
    node_key      VARCHAR(64) NOT NULL,
    trigger_type  VARCHAR(16) NOT NULL,
    scene_code    VARCHAR(64) NOT NULL,
    PRIMARY KEY (definition_id, node_key, trigger_type),
    CONSTRAINT fk_wf_nref_definition FOREIGN KEY (definition_id) REFERENCES wf_definition (id),
    CONSTRAINT fk_wf_nref_scene FOREIGN KEY (scene_code) REFERENCES sys_notification_scene (scene_code),
    CONSTRAINT ck_wf_nref_trigger CHECK (trigger_type IN ('APPROVED', 'REJECTED')),
    INDEX         idx_wf_nref_scene (scene_code, definition_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE wf_notification_delivery
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    trigger_key        VARCHAR(128) NOT NULL,
    flow_instance_id   BIGINT       NOT NULL,
    node_instance_id   BIGINT       NOT NULL,
    event_id           BIGINT NULL,
    task_id            BIGINT NULL,
    kind               VARCHAR(32)  NOT NULL,
    scene_code         VARCHAR(64)  NOT NULL,
    event_context_json LONGTEXT     NOT NULL,
    scene_version      BIGINT NULL,
    prepared_at        DATETIME(6)  NULL,
    plan_json          LONGTEXT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count      INT          NOT NULL DEFAULT 0,
    next_attempt_at    DATETIME(6)  NULL,
    lease_token        CHAR(36) NULL,
    lease_expires_at   DATETIME(6)  NULL,
    last_error_code    VARCHAR(64) NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    finished_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wf_ndel_trigger UNIQUE (trigger_key),
    CONSTRAINT ck_wf_ndel_source CHECK (
        (kind IN ('NODE_APPROVED', 'NODE_REJECTED') AND event_id IS NOT NULL AND task_id IS NULL)
            OR (kind IN ('TASK_ASSIGNED_MAIL', 'TASK_RETURNED_MAIL') AND task_id IS NOT NULL)
        ),
    CONSTRAINT ck_wf_ndel_status CHECK (status IN
                                        ('PENDING', 'PROCESSING', 'RETRY', 'DONE', 'PARTIAL', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_wf_ndel_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_wf_ndel_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_expires_at IS NULL)
        ),
    CONSTRAINT fk_wf_ndel_flow FOREIGN KEY (flow_instance_id) REFERENCES wf_instance (id),
    CONSTRAINT fk_wf_ndel_node FOREIGN KEY (node_instance_id) REFERENCES wf_node_instance (id),
    CONSTRAINT fk_wf_ndel_event FOREIGN KEY (event_id) REFERENCES wf_flow_event (id),
    CONSTRAINT fk_wf_ndel_task FOREIGN KEY (task_id) REFERENCES wf_node_task (id),
    CONSTRAINT fk_wf_ndel_scene FOREIGN KEY (scene_code) REFERENCES sys_notification_scene (scene_code),
    INDEX              idx_wf_ndel_due (status, next_attempt_at, id),
    INDEX              idx_wf_ndel_lease (status, lease_expires_at, id),
    INDEX              idx_wf_ndel_flow (flow_instance_id, id),
    INDEX              idx_wf_ndel_scene (scene_code, id),
    INDEX              idx_wf_ndel_node (node_instance_id),
    INDEX              idx_wf_ndel_event (event_id),
    INDEX              idx_wf_ndel_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE wf_notification_recipient
(
    delivery_id       BIGINT      NOT NULL,
    recipient_user_id INT         NOT NULL,
    in_app_status     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    message_id        BIGINT NULL,
    mail_status       VARCHAR(16) NOT NULL DEFAULT 'OFF',
    mail_log_id       BIGINT NULL,
    mail_request_json LONGTEXT NULL,
    in_app_error_code VARCHAR(64) NULL,
    mail_error_code   VARCHAR(64) NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id, recipient_user_id),
    CONSTRAINT fk_wf_nrec_delivery FOREIGN KEY (delivery_id) REFERENCES wf_notification_delivery (id),
    CONSTRAINT ck_wf_nrec_user CHECK (recipient_user_id > 0),
    CONSTRAINT ck_wf_nrec_in_app CHECK (
        (in_app_status = 'WRITTEN' AND message_id IS NOT NULL)
            OR (in_app_status IN ('PENDING', 'SKIPPED', 'FAILED') AND message_id IS NULL)
        ),
    CONSTRAINT ck_wf_nrec_mail CHECK (mail_status IN ('OFF', 'PENDING', 'ACCEPTED', 'SKIPPED', 'FAILED', 'UNKNOWN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

INSERT INTO sys_notification_scene (scene_code, name_cn, name_en, scene_type, enabled, updated_at)
SELECT s.code, s.name_cn, s.name_en, 'WORKFLOW_SYSTEM', TRUE, UTC_TIMESTAMP(6)
FROM (SELECT 'workflow.task.assigned' AS code, '新待办提醒' AS name_cn, 'New task reminder' AS name_en
      UNION ALL
      SELECT 'workflow.task.returned', '退回待办提醒', 'Returned task reminder') s
WHERE NOT EXISTS (SELECT 1 FROM sys_notification_scene existing WHERE existing.scene_code = s.code);

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT r.name_cn,
       r.name_en,
       r.code,
       'BUTTON',
       p.id,
       0,
       1,
       r.sort_order
FROM `permission` p
         CROSS JOIN (SELECT '维护流程通知场景'                    AS name_cn,
                            'Manage workflow notification scenes' AS name_en,
                            'WorkflowNotificationSceneEdit'       AS code,
                            3                                     AS sort_order
                     UNION ALL
                     SELECT '查看流程通知投递',
                            'View workflow notification deliveries',
                            'WorkflowNotificationDeliveryView',
                            4) r
WHERE p.code = 'NotificationGroupView'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = r.code);

-- 机构档案、变更申请及相关配置。
CREATE TABLE IF NOT EXISTS `biz_institution`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `code`
    VARCHAR
(
    64
) COLLATE utf8mb4_bin NOT NULL,
    `nameZh` VARCHAR
(
    200
) NOT NULL,
    `nameEn` VARCHAR
(
    200
) DEFAULT NULL,
    `typeItemId` BIGINT NOT NULL,
    `countryRegionItemId` BIGINT NOT NULL,
    `registrationId` VARCHAR
(
    100
) DEFAULT NULL,
    `shortNameZh` VARCHAR
(
    100
) DEFAULT NULL,
    `shortNameEn` VARCHAR
(
    100
) DEFAULT NULL,
    `officeAddress` VARCHAR
(
    500
) DEFAULT NULL,
    `website` VARCHAR
(
    500
) DEFAULT NULL,
    `remark` VARCHAR
(
    1000
) DEFAULT NULL,
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `version` BIGINT NOT NULL DEFAULT 0,
    `createdBy` INT NOT NULL,
    `updatedBy` INT NOT NULL,
    `createdAt` DATETIME
(
    3
) NOT NULL,
    `updatedAt` DATETIME
(
    3
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    CONSTRAINT `uk_biz_institution_code` UNIQUE
(
    `code`
),
    CONSTRAINT `ck_biz_institution_version` CHECK
(
    `version`
    >=
    0
),
    KEY `idx_biz_institution_type`
(
    `typeItemId`,
    `id`
),
    KEY `idx_biz_institution_country_region`
(
    `countryRegionItemId`,
    `id`
),
    KEY `idx_biz_institution_enabled`
(
    `enabled`,
    `id`
),
    CONSTRAINT `fk_biz_institution_type_item`
    FOREIGN KEY
(
    `typeItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_biz_institution_country_region_item`
    FOREIGN KEY
(
    `countryRegionItemId`
) REFERENCES `sys_param_item`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `biz_institution_change`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `institutionId`
    BIGINT
    NOT
    NULL,
    `flowInstanceId`
    BIGINT
    DEFAULT
    NULL,
    `baseValues`
    JSON
    NOT
    NULL,
    `status`
    VARCHAR
(
    16
) COLLATE utf8mb4_bin NOT NULL,
    `resultEventId` BIGINT DEFAULT NULL,
    `resolvedAt` DATETIME
(
    3
) DEFAULT NULL,
    PRIMARY KEY
(
    `id`
),
    CONSTRAINT `uk_biz_institution_change_flow` UNIQUE
(
    `flowInstanceId`
),
    KEY `idx_biz_institution_change_history`
(
    `institutionId`,
    `status`,
    `id`
),
    CONSTRAINT `ck_biz_institution_change_status` CHECK
(
    `status`
    IN
(
    'PENDING',
    'APPLIED',
    'CONFLICT',
    'CANCELLED'
)),
    CONSTRAINT `fk_biz_institution_change_institution`
    FOREIGN KEY
(
    `institutionId`
) REFERENCES `biz_institution`
(
    `id`
) ON DELETE RESTRICT,
    CONSTRAINT `fk_biz_institution_change_flow`
    FOREIGN KEY
(
    `flowInstanceId`
) REFERENCES `wf_instance`
(
    `id`
) ON DELETE RESTRICT
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO `sys_param_group` (`code`, `name`, `nameEn`)
SELECT seed.code, seed.name, seed.nameEn
FROM (SELECT 'INSTITUTION_TYPE' AS code, '机构类型' AS name, 'Institution Types' AS nameEn
      UNION ALL
      SELECT 'INSTITUTION_COUNTRY_REGION', '注册国家或地区', 'Countries and Regions of Registration') seed
WHERE NOT EXISTS (SELECT 1 FROM `sys_param_group` g WHERE g.code = seed.code);

INSERT INTO `sys_param_item` (`groupId`, `code`, `name`, `nameEn`, `sort`)
SELECT g.id, seed.code, seed.name, seed.nameEn, seed.sort
FROM `sys_param_group` g
         JOIN (SELECT 'INSTITUTION_TYPE' AS groupCode, 'BANK' AS code, '银行' AS name, 'Bank' AS nameEn, 10 AS sort
               UNION ALL
               SELECT 'INSTITUTION_TYPE', 'SECURITIES', '证券公司', 'Securities Company', 20
               UNION ALL
               SELECT 'INSTITUTION_TYPE', 'FUND_MANAGEMENT', '基金管理公司', 'Fund Management Company', 30
               UNION ALL
               SELECT 'INSTITUTION_TYPE', 'INSURANCE', '保险公司', 'Insurance Company', 40
               UNION ALL
               SELECT 'INSTITUTION_TYPE', 'TRUST', '信托公司', 'Trust Company', 50
               UNION ALL
               SELECT 'INSTITUTION_TYPE', 'OTHER', '其他金融机构', 'Other Financial Institution', 60
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'CN', '中国内地', 'Mainland China', 10
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'HK', '中国香港', 'Hong Kong', 20
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'MO', '中国澳门', 'Macao', 30
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'TW', '中国台湾', 'Taiwan', 40
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'US', '美国', 'United States', 50
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'SG', '新加坡', 'Singapore', 60
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'JP', '日本', 'Japan', 70
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'GB', '英国', 'United Kingdom', 80
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'DE', '德国', 'Germany', 90
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'FR', '法国', 'France', 100
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'CH', '瑞士', 'Switzerland', 110
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'AU', '澳大利亚', 'Australia', 120
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'CA', '加拿大', 'Canada', 130
               UNION ALL
               SELECT 'INSTITUTION_COUNTRY_REGION', 'KR', '韩国', 'South Korea', 140) seed ON g.code = seed.groupCode
WHERE NOT EXISTS (SELECT 1 FROM `sys_param_item` i WHERE i.groupId = g.id AND i.code = seed.code);

INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `required`, `textMaxLength`, `textFormat`,
                                    `paramGroupId`, `enable`)
SELECT seed.code,
       seed.name,
       seed.nameEn,
       seed.dataType,
       seed.isRequired,
       seed.maxLength,
       seed.textFormat,
       g.id,
       1
FROM (SELECT 'institution_code' AS code,
             '机构编码'         AS name,
             'Institution Code' AS nameEn,
             'TEXT'             AS dataType,
             1                  AS isRequired,
             64                 AS maxLength,
             NULL               AS textFormat,
             NULL               AS groupCode
      UNION ALL
      SELECT 'institution_name_zh',
             '中文全称',
             'Chinese Full Name',
             'TEXT',
             1,
             200,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_name_en',
             '英文全称',
             'English Full Name',
             'TEXT',
             0,
             200,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_type',
             '机构类型',
             'Institution Type',
             'ENUM',
             1,
             NULL,
             NULL,
             'INSTITUTION_TYPE'
      UNION ALL
      SELECT 'institution_country_region',
             '注册国家或地区',
             'Country or Region of Registration',
             'ENUM',
             1,
             NULL,
             NULL,
             'INSTITUTION_COUNTRY_REGION'
      UNION ALL
      SELECT 'institution_registration_id',
             '注册识别号',
             'Registration ID',
             'TEXT',
             0,
             100,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_short_name_zh',
             '中文简称',
             'Chinese Short Name',
             'TEXT',
             0,
             100,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_short_name_en',
             '英文简称',
             'English Short Name',
             'TEXT',
             0,
             100,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_office_address',
             '办公地址',
             'Office Address',
             'TEXT',
             0,
             500,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_website',
             '官网',
             'Website',
             'TEXT',
             0,
             500,
             'URL',
             NULL
      UNION ALL
      SELECT 'institution_remark',
             '备注',
             'Remarks',
             'TEXT',
             0,
             1000,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_enabled',
             '启停状态',
             'Enabled Status',
             'BOOLEAN',
             0,
             NULL,
             NULL,
             NULL
      UNION ALL
      SELECT 'institution_change_reason',
             '变更原因',
             'Change Reason',
             'TEXT',
             1,
             500,
             NULL,
             NULL) seed
         LEFT JOIN `sys_param_group` g ON g.code = seed.groupCode
WHERE (seed.groupCode IS NULL OR g.id IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM `sys_field_definition` f WHERE f.code = seed.code);

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '机构管理',
       'Institution Management',
       'InstitutionView',
       'MENU',
       p.id,
       '/business/institutions',
       'i-fe:home',
       '/src/views/business/institutions/index.vue',
       1,
       1,
       13
FROM `permission` p
WHERE p.code = 'BusinessInfo'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = 'InstitutionView');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT seed.name,
       seed.nameEn,
       seed.code,
       'BUTTON',
       p.id,
       0,
       1,
       seed.sort
FROM `permission` p
         CROSS JOIN (SELECT '新增机构' AS name, 'Create Institution' AS nameEn, 'InstitutionCreate' AS code, 1 AS sort
                     UNION ALL
                     SELECT '编辑机构', 'Edit Institution', 'InstitutionEdit', 2
                     UNION ALL
                     SELECT '启停机构', 'Change Institution Status', 'InstitutionStatus', 3
                     UNION ALL
                     SELECT '发起机构变更', 'Submit Institution Change', 'InstitutionSubmit', 4) seed
WHERE p.code = 'InstitutionView'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = seed.code);

-- 开放 API 配置、凭据版本和调用记录。
CREATE TABLE IF NOT EXISTS sys_open_api_endpoint
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    endpoint_code
    VARCHAR
(
    96
) NOT NULL,
    module_code VARCHAR
(
    64
) NOT NULL,
    display_name VARCHAR
(
    128
) NOT NULL,
    http_method VARCHAR
(
    8
) NOT NULL,
    path_template VARCHAR
(
    256
) NOT NULL,
    api_version VARCHAR
(
    16
) NOT NULL,
    data_scope_type VARCHAR
(
    32
) NOT NULL,
    status VARCHAR
(
    16
) NOT NULL DEFAULT 'REGISTERED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME
(
    3
) NOT NULL,
    updated_at DATETIME
(
    3
) NOT NULL,
    created_by INT NULL,
    updated_by INT NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_open_api_endpoint_code UNIQUE
(
    endpoint_code
),
    CONSTRAINT uk_open_api_endpoint_route UNIQUE
(
    http_method,
    path_template
),
    CONSTRAINT ck_open_api_endpoint_status CHECK
(
    status
    IN
(
    'REGISTERED',
    'PUBLISHED',
    'OFFLINE'
)),
    CONSTRAINT ck_open_api_endpoint_version CHECK
(
    version
    >=
    0
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS sys_open_api_read_mapping
(
    endpoint_id
    BIGINT
    NOT
    NULL
    COMMENT
    '接口目录 ID，关联 sys_open_api_endpoint.id',
    source_path
    VARCHAR
(
    256
) NOT NULL COMMENT '现有只读 REST 接口的 MVC 路由',
    public_contract_json JSON NOT NULL COMMENT '公开契约快照，包含查询参数、响应字段和数据范围规则',
    source_contract_hash CHAR
(
    64
) NOT NULL COMMENT '来源契约的 SHA-256 指纹，用于检测部署变更',
    PRIMARY KEY
(
    endpoint_id
),
    CONSTRAINT fk_open_api_read_mapping_endpoint FOREIGN KEY
(
    endpoint_id
)
    REFERENCES sys_open_api_endpoint
(
    id
) ON DELETE RESTRICT
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin COMMENT = '只读开放接口来源映射及公开契约';

CREATE TABLE IF NOT EXISTS sys_open_api_app
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    client_id
    VARCHAR
(
    64
) NOT NULL,
    name VARCHAR
(
    128
) NOT NULL,
    environment VARCHAR
(
    32
) NOT NULL,
    description VARCHAR
(
    512
) NULL,
    owner_user_id INT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_revision BIGINT NOT NULL DEFAULT 0,
    rate_limit_count INT NOT NULL,
    rate_limit_window_seconds INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME
(
    3
) NOT NULL,
    updated_at DATETIME
(
    3
) NOT NULL,
    created_by INT NULL,
    updated_by INT NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_open_api_app_client_id UNIQUE
(
    client_id
),
    CONSTRAINT ck_open_api_app_quota CHECK
(
    rate_limit_count >
    0
    AND
    rate_limit_window_seconds >
    0
),
    CONSTRAINT ck_open_api_app_version CHECK
(
    version
    >=
    0
    AND
    token_revision
    >=
    0
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS sys_open_api_app_grant
(
    app_id
    BIGINT
    NOT
    NULL,
    endpoint_id
    BIGINT
    NOT
    NULL,
    status
    VARCHAR
(
    16
) NOT NULL,
    data_scope_mode VARCHAR
(
    16
) NOT NULL,
    data_scope_json JSON NULL,
    rate_limit_count INT NULL,
    rate_limit_window_seconds INT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME
(
    3
) NOT NULL,
    updated_at DATETIME
(
    3
) NOT NULL,
    created_by INT NULL,
    updated_by INT NULL,
    PRIMARY KEY
(
    app_id,
    endpoint_id
),
    CONSTRAINT fk_open_api_grant_app FOREIGN KEY
(
    app_id
) REFERENCES sys_open_api_app
(
    id
) ON DELETE RESTRICT,
    CONSTRAINT fk_open_api_grant_endpoint FOREIGN KEY
(
    endpoint_id
)
    REFERENCES sys_open_api_endpoint
(
    id
) ON DELETE RESTRICT,
    CONSTRAINT ck_open_api_grant_status CHECK
(
    status
    IN
(
    'GRANTED',
    'REVOKED'
)),
    CONSTRAINT ck_open_api_grant_scope CHECK
(
(
    data_scope_mode =
    'RESTRICTED'
    AND
    data_scope_json
    IS
    NOT
    NULL
)
    OR
(
    data_scope_mode
    IN
(
    'ALL',
    'NOT_APPLICABLE'
) AND data_scope_json IS NULL)),
    CONSTRAINT ck_open_api_grant_quota CHECK
(
(
    rate_limit_count
    IS
    NULL
    AND
    rate_limit_window_seconds
    IS
    NULL
)
    OR
(
    rate_limit_count
    IS
    NOT
    NULL
    AND
    rate_limit_window_seconds
    IS
    NOT
    NULL
    AND
    rate_limit_count >
    0
    AND
    rate_limit_window_seconds >
    0
)),
    CONSTRAINT ck_open_api_grant_version CHECK
(
    version
    >=
    0
),
    INDEX idx_open_api_grant_endpoint
(
    endpoint_id,
    status,
    app_id
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS sys_open_api_app_secret
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    app_id
    BIGINT
    NOT
    NULL,
    version_no
    INT
    NOT
    NULL,
    secret_hash
    VARCHAR
(
    255
) NOT NULL,
    status VARCHAR
(
    16
) NOT NULL,
    expires_at DATETIME
(
    3
) NOT NULL,
    activated_at DATETIME
(
    3
) NULL,
    revoked_at DATETIME
(
    3
) NULL,
    tokens_revoked_at DATETIME
(
    3
) NULL,
    created_at DATETIME
(
    3
) NOT NULL,
    updated_at DATETIME
(
    3
) NOT NULL,
    created_by INT NULL,
    updated_by INT NULL,
    PRIMARY KEY
(
    id
),
    CONSTRAINT fk_open_api_secret_app FOREIGN KEY
(
    app_id
) REFERENCES sys_open_api_app
(
    id
) ON DELETE RESTRICT,
    CONSTRAINT uk_open_api_secret_version UNIQUE
(
    app_id,
    version_no
),
    CONSTRAINT ck_open_api_secret_version CHECK
(
    version_no >
    0
),
    CONSTRAINT ck_open_api_secret_status CHECK
(
    status
    IN
(
    'CANDIDATE',
    'ACTIVE',
    'RETIRING',
    'REVOKED'
)),
    INDEX idx_open_api_secret_slots
(
    app_id,
    status,
    expires_at
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS sys_open_api_call_log
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    request_id
    VARCHAR
(
    64
) NOT NULL,
    occurred_at DATETIME
(
    3
) NOT NULL,
    duration_ms BIGINT NOT NULL,
    http_status SMALLINT NOT NULL,
    app_id BIGINT NULL,
    client_id VARCHAR
(
    64
) NULL,
    endpoint_id BIGINT NULL,
    endpoint_code VARCHAR
(
    96
) NULL,
    error_code VARCHAR
(
    64
) NULL,
    source_ip VARCHAR
(
    64
) NULL,
    PRIMARY KEY
(
    id
),
    INDEX idx_open_api_call_app
(
    app_id,
    occurred_at,
    id
),
    INDEX idx_open_api_call_app_endpoint
(
    app_id,
    endpoint_id,
    occurred_at,
    id
),
    INDEX idx_open_api_call_request
(
    request_id
),
    INDEX idx_open_api_call_retention
(
    occurred_at,
    id
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO sys_open_api_endpoint
(endpoint_code, module_code, display_name, http_method, path_template, api_version, data_scope_type, created_at,
 updated_at)
SELECT seed.code,
       'trading-calendar',
       seed.display_name,
       'GET',
       seed.path,
       'v1',
       'MARKET',
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM (SELECT 'trading-calendar.markets.read'     AS code,
             '市场列表'                          AS display_name,
             '/open/v1/trading-calendar/markets' AS path
      UNION ALL
      SELECT 'trading-calendar.year.read', '年度交易日历', '/open/v1/trading-calendar/year'
      UNION ALL
      SELECT 'trading-calendar.status.read', '指定日期交易状态', '/open/v1/trading-calendar/status'
      UNION ALL
      SELECT 'trading-calendar.offset.read', '交易日偏移', '/open/v1/trading-calendar/offset') seed
WHERE NOT EXISTS (SELECT 1 FROM sys_open_api_endpoint existing WHERE existing.endpoint_code = seed.code);

-- 字段管理专用开放接口。
INSERT INTO sys_open_api_endpoint
(endpoint_code, module_code, display_name, http_method, path_template,
 api_version, data_scope_type, status, created_at, updated_at)
SELECT seed.endpoint_code,
       seed.module_code,
       seed.display_name,
       seed.http_method,
       seed.path_template,
       seed.api_version,
       seed.data_scope_type,
       'REGISTERED',
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM (SELECT 'field-definition.public.page.read' AS endpoint_code,
             'field-definition'                  AS module_code,
             '字段定义分页查询（专用）'            AS display_name,
             'GET'                               AS http_method,
             '/open/v1/field-definition'         AS path_template,
             'v1'                                AS api_version,
             'NONE'                              AS data_scope_type
      UNION ALL
      SELECT 'field-definition.public.detail.read',
             'field-definition',
             '字段定义详情（专用）',
             'GET',
             '/open/v1/field-definition/{id}',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'field-definition.public.metadata.read',
             'field-definition',
             '字段定义元数据（专用）',
             'GET',
             '/open/v1/field-definition/metadata',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'field-definition.public.dictionaries.read',
             'field-definition',
             '字段字典列表（专用）',
             'GET',
             '/open/v1/field-definition/dictionaries',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'field-definition.public.dictionary-options.read',
             'field-definition',
             '字段字典选项（专用）',
             'GET',
             '/open/v1/field-definition/dictionaries/{id}/options',
             'v1',
             'NONE') AS seed
WHERE NOT EXISTS (SELECT 1
                  FROM sys_open_api_endpoint AS existing
                  WHERE existing.endpoint_code = seed.endpoint_code
                    AND existing.module_code = seed.module_code
                    AND existing.http_method = seed.http_method
                    AND existing.path_template = seed.path_template
                    AND existing.api_version = seed.api_version
                    AND existing.data_scope_type = seed.data_scope_type);


INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `path`, `icon`, `component`, `show`, `enable`,
                          `order`)
SELECT '开放 API',
       'Open API',
       'OpenApiView',
       'MENU',
       p.id,
       '/pms/open-api',
       'i-fe:key',
       '/src/views/pms/open-api/index.vue',
       1,
       1,
       14
FROM `permission` p
WHERE p.code = 'SysMgt'
  AND NOT EXISTS (SELECT 1 FROM `permission` WHERE code = 'OpenApiView');

INSERT INTO `permission` (`name`, `nameEn`, `code`, `type`, `parentId`, `show`, `enable`, `order`)
SELECT resources.name,
       resources.nameEn,
       resources.code,
       'BUTTON',
       p.id,
       0,
       1,
       resources.sort
FROM `permission` p
         CROSS JOIN (SELECT '维护接入应用'        AS name,
                            'Manage applications' AS nameEn,
                            'OpenApiAppManage'    AS code,
                            1                     AS sort
                     UNION ALL
                     SELECT '发布开放接口', 'Publish endpoints', 'OpenApiEndpointPublish', 2
                     UNION ALL
                     SELECT '维护接口授权', 'Manage grants', 'OpenApiGrantManage', 3
                     UNION ALL
                     SELECT '维护应用凭据', 'Manage application credentials', 'OpenApiSecretManage', 4
                     UNION ALL
                     SELECT '查看调用记录', 'View call logs', 'OpenApiCallLogView', 5) resources
WHERE p.code = 'OpenApiView'
  AND NOT EXISTS (SELECT 1 FROM `permission` existing WHERE existing.code = resources.code);
-- 文档生成模板、固定发布版本及生成记录。
CREATE TABLE IF NOT EXISTS biz_doc_template
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '模板 ID',
    code
    VARCHAR
(
    64
) NOT NULL COMMENT '创建后固定的模板编码',
    name VARCHAR
(
    200
) NOT NULL COMMENT '模板展示名称',
    description VARCHAR
(
    500
) NULL COMMENT '模板说明，可空',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许选择该模板发起生成',
    currentVersionId BIGINT NULL COMMENT '当前发布版本 ID，未发布时为空',
    createdBy INT NOT NULL COMMENT '创建人用户 ID，0 表示系统初始化',
    updatedBy INT NOT NULL COMMENT '最后更新人用户 ID，0 表示系统初始化',
    createdAt DATETIME
(
    3
) NOT NULL COMMENT '创建时间 UTC',
    updatedAt DATETIME
(
    3
) NOT NULL COMMENT '最后更新时间 UTC',
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_docgen_template_code UNIQUE
(
    code
),
    KEY idx_docgen_template_enabled
(
    enabled,
    id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档生成模板';

CREATE TABLE IF NOT EXISTS biz_doc_template_version
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '模板版本 ID',
    templateId
    BIGINT
    NOT
    NULL
    COMMENT
    '所属模板 ID',
    versionNo
    INT
    NOT
    NULL
    COMMENT
    '模板内递增版本号',
    status
    VARCHAR
(
    16
) NOT NULL COMMENT 'DRAFT 草稿，PUBLISHED 已发布',
    templateFormat VARCHAR
(
    32
) NOT NULL COMMENT '模板协议标识，首版为 POI_TL_V1',
    documentId BIGINT NULL COMMENT '模板所属文档中心文档 ID，未上传为空',
    documentVersionId BIGINT NULL COMMENT '模板文档的固定文件版本 ID，未上传为空',
    inputSchema JSON NOT NULL COMMENT '表单和输入校验共用的 Schema 根对象',
    exampleJson JSON NULL COMMENT '兼容单份示例的默认输入对象',
    renderConfig JSON NULL COMMENT '循环、图表、子模板等渲染配置',
    samplesJson JSON NULL COMMENT '命名输入样例数组',
    createdBy INT NOT NULL COMMENT '创建人用户 ID，0 表示系统初始化',
    updatedBy INT NOT NULL COMMENT '最后维护人用户 ID',
    publishedBy INT NULL COMMENT '发布人用户 ID，发布前为空',
    createdAt DATETIME
(
    3
) NOT NULL COMMENT '创建时间 UTC',
    updatedAt DATETIME
(
    3
) NOT NULL COMMENT '更新时间 UTC，兼作草稿修订',
    publishedAt DATETIME
(
    3
) NULL COMMENT '发布时间 UTC，发布前为空',
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_docgen_version_number UNIQUE
(
    templateId,
    versionNo
),
    CONSTRAINT uk_docgen_version_owner UNIQUE
(
    templateId,
    id
),
    CONSTRAINT uk_docgen_template_file UNIQUE
(
    documentId,
    documentVersionId
),
    KEY idx_docgen_version_status
(
    templateId,
    status,
    versionNo
),
    CONSTRAINT fk_docgen_version_template FOREIGN KEY
(
    templateId
) REFERENCES biz_doc_template
(
    id
),
    CONSTRAINT fk_docgen_version_file FOREIGN KEY
(
    documentId,
    documentVersionId
) REFERENCES doc_document_version
(
    documentId,
    id
),
    CONSTRAINT ck_docgen_version_number CHECK
(
    versionNo >
    0
),
    CONSTRAINT ck_docgen_version_file CHECK
(
(
    documentId
    IS
    NULL
    AND
    documentVersionId
    IS
    NULL
)
    OR
(
    documentId
    IS
    NOT
    NULL
    AND
    documentVersionId
    IS
    NOT
    NULL
)),
    CONSTRAINT ck_docgen_version_state CHECK
(
(
    status =
    'DRAFT'
    AND
    publishedAt
    IS
    NULL
    AND
    publishedBy
    IS
    NULL
)
    OR
(
    status =
    'PUBLISHED'
    AND
    publishedAt
    IS
    NOT
    NULL
    AND
    publishedBy
    IS
    NOT
    NULL
    AND
    documentVersionId
    IS
    NOT
    NULL
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档生成模板版本';

ALTER TABLE biz_doc_template
    ADD CONSTRAINT fk_docgen_template_current
        FOREIGN KEY (id, currentVersionId) REFERENCES biz_doc_template_version (templateId, id);

CREATE TABLE IF NOT EXISTS biz_doc_generation
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    COMMENT
    '生成记录 ID',
    templateVersionId
    BIGINT
    NOT
    NULL
    COMMENT
    '生成时固定的模板版本 ID',
    createdBy
    INT
    NULL
    COMMENT
    '生成员工 ID，开放应用生成时为空',
    createdByAppId
    BIGINT
    NULL
    COMMENT
    '生成开放应用 ID，员工生成时为空',
    requestKey
    CHAR
(
    36
) NOT NULL COMMENT '调用方范围内的幂等请求键',
    requestHash CHAR
(
    64
) NOT NULL COMMENT '规范化生成请求的 SHA-256 摘要',
    inputJson JSON NULL COMMENT '生成时输入快照，到期清理后为空',
    status VARCHAR
(
    16
) NOT NULL COMMENT 'RUNNING 运行中，SUCCEEDED 成功，FAILED 失败',
    errorCode VARCHAR
(
    64
) NULL COMMENT '失败原因的稳定错误码',
    outputDocumentId BIGINT NULL COMMENT '成品所属文档中心文档 ID，仅成功时填写',
    outputVersionId BIGINT NULL COMMENT '成品的固定文件版本 ID，仅成功时填写',
    createdAt DATETIME
(
    3
) NOT NULL COMMENT '生成开始时间 UTC',
    finishedAt DATETIME
(
    3
) NULL COMMENT '生成完成时间 UTC',
    PRIMARY KEY
(
    id
),
    CONSTRAINT uk_docgen_owner_request UNIQUE
(
    createdBy,
    requestKey
),
    CONSTRAINT uk_docgen_app_request UNIQUE
(
    createdByAppId,
    requestKey
),
    CONSTRAINT uk_docgen_output_file UNIQUE
(
    outputDocumentId,
    outputVersionId
),
    KEY idx_docgen_history
(
    createdBy,
    createdAt,
    id
),
    KEY idx_docgen_app_history
(
    createdByAppId,
    createdAt,
    id
),
    KEY idx_docgen_template_history
(
    templateVersionId,
    id
),
    KEY idx_docgen_running
(
    status,
    createdAt,
    id
),
    KEY idx_docgen_retention
(
    finishedAt,
    id
),
    CONSTRAINT fk_docgen_request_template FOREIGN KEY
(
    templateVersionId
) REFERENCES biz_doc_template_version
(
    id
),
    CONSTRAINT fk_docgen_output_file FOREIGN KEY
(
    outputDocumentId,
    outputVersionId
) REFERENCES doc_document_version
(
    documentId,
    id
),
    CONSTRAINT ck_docgen_owner CHECK
(
(
    createdBy
    IS
    NOT
    NULL
    AND
    createdBy >
    0
    AND
    createdByAppId
    IS
    NULL
)
    OR
(
    createdBy
    IS
    NULL
    AND
    createdByAppId
    IS
    NOT
    NULL
    AND
    createdByAppId >
    0
)),
    CONSTRAINT ck_docgen_result CHECK
(
(
    status =
    'RUNNING'
    AND
    finishedAt
    IS
    NULL
    AND
    errorCode
    IS
    NULL
    AND
    outputDocumentId
    IS
    NULL
    AND
    outputVersionId
    IS
    NULL
)
    OR
(
    status =
    'FAILED'
    AND
    finishedAt
    IS
    NOT
    NULL
    AND
    errorCode
    IS
    NOT
    NULL
    AND
    outputDocumentId
    IS
    NULL
    AND
    outputVersionId
    IS
    NULL
)
    OR
(
    status =
    'SUCCEEDED'
    AND
    finishedAt
    IS
    NOT
    NULL
    AND
    errorCode
    IS
    NULL
    AND
    outputDocumentId
    IS
    NOT
    NULL
    AND
    outputVersionId
    IS
    NOT
    NULL
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档生成记录';

INSERT INTO permission (name, nameEn, code, type, parentId, path, icon, component, `show`, enable, `order`)
SELECT '文档生成',
       'Document Generation',
       'DocumentGenerationView',
       'MENU',
       p.id,
       '/business/document-generation',
       'i-fe:file-plus',
       '/src/views/business/document-generation/index.vue',
       1,
       1,
       13
FROM permission p
WHERE p.code = 'BusinessInfo'
  AND NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DocumentGenerationView');

INSERT INTO permission (name, nameEn, code, type, parentId, `show`, enable, `order`)
SELECT b.name,
       b.nameEn,
       b.code,
       'BUTTON',
       p.id,
       0,
       1,
       b.rankNo
FROM permission p
         CROSS JOIN (SELECT '生成文档'              AS name,
                            'Generate document'     AS nameEn,
                            'DocumentGenerationRun' AS code,
                            1                       AS rankNo
                     UNION ALL
                     SELECT '预览和下载', 'Preview and download', 'DocumentGenerationReadFile', 2
                     UNION ALL
                     SELECT '管理模板', 'Manage templates', 'DocumentGenerationTemplateManage', 3
                     UNION ALL
                     SELECT '发布模板', 'Publish templates', 'DocumentGenerationTemplatePublish', 4) b
WHERE p.code = 'DocumentGenerationView'
  AND NOT EXISTS (SELECT 1 FROM permission WHERE code = b.code);

INSERT INTO sys_open_api_endpoint
(endpoint_code, module_code, display_name, http_method, path_template,
 api_version, data_scope_type, status, created_at, updated_at)
SELECT e.code,
       'document-generation',
       e.display_name,
       e.http_method,
       e.path,
       'v1',
       'NONE',
       'REGISTERED',
       UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM (SELECT 'document-generation.templates.read'     AS code,
             '文档生成模板列表'                       AS display_name,
             'GET'                                    AS http_method,
             '/open/v1/document-generation/templates' AS path
      UNION ALL
      SELECT 'document-generation.templates.current.read',
             '文档生成当前模板',
             'GET',
             '/open/v1/document-generation/templates/{id}/current'
      UNION ALL
      SELECT 'document-generation.generations.create',
             '发起文档生成',
             'POST',
             '/open/v1/document-generation/generations'
      UNION ALL
      SELECT 'document-generation.generations.read',
             '文档生成记录详情',
             'GET',
             '/open/v1/document-generation/generations/{id}'
      UNION ALL
      SELECT 'document-generation.generations.file.read',
             '文档生成成品下载',
             'GET',
             '/open/v1/document-generation/generations/{id}/file') e
WHERE NOT EXISTS (SELECT 1 FROM sys_open_api_endpoint WHERE endpoint_code = e.code);
