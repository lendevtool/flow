-- 将当前代码中由 @OpenApiEndpoint 声明的接口补登记到已有 MySQL 接口目录。
-- 新记录为 REGISTERED。契约一致的既有记录保留原 ID、发布状态、版本和配置。
-- 同编码的契约不一致，或相同方法和路径已被其他编码占用时，由唯一约束拒绝本次插入。
-- 生成方法见 backend/docs/open-api-development.md 的「生成与导入全量登记 SQL」。
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
FROM (SELECT 'trading-calendar.markets.read'     AS endpoint_code,
             'trading-calendar'                  AS module_code,
             '市场列表'                          AS display_name,
             'GET'                               AS http_method,
             '/open/v1/trading-calendar/markets' AS path_template,
             'v1'                                AS api_version,
             'MARKET'                            AS data_scope_type
      UNION ALL
      SELECT 'trading-calendar.year.read',
             'trading-calendar',
             '年度交易日历',
             'GET',
             '/open/v1/trading-calendar/year',
             'v1',
             'MARKET'
      UNION ALL
      SELECT 'trading-calendar.status.read',
             'trading-calendar',
             '指定日期交易状态',
             'GET',
             '/open/v1/trading-calendar/status',
             'v1',
             'MARKET'
      UNION ALL
      SELECT 'trading-calendar.offset.read',
             'trading-calendar',
             '交易日偏移',
             'GET',
             '/open/v1/trading-calendar/offset',
             'v1',
             'MARKET'
      UNION ALL
      SELECT 'field-definition.public.page.read' AS endpoint_code,
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
             'NONE'
      UNION ALL
      SELECT 'document-generation.templates.read',
             'document-generation',
             '文档生成模板列表',
             'GET',
             '/open/v1/document-generation/templates',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'document-generation.templates.current.read',
             'document-generation',
             '文档生成当前模板',
             'GET',
             '/open/v1/document-generation/templates/{id}/current',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'document-generation.generations.create',
             'document-generation',
             '发起文档生成',
             'POST',
             '/open/v1/document-generation/generations',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'document-generation.generations.read',
             'document-generation',
             '文档生成记录详情',
             'GET',
             '/open/v1/document-generation/generations/{id}',
             'v1',
             'NONE'
      UNION ALL
      SELECT 'document-generation.generations.file.read',
             'document-generation',
             '文档生成成品下载',
             'GET',
             '/open/v1/document-generation/generations/{id}/file',
             'v1',
             'NONE') AS seed
WHERE NOT EXISTS (SELECT 1
                  FROM sys_open_api_endpoint AS existing
                  WHERE existing.endpoint_code = seed.endpoint_code
                    AND existing.http_method = seed.http_method
                    AND existing.path_template = seed.path_template
                    AND existing.api_version = seed.api_version
                    AND existing.data_scope_type = seed.data_scope_type);
