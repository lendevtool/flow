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
