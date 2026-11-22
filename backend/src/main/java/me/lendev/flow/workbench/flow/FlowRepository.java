package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.FlowModels.*;
import static me.lendev.flow.workbench.flow.entity.table.FlowDefinitionTableDef.FLOW_DEFINITION;
import static me.lendev.flow.workbench.flow.entity.table.FlowInstanceTableDef.FLOW_INSTANCE;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.workbench.flow.entity.FlowDefinition;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.flow.mapper.FlowDefinitionMapper;
import me.lendev.flow.workbench.flow.mapper.FlowInstanceMapper;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 读写流程模块独占的模板与运行状态，事务性动作先锁定运行主行。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
class FlowRepository {

    private static final TypeReference<List<Step>> DEFINITION_STEPS = new TypeReference<>() {
    };
    private static final TypeReference<List<Node>> RUN_STEPS = new TypeReference<>() {
    };

    private final FlowDefinitionMapper definitions;
    private final FlowInstanceMapper instances;
    private final ObjectMapper json;

    /**
     * 查询旧版（schemaVersion=1）模板，published 为 true 时仅返回已发布版本。
     */
    List<Definition> definitions(boolean published) {
        var query = QueryWrapper.create().where(FLOW_DEFINITION.SCHEMA_VERSION.eq(1))
                .and(FLOW_DEFINITION.STATUS.eq(DefinitionStatus.PUBLISHED, published))
                .orderBy(FLOW_DEFINITION.CODE.asc(), FLOW_DEFINITION.REVISION.desc());
        return definitions.selectListByQuery(query).stream().map(this::readDefinition).toList();
    }

    /**
     * 读取模板，lock 为 true 时加行锁，不存在时返回 empty。
     */
    Optional<Definition> definition(long id, boolean lock) {
        var query = QueryWrapper.create().where(FLOW_DEFINITION.ID.eq(id)).and(FLOW_DEFINITION.SCHEMA_VERSION.eq(1));
        if (lock) {
            query.forUpdate();
        }
        return Optional.ofNullable(definitions.selectOneByQuery(query)).map(this::readDefinition);
    }

    /**
     * 锁定并读取同编码最新修订的模板，用于草稿唯一性检查。
     *
     * @throws TodoConflictException 最新版本为新版结构，旧接口不可操作
     */
    Optional<Definition> latestDefinition(String code) {
        var query = QueryWrapper.create().where(FLOW_DEFINITION.CODE.eq(code, true))
                .orderBy(FLOW_DEFINITION.REVISION.desc()).limit(1).forUpdate();
        return Optional.ofNullable(definitions.selectOneByQuery(query))
                .map(row -> {
                    if (row.getSchemaVersion() != 1) {
                        throw new TodoConflictException("error.flow.schema-version");
                    }
                    return readDefinition(row);
                });
    }

    /**
     * 插入模板草稿并返回自增 ID，编码+修订号唯一约束兜底并发创建。
     */
    long createDefinition(String code, int revision, String name, List<Step> steps, int actor, LocalDateTime now) {
        var row = new FlowDefinition();
        row.setCode(code);
        row.setRevision(revision);
        row.setName(name);
        row.setStepsJson(json.writeValueAsString(steps));
        row.setCreatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        definitions.insertSelective(row);
        return row.getId();
    }

    /**
     * 按版本号条件更新模板，未命中视为版本冲突。
     */
    void updateDefinition(Definition next, long version, LocalDateTime now) {
        requireUpdated(UpdateChain.of(definitions)
                .set(FLOW_DEFINITION.NAME, next.name())
                .set(FLOW_DEFINITION.STEPS_JSON, json.writeValueAsString(next.steps()))
                .set(FLOW_DEFINITION.STATUS, next.status())
                .set(FLOW_DEFINITION.VERSION, next.version())
                .set(FLOW_DEFINITION.UPDATED_AT, now)
                .where(FLOW_DEFINITION.ID.eq(next.id())).and(FLOW_DEFINITION.VERSION.eq(version)).update());
    }

    /**
     * 读取旧版流程运行状态，lock 为 true 时加行锁。
     */
    Optional<Run> run(long id, boolean lock) {
        var query = legacyRuns().and(FLOW_INSTANCE.ID.eq(id));
        if (lock) {
            query.forUpdate();
        }
        return Optional.ofNullable(instances.selectOneByQuery(query)).map(this::readRun);
    }

