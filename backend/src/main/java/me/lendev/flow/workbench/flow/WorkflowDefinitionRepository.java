package me.lendev.flow.workbench.flow;

import static com.mybatisflex.core.query.QueryMethods.exists;
import static com.mybatisflex.core.query.QueryMethods.notExists;
import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.workbench.flow.WorkflowDefinitionModels.*;
import static me.lendev.flow.workbench.flow.entity.table.FlowDefinitionTableDef.FLOW_DEFINITION;
import static me.lendev.flow.workbench.flow.entity.table.FlowInstanceTableDef.FLOW_INSTANCE;

import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.mapper.RoleMapper;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.entity.FlowDefinition;
import me.lendev.flow.workbench.flow.mapper.FlowDefinitionMapper;
import me.lendev.flow.workbench.flow.mapper.WorkflowNotificationRefMapper;
import me.lendev.flow.workbench.flow.mapper.WorkflowNotificationRefMapper.Ref;
import me.lendev.flow.workbench.todo.TodoConflictException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 新版模板读写。历史工作台模板由原仓储读取。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
class WorkflowDefinitionRepository {

    static final int SCHEMA_VERSION = 2;
    private static final TypeReference<List<Node>> NODES = new TypeReference<>() {
    };
    private static final TypeReference<List<FieldBinding>> FIELDS = new TypeReference<>() {
    };
    private final FlowDefinitionMapper mapper;
    private final WorkflowNotificationRefMapper references;
    private final RoleMapper roles;
    private final ObjectMapper json;

