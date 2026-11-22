package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.TodoModels.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.workbench.todo.entity.TodoPersonalDimension;
import me.lendev.flow.workbench.todo.entity.TodoPersonalValue;
import me.lendev.flow.workbench.todo.mapper.TodoPersonalDimensionMapper;
import me.lendev.flow.workbench.todo.mapper.TodoPersonalValueMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 个人维度与标记。所有操作仅使用当前登录用户的命名空间。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class TodoPersonalDimensions {

    static final int MAX_VALUE_LENGTH = 100;
    private static final TypeReference<List<PersonalOption>> OPTIONS = new TypeReference<>() {
    };
    private final TodoPersonalDimensionMapper dimensions;
    private final TodoPersonalValueMapper tags;
    private final TodoRepository repository;
    private final TodoSchedule schedule;
    private final ObjectMapper json;

    /**
     * 维度配置结果，changed 为 false 表示内容未变化，未产生写入。
     */
    record Configuration(int userId, PersonalDimension saved, boolean changed) {

    }

    /**
     * 标记写入结果，changedSlots 为实际发生变化的槽位。
     */
    record Tags(long todoId, List<Integer> changedSlots) {

    }

    /**
     * 查询当前登录人的全部个人维度配置。
     */
    List<PersonalDimension> list(int viewer) {
        return dimensions.dimensions(viewer).stream().map(this::read).toList();
    }

    /**
     * 批量查询待办的个人标记，返回 待办 ID -> 槽位 -> 选项键。
     */
    Map<Long, Map<Integer, String>> values(int viewer, Collection<Long> ids) {
        Map<Long, Map<Integer, String>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (var row : tags.values(viewer, ids)) {
            result.computeIfAbsent(row.getTodoId(), id -> new LinkedHashMap<>()).put(row.getSlotNo(), row.getOptionKey());
        }
        return result;
    }

    /**
     * 新建或更新个人维度配置：先锁用户行再锁槽位行，版本号不匹配视为冲突。
     * 已被标记引用的选项不可删除，文本维度有引用时不可改为选项维度。
     *
     * @param slot  槽位号 1-5
     * @param write 配置内容，version 为 null 或 0 表示新建
     * @throws TodoConflictException 版本不匹配
     * @throws BadRequestException   配置不合法或选项仍被引用
     */
    @Audited(AuditOperation.TODO_PERSONAL_CONFIGURE)
    @Transactional(rollbackFor = Exception.class)
    public Configuration configure(int slot, PersonalWrite write) {
        int viewer = TodoAccess.viewer();
        requireSlot(slot);
        String name = cleanName(write.name());
        if (write.options() == null || write.options().size() > 100) {
            throw invalid();
        }
        dimensions.lockUser(viewer);
        var before = lockDimension(viewer, slot);
        if (before.isPresent() && !Objects.equals(write.version(), before.get().version())
                || before.isEmpty() && write.version() != null && write.version() != 0) {
            throw new TodoConflictException("error.todo.version");
        }
        List<PersonalOption> options = normalizeOptions(write.options(), before);
        if (before.isPresent() && name.equals(before.get().name()) && write.enabled() == before.get().enabled()
                && options.equals(before.get().options())) {
            return new Configuration(viewer, before.get(), false);
        }
        before.filter(previous -> !options.equals(previous.options()))
                .ifPresent(previous -> requireRetainedValues(viewer, slot, previous, options));
        long version = before.map(item -> item.version() + 1).orElse(0L);
        var row = new TodoPersonalDimension();
        row.setUserId(viewer);
        row.setSlotNo(slot);
        row.setName(name);
        row.setOptionsJson(json.writeValueAsString(options));
        row.setEnable(write.enabled());
        row.setVersion(version);
        row.setUpdatedAt(schedule.now());
        if (before.isEmpty()) {
            row.setCreatedAt(row.getUpdatedAt());
            dimensions.insert(row);
        } else {
            dimensions.updateDimension(row);
        }
        log.info("个人维度配置保存，用户 ID={}，槽位={}，版本={}，新建={}", viewer, slot, version, before.isEmpty());
        return new Configuration(viewer, new PersonalDimension(slot, name, write.enabled(), version, options), true);
    }

    private void requireRetainedValues(int viewer, int slot, PersonalDimension previous, List<PersonalOption> options) {
        Set<String> referenced = new HashSet<>(tags.referencedValues(viewer, slot));
        if (!referenced.isEmpty() && previous.options().isEmpty()) {
            throw BadRequestException.localized("error.todo.personal-text-in-use");
        }
        options.forEach(option -> referenced.remove(option.key()));
        if (!referenced.isEmpty()) {
            throw BadRequestException.localized("error.todo.personal-option-in-use");
        }
    }

    /**
     * 规范化创建时携带的个人标记，空值槽位直接剔除。
     */
    static Map<Integer, String> normalizeCreateTags(Map<Integer, String> values) {
        if (values == null) {
            return Map.of();
        }
        var normalized = normalizeTags(values);
        normalized.values().removeIf(Objects::isNull);
        return normalized;
    }

    /**
     * 新建待办尚未提交，初始个人标记与该待办在同一事务写入。
     */
    Tags tagOnCreate(long todoId, int viewer, Map<Integer, String> values) {
        values.forEach((slot, value) -> saveTag(viewer, todoId, slot, value, true));
        return new Tags(todoId, List.copyOf(values.keySet()));
    }

    /**
     * 更新待办个人标记：锁定待办并校验可见性，值为 null 表示清除槽位。
     * 与当前值相同的槽位跳过，不产生写入。
     *
     * @throws TodoNotFoundException 待办不存在或当前人不可见
     * @throws BadRequestException   槽位未配置、已停用或选项无效
     */
    @Audited(AuditOperation.TODO_PERSONAL_TAG)
    @Transactional(rollbackFor = Exception.class)
    public Tags tag(long todoId, Map<Integer, String> values) {
        int viewer = TodoAccess.viewer();
        Todo todo = repository.lock(todoId).orElseThrow(TodoNotFoundException::new);
        TodoService.requireVisible(todo, viewer);
        var normalized = normalizeTags(values);
        var current = values(viewer, List.of(todoId)).getOrDefault(todoId, Map.of());
        List<Integer> changed = new ArrayList<>();
        for (var entry : normalized.entrySet()) {
            int slot = entry.getKey();
            String key = entry.getValue();
            String old = current.get(slot);
            if (Objects.equals(key, old)) {
                continue;
            }
            if (key == null) {
                tags.deleteTag(viewer, todoId, slot);
            } else {
                saveTag(viewer, todoId, slot, key, old == null);
            }
            changed.add(slot);
        }
        if (!changed.isEmpty()) {
            log.info("待办个人标记更新，待办 ID={}，用户 ID={}，变更槽位={}", todoId, viewer, changed);
        }
        return new Tags(todoId, List.copyOf(changed));
    }

    private static Map<Integer, String> normalizeTags(Map<Integer, String> values) {
        if (values == null || values.size() > 5) {
            throw invalid();
        }
        Map<Integer, String> normalized = new TreeMap<>();
        values.forEach((slot, value) -> {
            if (slot == null) {
                throw invalid();
            }
            requireSlot(slot);
            String text = value == null || value.isBlank() ? null : value.strip();
            if (text != null && text.length() > MAX_VALUE_LENGTH) {
                throw invalid();
            }
            normalized.put(slot, text);
        });
        return normalized;
    }

    private void saveTag(int viewer, long todoId, int slot, String key, boolean insert) {
        var definition = lockDimension(viewer, slot).orElseThrow(TodoPersonalDimensions::invalid);
        if (!definition.enabled() || !definition.options().isEmpty() && definition.options().stream()
                .noneMatch(option -> option.enabled() && option.key().equals(key))) {
            throw invalid();
        }
        var row = new TodoPersonalValue();
        row.setUserId(viewer);
        row.setTodoId(todoId);
        row.setSlotNo(slot);
        row.setOptionKey(key);
        row.setUpdatedAt(schedule.now());
        if (insert) {
            tags.insert(row);
        } else {
            tags.updateTag(row);
        }
    }

    private Optional<PersonalDimension> lockDimension(int viewer, int slot) {
        return Optional.ofNullable(dimensions.lockDimension(viewer, slot)).map(this::read);
    }

    private PersonalDimension read(TodoPersonalDimension row) {
        return new PersonalDimension(row.getSlotNo(), row.getName(), row.getEnable(), row.getVersion(),
                json.readValue(row.getOptionsJson(), OPTIONS));
    }

    private static List<PersonalOption> normalizeOptions(List<PersonalOption> requested,
                                                         Optional<PersonalDimension> before) {
        var previousKeys = before.stream().flatMap(item -> item.options().stream()).map(PersonalOption::key)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<PersonalOption> result = new ArrayList<>();
        for (var option : requested) {
            if (option == null) {
                throw invalid();
            }
            String name = cleanName(option.name());
            String key = normalizeOptionKey(option.key(), previousKeys);
            if (!keys.add(key) || !names.add(name)) {
                throw invalid();
            }
            result.add(new PersonalOption(key, name, option.enabled()));
        }
        return List.copyOf(result);
    }

    private static String normalizeOptionKey(String key, Set<String> previousKeys) {
        if (key == null || key.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // 客户端可为新选项生成 UUID，已有选项沿用原键。
        if (!previousKeys.contains(key)) {
            try {
                if (!UUID.fromString(key).toString().equals(key)) {
                    throw invalid();
                }
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
        }
        return key;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw invalid();
        }
        return name.strip();
    }

    private static void requireSlot(int slot) {
        if (slot < 1 || slot > 5) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return BadRequestException.localized("error.todo.personal-dimension");
    }
}
