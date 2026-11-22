-- 字段管理的五个专用开放 API，与已配置导入的字段只读来源并存。
-- 执行前部署含 OpenFieldController 的后端，并完成开放 API 基础迁移。
-- 新记录为 REGISTERED，不自动发布或授权；无需 sys_open_api_read_mapping 记录。
-- 契约相同的既有记录保留 ID、名称、版本、状态及授权；冲突由唯一约束拒绝整条插入。
SET NAMES utf8mb4;

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
