-- 可选示例数据。在新版流程表结构就绪后执行。
-- 只初始化示例角色、字段和模板草稿，不创建用户、授权、流程实例或待办。
-- 通过模板、角色和字段编码判断是否已初始化，可重复执行。

-- 示例节点使用独立角色编码，执行后按环境配置成员及主办。
INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_REQUESTER',
       '流程示例 · 经办',
       'Workflow Demo · Requester',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_REQUESTER');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_BUDGET',
       '流程示例 · 预算',
       'Workflow Demo · Budget Review',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_BUDGET');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_COMPLIANCE',
       '流程示例 · 合规',
       'Workflow Demo · Compliance',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_COMPLIANCE');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_FINANCE',
       '流程示例 · 财务',
       'Workflow Demo · Finance',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_FINANCE');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_CASHIER',
       '流程示例 · 出纳',
       'Workflow Demo · Cashier',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_CASHIER');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_EXPENSE',
       '流程示例 · 费用复核',
       'Workflow Demo · Expense Review',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_EXPENSE');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_LEGAL',
       '流程示例 · 法务',
       'Workflow Demo · Legal',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_LEGAL');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_ARCHIVE',
       '流程示例 · 归档',
       'Workflow Demo · Records',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_ARCHIVE');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_SECURITY',
       '流程示例 · 安全',
       'Workflow Demo · Security',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_SECURITY');

INSERT INTO `role` (`code`, `name`, `nameEn`, `enable`)
SELECT 'WF_DEMO_OPS',
       '流程示例 · 运维',
       'Workflow Demo · Operations',
       1 WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'WF_DEMO_OPS');

-- 示例字段定义，可同时被多份模板引用。
INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `enable`)
SELECT 'wf_demo_subject',
       '事项名称',
       'Subject',
       'TEXT',
       1 WHERE NOT EXISTS (SELECT 1 FROM `sys_field_definition` WHERE `code` = 'wf_demo_subject');

INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `enable`)
SELECT 'wf_demo_amount',
       '金额',
       'Amount',
       'DECIMAL',
       1 WHERE NOT EXISTS (SELECT 1 FROM `sys_field_definition` WHERE `code` = 'wf_demo_amount');

INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `enable`)
SELECT 'wf_demo_description',
       '业务说明',
       'Description',
       'TEXT',
       1 WHERE NOT EXISTS (SELECT 1 FROM `sys_field_definition` WHERE `code` = 'wf_demo_description');

INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `enable`)
SELECT 'wf_demo_files',
       '业务附件',
       'Attachments',
       'ATTACHMENT',
       1 WHERE NOT EXISTS (SELECT 1 FROM `sys_field_definition` WHERE `code` = 'wf_demo_files');

INSERT INTO `sys_field_definition` (`code`, `name`, `nameEn`, `dataType`, `enable`)
SELECT 'wf_demo_decision',
       '复核说明',
       'Review Note',
       'TEXT',
       1 WHERE NOT EXISTS (SELECT 1 FROM `sys_field_definition` WHERE `code` = 'wf_demo_decision');

-- 采购审批：预算广播和合规会签并行，汇总后进入财务审批。
INSERT INTO `wf_definition` (`code`, `revision`, `name`, `status`, `schemaVersion`, `stepsJson`, `fieldsJson`,
                             `createdBy`)
SELECT 'WF_DEMO_PURCHASE',
       1,
       '采购审批',
       'DRAFT',
       2,
       '[{"key":"request","order":1,"title":"提交申请","roleCode":"WF_DEMO_REQUESTER","mode":"PRIMARY","requiredFields":["wf_demo_subject","wf_demo_amount"]},
        {"key":"review","order":2,"title":"部门会审"},
        {"key":"budget","order":1,"title":"预算核验","roleCode":"WF_DEMO_BUDGET","mode":"BROADCAST","parentKey":"review"},
        {"key":"compliance","order":2,"title":"合规核验","roleCode":"WF_DEMO_COMPLIANCE","mode":"ALL","parentKey":"review"},
        {"key":"finance","order":3,"title":"财务审批","roleCode":"WF_DEMO_FINANCE","mode":"PRIMARY","editableFields":["wf_demo_decision"]}]',
       '[{"code":"wf_demo_subject"},{"code":"wf_demo_amount"},{"code":"wf_demo_description"},{"code":"wf_demo_files"},{"code":"wf_demo_decision"}]',
       0 WHERE NOT EXISTS (SELECT 1 FROM `wf_definition` WHERE `code` = 'WF_DEMO_PURCHASE');

-- 付款复核：财务采用单人模式，可演示休假双发。
INSERT INTO `wf_definition` (`code`, `revision`, `name`, `status`, `schemaVersion`, `stepsJson`, `fieldsJson`,
                             `createdBy`)
