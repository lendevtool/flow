package me.lendev.flow.field;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.field.FieldModels.*;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.i18n.ApiMessage;
import me.lendev.flow.common.response.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 编排字段管理及与参数字典有关的写入校验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldService {

    private static final Set<String> TYPES = Set.of("TEXT", "INTEGER", "DECIMAL", "BOOLEAN",
            "DATE", "DATETIME", "ENUM", "ENUM_MULTI", "ATTACHMENT");
    private static final Set<String> FORMATS = Set.of("EMAIL", "URL");
    private static final Set<String> CHARACTERS = Set.of("CHINESE", "ENGLISH", "DIGITS", "SPACE",
            "SEPARATORS", "CHINESE_PUNCTUATION", "ENGLISH_PUNCTUATION");
    private static final String INVALID_DICTIONARY = "error.field.dictionary";

    private final FieldRepository repository;
    private final FieldValidator validator;

    public Page<Definition> page(Query query) {
        return repository.page(query);
    }

    public Definition get(long id) {
        return require(id, false);
    }

    /**
     * 按编码查找字段，供业务模块建立已发布规则快照。
     *
     * @param codes 字段编码集合
     * @return 找到的字段定义
     */
    public List<Definition> findByCodes(List<String> codes) {
        return repository.findByCodes(codes);
    }

    /**
     * 读取启用字典及其启用选项，供业务模块建立已发布规则快照。
     *
     * @param groupId 字典参数组 ID
     * @return 已启用的字典选项
     * @throws BadRequestException 字典不存在或已停用
     */
    public List<Option> activeOptions(long groupId) {
        var dictionary = repository.dictionary(groupId, false)
                .filter(Dictionary::enable).orElseThrow(() -> localized(INVALID_DICTIONARY));
        return repository.options(dictionary.id(), false).stream().filter(Option::enable).toList();
    }

    /**
     * 按已保存的字段定义及当前启用的字典选项试测单个值。字段本身停用时仍可试测，输入原样参与校验。
     *
     * @param id    字段定义 ID
     * @param value 待校验的值，可以为 {@code null}
     * @return 第一条失败规则及提示参数，通过时返回 {@code null}
     * @throws BadRequestException 字段不存在
     */
    @Transactional(readOnly = true)
    public ApiMessage validate(long id, JsonNode value) {
        var field = require(id, false).config();
        try {
            Set<String> options = field.dataType().isEnum() && value != null && !value.isNull()
                    ? validationOptions(field.paramGroupId()) : Set.of();
            validator.value(field, value, options);
            return null;
        } catch (BadRequestException e) {
            return e.getApiMessage();
        }
    }

    private Set<String> validationOptions(Long groupId) {
        var group = (groupId == null ? java.util.Optional.<Dictionary>empty() : repository.dictionary(groupId, false))
                .orElseThrow(() -> localized("error.field.validation.dictionary.missing"));
        if (!group.enable()) {
            throw localized("error.field.validation.dictionary.disabled", group.code());
        }
        return enabledCodes(group.id(), false);
    }

    /**
     * 创建字段定义，编码冲突由唯一约束判定。
     *
     * @param config 待保存的定义、规则和默认值
     * @return 新字段的 ID
     * @throws BadRequestException 字段配置无效或编码已存在
     */
    @Audited(AuditOperation.FIELD_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public Long create(Config config) {
        validator.definition(config, allowedOptions(config, null));
        try {
            Long id = repository.insert(config, StpUtil.getLoginIdAsLong());
            log.info("字段定义已写入，requestId={} fieldId={} dataType={} enabled={}",
                    AuditParticipants.currentRequestId(), id, config.dataType(), config.enable());
            return id;
        } catch (DuplicateKeyException e) {
            throw localized("error.field.code.exists");
        }
    }

    /**
     * 全量替换配置，使用行锁同步快照并保持字段编码稳定。
     *
     * @param id     字段定义 ID
     * @param config 完整的新配置
     * @throws BadRequestException 字段不存在、编码变更或配置无效
     */
    @Audited(AuditOperation.FIELD_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, Config config) {
        var before = require(id, true).config();
        if (!before.code().equals(config.code())) {
            throw localized("error.field.code.immutable");
        }
        validator.definition(config, allowedOptions(config, before));
        repository.update(id, config, StpUtil.getLoginIdAsLong());
        log.info("字段定义已更新，requestId={} fieldId={} dataType={} enabled={}",
                AuditParticipants.currentRequestId(), id, config.dataType(), config.enable());
    }

    /**
     * 锁定并删除字段定义，供审计读取一致的变更前快照。
     *
     * @param id 字段定义 ID
     * @throws BadRequestException 字段不存在
     */
    @Audited(AuditOperation.FIELD_DELETE)
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id, true);
        repository.delete(id);
        log.info("字段定义已删除，requestId={} fieldId={}", AuditParticipants.currentRequestId(), id);
    }

    public Metadata metadata() {
        return new Metadata(metadata("FIELD_DATA_TYPE", TYPES), metadata("FIELD_TEXT_FORMAT", FORMATS),
                metadata("FIELD_TEXT_CHARACTER", CHARACTERS));
    }

    public List<Dictionary> dictionaries(String keyword, Long selectedId) {
        return repository.dictionaries(keyword, selectedId);
    }

    public List<Option> options(long id) {
        repository.dictionary(id, false).orElseThrow(() -> localized(INVALID_DICTIONARY));
        return repository.options(id, false);
    }

    Definition require(long id, boolean lock) {
        return repository.find(id, lock).orElseThrow(() -> localized("error.field.missing"));
    }

    private List<Option> metadata(String code, Set<String> supported) {
        return repository.metadata(code).stream().filter(option -> supported.contains(option.code())).toList();
    }

    /**
     * 限定枚举默认值可用的选项。字典与数据类型保持一致时允许沿用原有默认值。
     */
    private Set<String> allowedOptions(Config field, Config before) {
        if (!field.dataType().isEnum() || field.paramGroupId() == null) {
            return Set.of();
        }
        long id = field.paramGroupId();
        var group = repository.dictionary(id, true).orElseThrow(() -> localized(INVALID_DICTIONARY));
        boolean sameDictionary = before != null && before.dataType() == field.dataType()
                && Objects.equals(before.paramGroupId(), field.paramGroupId());
        if (!group.enable() && !sameDictionary) {
            throw localized(INVALID_DICTIONARY);
        }
        var value = field.defaultValue();
        if (value == null || value.isNull()) {
            return Set.of();
        }
        Set<String> result = group.enable() ? enabledCodes(id, true) : new HashSet<>();
        if (sameDictionary) {
            retainCodes(before.defaultValue(), result);
        }
        return result;
    }

    private Set<String> enabledCodes(long groupId, boolean lock) {
        Set<String> codes = new HashSet<>();
        repository.options(groupId, lock).stream().filter(Option::enable).map(Option::code).forEach(codes::add);
        return codes;
    }

    private static void retainCodes(JsonNode value, Set<String> codes) {
        if (value != null && value.isString()) {
            codes.add(value.asString());
        } else if (value != null && value.isArray()) {
            value.forEach(node -> codes.add(node.asString()));
        }
    }
}
