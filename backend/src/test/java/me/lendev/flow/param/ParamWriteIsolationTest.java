package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentDimensions;
import me.lendev.flow.param.domain.*;
import me.lendev.flow.param.mapper.*;
import me.lendev.flow.param.request.*;
import me.lendev.flow.param.support.*;
import me.lendev.flow.pms.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 写隔离测试：用 mock 将"过期一致性读"与"当前锁定读（FOR UPDATE）"分开建模， 验证排序、删除、Schema 变更都基于当前读而非旧快照；真实 MySQL 行为仍由部署环境检查兜底。
 */
class ParamWriteIsolationTest {

    final ParamGroupMapper groups = mock(ParamGroupMapper.class);
    final ParamItemMapper items = mock(ParamItemMapper.class);
    final ParamFieldValidator validator = mock(ParamFieldValidator.class);
    final ParamService service = new ParamService(groups, items, mock(UserMapper.class), validator,
            mock(ParamLookupCache.class), new TodoDimensionParameters(groups, items, new ObjectMapper()),
            mock(DocumentDimensions.class), new ObjectMapper());

    ParamGroup owner() {
        var group = new ParamGroup();
        group.setId(1L);
        group.setCode("config");
        group.setVersion(1L);
        when(groups.selectOneByQuery(any(QueryWrapper.class))).thenReturn(group);
        return group;
    }

    ParamItem child(long id, String value) {
        var item = new ParamItem();
        item.setId(id);
        item.setGroupId(1L);
        item.setCode("item" + id);
        item.setName("current");
        item.setValue1(value);
        item.setCreatedBy(9L);
        return item;
    }

    @Test
    void sortingUsesCurrentMembershipAndWritesOnlyOrderMetadata() {
        owner();
        var current = child(2, "concurrent-committed-value");
        var stale = child(2, "old-snapshot-value");
        // 模拟一致性读与当前读的差异：不带 FOR UPDATE 的查询看到旧快照，带 FOR UPDATE 的看到最新已提交数据。
        when(items.selectListByQuery(any(QueryWrapper.class))).thenAnswer(invocation ->
                List.of(((QueryWrapper) invocation.getArgument(0)).toSQL().contains("FOR UPDATE")
                        ? current : stale));
        var request = new ParamSortRequest();
        request.setIds(List.of(2L));
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            service.sort(1L, request);
        }
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(items).selectListByQuery(query.capture());
        assertTrue(query.getValue().toSQL().contains("FOR UPDATE"), "membership must use a current read");
        verify(items).updateSort(eq(Map.of(2L, 1)), eq(1L), any(LocalDateTime.class));
    }

    @Test
    void deleteRejectsCurrentChildrenEvenWhenOldSnapshotCountWasEmpty() {
        owner();
        // 旧快照统计为 0（看似空组），但当前读仍有子项，删除必须被拒绝。
        when(items.selectCountByCondition(any())).thenReturn(0L);
        when(items.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(child(2, "current")));
        assertThrows(BadRequestException.class, () -> service.deleteGroup(1L));
        verify(groups, never()).deleteById(any());
    }

    @Test
    void schemaChangesValidateCurrentChildrenInsteadOfOldSnapshot() {
        owner();
        var current = child(2, "current");
        // 一致性读返回空、当前读返回子项，验证 Schema 校验拿到的是当前读结果。
        when(items.selectListByQuery(any(QueryWrapper.class))).thenAnswer(invocation ->
                ((QueryWrapper) invocation.getArgument(0)).toSQL().contains("FOR UPDATE") ? List.of(current) : List.of());
        var request = new ParamGroupRequest();
        request.setCode("config");
        request.setName("配置");
        request.setFieldConfig(new ObjectMapper().createObjectNode());
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            service.updateGroup(1L, request);
        }
        verify(validator).change(isNull(), eq(request.getFieldConfig()), eq(List.of(current)));
    }

    @Test
    void unchangedSchemaDoesNotReadOrLockExistingItems() {
        var group = owner();
        group.setFieldConfig(new ObjectMapper().createObjectNode());
        var request = new ParamGroupRequest();
        request.setCode("config");
        request.setName("配置");
        request.setFieldConfig(group.getFieldConfig().deepCopy());
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            service.updateGroup(1L, request);
        }
        verifyNoInteractions(items);
    }

    @Test
    void unchangedItemOrderDoesNotWriteItemsOrInvalidateTheirCacheVersion() {
        owner();
        var item = child(2, "current");
        item.setSort(1);
        when(items.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(item));
        var request = new ParamSortRequest();
        request.setIds(List.of(item.getId()));

        service.sort(1L, request);

        verify(items, never()).updateSort(anyMap(), anyLong(), any());
        verify(groups, never()).update(any(ParamGroup.class), anyBoolean());
    }
}
