package me.lendev.flow.param.support;

import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.PermissionCode;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;
import me.lendev.flow.param.mapper.ParamGroupMapper;
import me.lendev.flow.param.mapper.ParamItemMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 维护待办公共维度的保留参数。绑定变更和待办写入均先锁定槽位分类，再读取槽位和字典。
 */
@Component
@RequiredArgsConstructor
public class TodoDimensionParameters {

    public static final String SLOT_GROUP = "WORKBENCH_TODO_DIMENSION";
    public static final int SLOT_COUNT = 5;
    private static final String DICTIONARY_ERROR = "error.todo.dimension.dictionary";
    private static final String DICTIONARY_PREFIX = "WORKBENCH_TODO_DICT_";
    private static final String BINDING_LOCKED = "bindingLocked";

    private final ParamGroupMapper groups;
    private final ParamItemMapper items;
    private final ObjectMapper json;

    /**
     * 读取固定槽位分类；写入事务必须先锁定该分类，再访问关联配置。
     */
    public ParamGroup slotGroup(boolean lock) {
        var query = QueryWrapper.create().where(PARAM_GROUP.CODE.eq(SLOT_GROUP));
        return groups.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    /**
     * 读取槽位明细。调用方加锁时须已持有槽位分类锁。
     */
    public List<ParamItem> slots(long groupId, boolean lock) {
        var query = QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(groupId)).orderBy(PARAM_ITEM.ID.asc());
        return items.selectListByQuery(lock ? query.forUpdate() : query);
    }

    /**
     * 返回固定槽位对应的参数明细编码。
     */
    public static String slotCode(int slot) {
        return "DIMENSION_" + slot;
    }

    /**
     * 返回槽位当前绑定的字典 ID，未绑定时返回空。
     */
    public static Long dictionaryId(ParamItem slot) {
        String raw = slot.getValue1();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.strip());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 配置只接受稳定的参数分类主键。
        }
        throw BadRequestException.localized(DICTIONARY_ERROR);
    }

    /**
     * 专用字典由不可修改的参数分类编码前缀标记。
     */
    public static boolean isDictionary(String code) {
        return code.startsWith(DICTIONARY_PREFIX) && code.length() > DICTIONARY_PREFIX.length();
    }

    /**
     * 判断分类是否为待办公共维度槽位或专用字典。
     */
    public static boolean isProtected(String code) {
        return SLOT_GROUP.equals(code) || isDictionary(code);
    }

    /**
     * 系统参数接口需同时具备原有参数权限和专用配置权限。
     */
    public static void requireWritePermission(String code) {
        if (isProtected(code) && !StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(PermissionCode.WORKBENCH_TODO_PUBLIC_DIMENSION_MANAGE);
        }
    }

    /**
     * 固定槽位在初始化时创建，普通参数写入只能编辑已有槽位。
     */
    public static void requireMutableStructure(String code) {
        if (SLOT_GROUP.equals(code)) {
            throw BadRequestException.localized("error.todo.dimension.fixed");
        }
    }

    /**
     * 校验单个槽位的绑定并保留服务端锁定标记。调用方已持有槽位分类锁。
     */
    public void updateSlot(ParamItem slot, Long previousDictionaryId, boolean locked) {
        Long dictionaryId = dictionaryId(slot);
        if (locked && !Objects.equals(previousDictionaryId, dictionaryId)) {
            throw BadRequestException.localized("error.todo.dimension.binding-locked");
        }
        if (dictionaryId == null) {
            if (Boolean.TRUE.equals(slot.getEnable())) {
                throw BadRequestException.localized(DICTIONARY_ERROR);
            }
            slot.setValue1(null);
        } else {
            validateBinding(slot, dictionaryId);
            slot.setValue1(dictionaryId.toString());
        }
        var extra = slot.getExtra() instanceof ObjectNode object ? object.deepCopy() : json.createObjectNode();
        extra.remove(BINDING_LOCKED);
        if (locked) {
            extra.put(BINDING_LOCKED, true);
        }
        slot.setExtra(extra);
    }

    private void validateBinding(ParamItem slot, long dictionaryId) {
        if (slots(slot.getGroupId(), true).stream().anyMatch(other -> !other.getId().equals(slot.getId())
                && Objects.equals(dictionaryId(other), dictionaryId))) {
            throw BadRequestException.localized("error.todo.dimension.dictionary-duplicate");
        }
        ParamGroup dictionary = groups.selectOneByQuery(QueryWrapper.create().where(PARAM_GROUP.ID.eq(dictionaryId)).forUpdate());
        if (dictionary == null || !isDictionary(dictionary.getCode())
                || Boolean.TRUE.equals(slot.getEnable()) && !Boolean.TRUE.equals(dictionary.getEnable())) {
            throw BadRequestException.localized(DICTIONARY_ERROR);
        }
    }

    /**
     * 删除专用字典前拒绝移除被槽位绑定的分类；调用方已持有槽位分类锁和字典分类锁。
     */
    public void requireUnbound(ParamGroup slotGroup, long dictionaryId) {
        if (slotGroup != null && slots(slotGroup.getId(), true).stream()
                .anyMatch(slot -> Objects.equals(dictionaryId(slot), dictionaryId))) {
            throw BadRequestException.localized("error.todo.dimension.dictionary-in-use");
        }
    }

    /**
     * 返回槽位是否已被真实业务数据引用。
     */
    public static boolean isBindingLocked(ParamItem slot) {
        return slot.getExtra() != null && slot.getExtra().path(BINDING_LOCKED).asBoolean(false);
    }

    /**
     * 在调用方事务中固定字典绑定；只有首次设置锁定标记时才刷新参数分类版本。
     */
    public void freezeBinding(ParamGroup slotGroup, ParamItem slot) {
        if (!isBindingLocked(slot)) {
            var extra = slot.getExtra() instanceof ObjectNode object ? object.deepCopy() : json.createObjectNode();
            extra.put(BINDING_LOCKED, true);
            ParamItem patch = new ParamItem();
            patch.setId(slot.getId());
            patch.setExtra(extra);
            items.update(patch);
            slotGroup.setVersion(slotGroup.getVersion() + 1);
            ParamGroup groupPatch = new ParamGroup();
            groupPatch.setId(slotGroup.getId());
            groupPatch.setVersion(slotGroup.getVersion());
            groups.update(groupPatch);
        }
    }
}