    /**
     * 按幂等作用域与请求键查询运行状态及原请求摘要。
     */
    Optional<RequestMatch> byRequest(String scope, String requestKey) {
        var query = legacyRuns().and(FLOW_INSTANCE.REQUEST_SCOPE.eq(scope, true))
                .and(FLOW_INSTANCE.REQUEST_KEY.eq(requestKey, true));
        return Optional.ofNullable(instances.selectOneByQuery(query))
                .map(row -> new RequestMatch(readRun(row), row.getRequestHash()));
    }

    private static QueryWrapper legacyRuns() {
        return QueryWrapper.create().and(FLOW_INSTANCE.DEFINITION_ID.in(QueryWrapper.create()
                .select(FLOW_DEFINITION.ID).from(FLOW_DEFINITION).where(FLOW_DEFINITION.SCHEMA_VERSION.eq(1))));
    }

    /**
     * 插入流程运行主行并返回自增 ID，初始步骤号 1、版本 0。
     */
    long createRun(Run initial, String scope, String keyValue, String hash, LocalDateTime now) {
        var row = new FlowInstance();
        row.setDefinitionId(initial.definitionId());
        row.setTitle(initial.title());
        row.setOwnerUserId(initial.ownerUserId());
        row.setOwnerName(initial.ownerName());
        var source = initial.source();
        if (source != null) {
            row.setSourceModule(source.module());
            row.setBusinessType(source.type());
            row.setBusinessId(source.id());
        }
        row.setRequestScope(scope);
        row.setRequestKey(keyValue);
        row.setRequestHash(hash);
        row.setStepsStateJson(json.writeValueAsString(initial.steps()));
        row.setCurrentStepNo(1);
        var dimensions = initial.dimensions();
        row.setDimension1ItemId(dimensions.get(1));
        row.setDimension2ItemId(dimensions.get(2));
        row.setDimension3ItemId(dimensions.get(3));
        row.setDimension4ItemId(dimensions.get(4));
        row.setDimension5ItemId(dimensions.get(5));
        row.setStartedAt(now);
        row.setUpdatedAt(now);
        instances.insertSelective(row);
        return row.getId();
    }

    /**
     * 按版本号条件覆盖运行状态，未命中视为版本冲突。
     */
    void writeRun(Run next, long version, LocalDateTime now) {
        requireUpdated(UpdateChain.of(instances)
                .set(FLOW_INSTANCE.STEPS_STATE_JSON, json.writeValueAsString(next.steps()))
                .set(FLOW_INSTANCE.CURRENT_STEP_NO, next.currentStepNo())
                .set(FLOW_INSTANCE.STATUS, next.status())
                .set(FLOW_INSTANCE.VERSION, next.version())
                .set(FLOW_INSTANCE.CLOSED_AT, next.closedAt())
                .set(FLOW_INSTANCE.UPDATED_AT, now)
                .where(FLOW_INSTANCE.ID.eq(next.id())).and(FLOW_INSTANCE.VERSION.eq(version)).update());
    }

    private Definition readDefinition(FlowDefinition row) {
        return new Definition(row.getId(), row.getCode(), row.getRevision(), row.getName(), row.getStatus(),
                json.readValue(row.getStepsJson(), DEFINITION_STEPS), row.getVersion());
    }

    private Run readRun(FlowInstance row) {
        Map<Integer, Long> dimensions = new LinkedHashMap<>();
        Long[] items = {row.getDimension1ItemId(), row.getDimension2ItemId(), row.getDimension3ItemId(),
                row.getDimension4ItemId(), row.getDimension5ItemId()};
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                dimensions.put(i + 1, items[i]);
            }
        }
        Source source = row.getSourceModule() == null ? null
                : new Source(row.getSourceModule(), row.getBusinessType(), row.getBusinessId(), row.getOwnerUserId());
        return new Run(row.getId(), row.getDefinitionId(), row.getTitle(), row.getOwnerUserId(), row.getOwnerName(), source,
                Map.copyOf(dimensions), json.readValue(row.getStepsStateJson(), RUN_STEPS), row.getCurrentStepNo(),
                row.getStatus(), row.getVersion(), row.getClosedAt());
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) {
            log.info("流程条件更新未命中，版本已变化");
            throw new TodoConflictException("error.todo.version");
        }
    }
}