    /**
     * 分页查询新版模板，keyword 匹配编码或名称（已转义 LIKE 通配符）。
     */
    Page<View> page(boolean latestOnly, String keyword, DefinitionStatus status, int number, int size) {
        var query = newer().orderBy(FLOW_DEFINITION.CODE.asc(), FLOW_DEFINITION.REVISION.desc());
        if (latestOnly) {
            query.and(latestRevision(null));
        }
        query.and(FLOW_DEFINITION.STATUS.eq(status, status != null));
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.and(raw("""
                    (LOWER(wf_definition.code) LIKE LOWER(?) ESCAPE '!'
                      OR LOWER(wf_definition.name) LIKE LOWER(?) ESCAPE '!')
                    """, pattern, pattern));
        }
        return page(query, number, size);
    }

    /**
     * 分页查询同编码更早的修订版本。
     */
    Page<View> history(View current, int number, int size) {
        return page(newer().and(FLOW_DEFINITION.CODE.eq(current.code(), true))
                .and(FLOW_DEFINITION.REVISION.lt(current.revision())).orderBy(FLOW_DEFINITION.REVISION.desc()), number, size);
    }

    private Page<View> page(QueryWrapper query, int number, int size) {
        var data = mapper.paginate(number, size, query);
        var page = new Page<View>();
        page.setTotal(data.getTotalRow());
        page.setPageData(views(data.getRecords()));
        return page;
    }

    /**
     * 查询每个编码最新已发布版本的可发起目录。
     */
    List<View> catalog(Set<String> excluded) {
        return views(mapper.selectListByQuery(catalogQuery(excluded)));
    }

    /**
     * 分页查询可发起目录。
     */
    Page<View> catalog(int number, int size, Set<String> excluded) {
        return page(catalogQuery(excluded), number, size);
    }

    private static QueryWrapper catalogQuery(Set<String> excluded) {
        var query = newer().and(FLOW_DEFINITION.STATUS.eq(DefinitionStatus.PUBLISHED))
                .and(latestRevision(DefinitionStatus.PUBLISHED));
        if (!excluded.isEmpty()) {
            query.and(FLOW_DEFINITION.CODE.notIn(excluded));
        }
        return query.orderBy(FLOW_DEFINITION.CODE.asc());
    }

    private static QueryCondition latestRevision(DefinitionStatus status) {
        var candidate = FLOW_DEFINITION.as("newer_version");
        var newerRevision = QueryWrapper.create().select(candidate.ID).from(candidate)
                .where(candidate.CODE.eq(FLOW_DEFINITION.CODE)).and(candidate.SCHEMA_VERSION.eq(SCHEMA_VERSION))
                .and(candidate.REVISION.gt(FLOW_DEFINITION.REVISION)).and(candidate.STATUS.eq(status, status != null));
        return notExists(newerRevision);
    }

    /**
     * 读取新版模板，lock 为 true 时加行锁，不存在返回 empty。
     */
    Optional<View> find(long id, boolean lock) {
        var query = newer().and(FLOW_DEFINITION.ID.eq(id));
        return Optional.ofNullable(mapper.selectOneByQuery(lock ? query.forUpdate() : query)).map(this::view);
    }

    /**
     * 批量读取模板节点配置，供列表页组装当前节点。
     */
    Map<Long, List<Node>> nodesByDefinition(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var rows = mapper.selectListByQuery(QueryWrapper.create().select(FLOW_DEFINITION.ID, FLOW_DEFINITION.STEPS_JSON)
                .where(FLOW_DEFINITION.ID.in(ids)));
        var scenes = scenesByDefinition(ids);
        return rows.stream().collect(Collectors.toMap(FlowDefinition::getId,
                row -> nodes(row, scenes.getOrDefault(row.getId(), Map.of()))));
    }

    /**
     * 判断草稿、已发布模板或运行中的历史版本是否有节点需要该角色的主办。
     */
    boolean requiresPrimaryHandler(String roleCode) {
        var running = QueryWrapper.create().select(FLOW_INSTANCE.ID).from(FLOW_INSTANCE)
                .where(FLOW_INSTANCE.DEFINITION_ID.eq(FLOW_DEFINITION.ID)).and(FLOW_INSTANCE.STATUS.eq(RunStatus.RUNNING));
        var query = newer().select(FLOW_DEFINITION.STEPS_JSON)
                .and(FLOW_DEFINITION.STATUS.in(DefinitionStatus.DRAFT, DefinitionStatus.PUBLISHED).or(exists(running)));
        return mapper.selectListByQuery(query).stream().flatMap(row -> json.readValue(row.getStepsJson(), NODES).stream())
                .anyMatch(node -> node.mode() == Mode.PRIMARY && roleCode.equals(node.roleCode()));
    }

    /**
     * 锁定并读取已发布版本，revision 为 null 时取最新已发布修订。
     */
    Optional<View> published(String code, Integer revision) {
        return published(code, revision, true);
    }

    Optional<View> published(String code, Integer revision, boolean lock) {
        var query = newer().and(FLOW_DEFINITION.CODE.eq(code, true))
                .and(FLOW_DEFINITION.STATUS.eq(DefinitionStatus.PUBLISHED))
                .and(FLOW_DEFINITION.REVISION.eq(revision, revision != null))
                .orderBy(FLOW_DEFINITION.REVISION.desc()).limit(1);
        return Optional.ofNullable(mapper.selectOneByQuery(lock ? query.forUpdate() : query))
                .map(row -> view(row, true));
    }

    /**
     * 锁定并读取同编码最新修订（含草稿），用于草稿唯一性检查。
     */
    Optional<FlowDefinition> latest(String code) {
        return Optional.ofNullable(mapper.selectOneByQuery(QueryWrapper.create()
                .where(FLOW_DEFINITION.CODE.eq(code, true)).orderBy(FLOW_DEFINITION.REVISION.desc()).limit(1).forUpdate()));
    }

    /**
     * 插入新版模板草稿，编码+修订号唯一约束兜底并发创建。
     */
    View insert(String code, int revision, String name, List<FieldBinding> fields, List<Node> nodes, int actor,
                LocalDateTime now) {
        var row = new FlowDefinition();
        row.setCode(code);
        row.setRevision(revision);
        row.setName(name);
        row.setStatus(DefinitionStatus.DRAFT);
        row.setStepsJson(stepsJson(nodes));
        row.setSchemaVersion(SCHEMA_VERSION);
        row.setFieldsJson(json.writeValueAsString(fields));
        row.setCreatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setVersion(0L);
        mapper.insertSelective(row);
        insertReferences(row.getId(), nodes);
        return new View(row.getId(), code, revision, name, row.getStatus(), row.getVersion(), fields, nodes, null);
    }

    /**
     * 按版本号条件覆盖模板并递增版本，首次发布记录发布时间。
     *
     * @throws TodoConflictException 版本已变化
     */
    View update(View current, String name, List<FieldBinding> fields, List<Node> nodes, DefinitionStatus status,
                LocalDateTime now) {
        long nextVersion = current.version() + 1;
        var publishedAt = status == DefinitionStatus.PUBLISHED ? now : current.publishedAt();
        if (!UpdateChain.of(mapper).set(FLOW_DEFINITION.NAME, name)
                .set(FLOW_DEFINITION.FIELDS_JSON, json.writeValueAsString(fields))
                .set(FLOW_DEFINITION.STEPS_JSON, stepsJson(nodes))
                .set(FLOW_DEFINITION.STATUS, status)
                .set(FLOW_DEFINITION.UPDATED_AT, now)
                .set(FLOW_DEFINITION.PUBLISHED_AT, publishedAt)
                .set(FLOW_DEFINITION.VERSION, nextVersion)
                .where(FLOW_DEFINITION.ID.eq(current.id())).and(FLOW_DEFINITION.VERSION.eq(current.version())).update()) {
            log.info("模板条件更新未命中，版本已变化，definitionId={}，期望版本={}", current.id(), current.version());
            throw new TodoConflictException("error.todo.version");
        }
        if (status == DefinitionStatus.DRAFT && !current.nodes().equals(nodes)) {
            references.deleteByDefinitionId(current.id());
            insertReferences(current.id(), nodes);
        }
        return new View(current.id(), current.code(), current.revision(), name, status, nextVersion, fields, nodes, publishedAt);
    }

    private String stepsJson(List<Node> nodes) {
        return json.writeValueAsString(nodes.stream().map(Node::withoutScenes).toList());
    }

    private void insertReferences(long definitionId, List<Node> nodes) {
        var refs = new ArrayList<Ref>();
        for (var node : nodes) {
            for (var trigger : Trigger.values()) {
                String code = node.sceneCode(trigger);
                if (code != null) {
                    refs.add(new Ref(definitionId, node.key(), trigger.name(), code));
                }
            }
        }
        if (!refs.isEmpty()) {
            references.insertRefs(refs);
        }
    }

    /**
     * 查询启用角色及其是否有启用成员，按编码升序。
     */
    List<RoleOption> activeRoles() {
        var withMembers = QueryWrapper.create().select(USER_ROLE.ROLE_ID).from(USER_ROLE)
                .join(USER).on(USER_ROLE.USER_ID.eq(USER.ID)).where(USER.ENABLE.eq(true));
        var available = roles.selectListByQuery(QueryWrapper.create().select(ROLE.ID)
                        .where(ROLE.ENABLE.eq(true)).and(ROLE.ID.in(withMembers))).stream()
                .map(Role::getId).collect(Collectors.toSet());
        return roles.selectListByQuery(QueryWrapper.create().where(ROLE.ENABLE.eq(true)).orderBy(ROLE.CODE.asc()))
                .stream().map(role -> new RoleOption(role.getCode(), role.getName(), role.getNameEn(),
                        available.contains(role.getId()))).toList();
    }

    private static QueryWrapper newer() {
        return QueryWrapper.create().where(FLOW_DEFINITION.SCHEMA_VERSION.eq(SCHEMA_VERSION));
    }

    private View view(FlowDefinition row) {
        return view(row, false);
    }

    private View view(FlowDefinition row, boolean lock) {
        var scenes = scenesByDefinition(List.of(row.getId()), lock);
        return view(row, scenes.getOrDefault(row.getId(), Map.of()));
    }

    private List<View> views(List<FlowDefinition> rows) {
        var scenes = scenesByDefinition(rows.stream().map(FlowDefinition::getId).toList());
        return rows.stream().map(row -> view(row, scenes.getOrDefault(row.getId(), Map.of()))).toList();
    }

    private View view(FlowDefinition row, Map<String, Map<Trigger, String>> scenes) {
        return new View(row.getId(), row.getCode(), row.getRevision(), row.getName(), row.getStatus(), row.getVersion(),
                json.readValue(row.getFieldsJson(), FIELDS), nodes(row, scenes), row.getPublishedAt());
    }

    private List<Node> nodes(FlowDefinition row, Map<String, Map<Trigger, String>> scenes) {
        return json.readValue(row.getStepsJson(), NODES).stream().map(node -> {
            var codes = scenes.getOrDefault(node.key(), Map.of());
            return node.withScenes(codes.get(Trigger.APPROVED), codes.get(Trigger.REJECTED));
        }).toList();
    }

    private Map<Long, Map<String, Map<Trigger, String>>> scenesByDefinition(List<Long> ids) {
        return scenesByDefinition(ids, false);
    }

    private Map<Long, Map<String, Map<Trigger, String>>> scenesByDefinition(List<Long> ids, boolean lock) {
        var result = new HashMap<Long, Map<String, Map<Trigger, String>>>();
        if (!ids.isEmpty()) {
            for (var ref : references.findByDefinitionIds(ids, lock)) {
                result.computeIfAbsent(ref.definitionId(), ignored -> new HashMap<>())
                        .computeIfAbsent(ref.nodeKey(), ignored -> new EnumMap<>(Trigger.class))
                        .put(Trigger.valueOf(ref.triggerType()), ref.sceneCode());
            }
        }
        return result;
    }
}