SELECT 'WF_DEMO_PAYMENT',
       1,
       '付款复核',
       'DRAFT',
       2,
       '[{"key":"request","order":1,"title":"提交付款","roleCode":"WF_DEMO_REQUESTER","mode":"PRIMARY","requiredFields":["wf_demo_subject","wf_demo_amount"]},
        {"key":"finance","order":2,"title":"财务复核","roleCode":"WF_DEMO_FINANCE","mode":"PRIMARY","editableFields":["wf_demo_decision"]},
        {"key":"cashier","order":3,"title":"出纳确认","roleCode":"WF_DEMO_CASHIER","mode":"PRIMARY"}]',
       '[{"code":"wf_demo_subject"},{"code":"wf_demo_amount"},{"code":"wf_demo_files"},{"code":"wf_demo_decision"}]',
       0 WHERE NOT EXISTS (SELECT 1 FROM `wf_definition` WHERE `code` = 'WF_DEMO_PAYMENT');

-- 费用报销：广播任务先处理者生效，下一节点未处理时可收回。
INSERT INTO `wf_definition` (`code`, `revision`, `name`, `status`, `schemaVersion`, `stepsJson`, `fieldsJson`,
                             `createdBy`)
SELECT 'WF_DEMO_EXPENSE',
       1,
       '费用报销',
       'DRAFT',
       2,
       '[{"key":"request","order":1,"title":"提交报销","roleCode":"WF_DEMO_REQUESTER","mode":"PRIMARY","requiredFields":["wf_demo_subject","wf_demo_amount"]},
        {"key":"review","order":2,"title":"费用广播","roleCode":"WF_DEMO_EXPENSE","mode":"BROADCAST"},
        {"key":"finance","order":3,"title":"财务核准","roleCode":"WF_DEMO_FINANCE","mode":"PRIMARY"}]',
       '[{"code":"wf_demo_subject"},{"code":"wf_demo_amount"},{"code":"wf_demo_files"}]',
       0 WHERE NOT EXISTS (SELECT 1 FROM `wf_definition` WHERE `code` = 'WF_DEMO_EXPENSE');

-- 合同审查：合同附件必填，法务与财务并行审查。
INSERT INTO `wf_definition` (`code`, `revision`, `name`, `status`, `schemaVersion`, `stepsJson`, `fieldsJson`,
                             `createdBy`)
SELECT 'WF_DEMO_CONTRACT',
       1,
       '合同审查',
       'DRAFT',
       2,
       '[{"key":"request","order":1,"title":"提交合同","roleCode":"WF_DEMO_REQUESTER","mode":"PRIMARY","requiredFields":["wf_demo_subject","wf_demo_files"]},
        {"key":"review","order":2,"title":"联合会审"},
        {"key":"legal","order":1,"title":"法务会签","roleCode":"WF_DEMO_LEGAL","mode":"ALL","parentKey":"review"},
        {"key":"finance","order":2,"title":"财务复核","roleCode":"WF_DEMO_FINANCE","mode":"PRIMARY","parentKey":"review","editableFields":["wf_demo_decision"]},
        {"key":"archive","order":3,"title":"归档","roleCode":"WF_DEMO_ARCHIVE","mode":"PRIMARY"}]',
       '[{"code":"wf_demo_subject"},{"code":"wf_demo_amount"},{"code":"wf_demo_files"},{"code":"wf_demo_decision"}]',
       0 WHERE NOT EXISTS (SELECT 1 FROM `wf_definition` WHERE `code` = 'WF_DEMO_CONTRACT');

-- 权限开通：安全角色会签，验证多成员提交和空角色待分派。
INSERT INTO `wf_definition` (`code`, `revision`, `name`, `status`, `schemaVersion`, `stepsJson`, `fieldsJson`,
                             `createdBy`)
SELECT 'WF_DEMO_ACCESS',
       1,
       '权限开通',
       'DRAFT',
       2,
       '[{"key":"request","order":1,"title":"提交开通","roleCode":"WF_DEMO_REQUESTER","mode":"PRIMARY","requiredFields":["wf_demo_subject","wf_demo_description"]},
        {"key":"security","order":2,"title":"安全会签","roleCode":"WF_DEMO_SECURITY","mode":"ALL"},
        {"key":"ops","order":3,"title":"运维开通","roleCode":"WF_DEMO_OPS","mode":"PRIMARY"}]',
       '[{"code":"wf_demo_subject"},{"code":"wf_demo_description"},{"code":"wf_demo_files"}]',
       0 WHERE NOT EXISTS (SELECT 1 FROM `wf_definition` WHERE `code` = 'WF_DEMO_ACCESS');

