package me.lendev.flow.pms.department;

import static me.lendev.flow.pms.department.DepartmentModels.*;
import static me.lendev.flow.pms.department.persistence.table.DepartmentEntryTableDef.DEPARTMENT_ENTRY;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mybatisflex.core.update.UpdateChain;
import com.mybatisflex.core.util.UpdateEntity;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.mapper.DepartmentMapper;
import me.lendev.flow.pms.department.persistence.DepartmentEntry;
import me.lendev.flow.pms.mapper.DepartmentMembershipMapper;
import me.lendev.flow.pms.mapper.DepartmentMapper.MemberRow;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

/**
 * 部门与成员的持久化查询。组织写入由调用方在同一事务内持有组织锁。
 */
@Repository
@RequiredArgsConstructor
public class DepartmentRepository {

    private final DepartmentMapper mapper;
    private final DepartmentMembershipMapper membershipMapper;
    private final UserMapper users;

    /**
     * 在当前可写事务中锁定组织变更，直至提交或回滚。
     */
    public void lockWrites() {
        if (mapper.lockWrites() == null) {
            throw new EmptyResultDataAccessException(1);
        }
    }

    public List<Department> list() {
        return mapper.list().stream().map(DepartmentRepository::department).toList();
    }

    public List<Department> ledBy(Long userId) {
        return mapper.ledBy(userId).stream().map(DepartmentRepository::department).toList();
    }

    public long insert(DepartmentRequest request, String name) {
        var row = new DepartmentEntry();
        applyRequest(row, request, name);
        mapper.insertSelective(row);
        return row.getId();
    }

    public void update(Long id, DepartmentRequest request, String name) {
        var row = UpdateEntity.of(DepartmentEntry.class, id);
        applyRequest(row, request, name);
        mapper.update(row);
    }

    public void setLeader(Long id, Long userId) {
        UpdateChain.of(mapper).set(DEPARTMENT_ENTRY.LEADER_USER_ID, userId)
                .where(DEPARTMENT_ENTRY.ID.eq(id, true)).update();
    }

    /**
     * 清空账号在各部门的负责人关系，调用方须在业务事务内持有组织锁。
     */
    public void clearLeadership(Long userId) {
        UpdateChain.of(mapper).set(DEPARTMENT_ENTRY.LEADER_USER_ID, null)
                .where(DEPARTMENT_ENTRY.LEADER_USER_ID.eq(userId, true)).update();
    }

    public boolean hasMembers(Long id) {
        return membershipMapper.hasMembers(id);
    }

    public List<Member> members(List<Long> ids) {
        return ids.isEmpty() ? List.of() : attachDepartments(mapper.members(ids), List.of());
    }

    /**
     * 按用户批量加载稳定排序的部门归属。
     */
    public Map<Long, List<DepartmentRef>> memberships(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Long, List<DepartmentRef>>();
        for (var row : mapper.memberships(userIds)) {
            result.computeIfAbsent(row.getUserId(), ignored -> new ArrayList<>())
                    .add(new DepartmentRef(row.getDepartmentId(), row.getName()));
        }
        return result;
    }

    /**
     * 先新增关联，再更新主部门，最后移除旧关联，保证复合外键始终成立。
     */
    public void replaceMemberships(Member before, Set<Long> next, Long primary) {
        Set<Long> old = new HashSet<>(before.departments().stream().map(DepartmentRef::id).toList());
        if (old.equals(next) && Objects.equals(before.departmentId(), primary)) {
            return;
        }
        for (Long id : next) {
            if (!old.contains(id)) {
                membershipMapper.add(before.id(), id);
            }
        }
        UpdateChain.of(users).set(USER.DEPARTMENT_ID, primary)
                .set(USER.DEPARTMENT_VERSION, USER.DEPARTMENT_VERSION.add(1))
                .setRaw(USER.UPDATE_TIME, "CURRENT_TIMESTAMP(6)").where(USER.ID.eq(before.id())).update();
        var removed = old.stream().filter(id -> !next.contains(id)).toList();
        membershipMapper.remove(before.id(), removed);
    }

    /**
     * 在账号删除事务内清理主部门指针及所有成员归属。
     */
    public void clearMemberships(Long userId) {
        UpdateChain.of(users).set(USER.DEPARTMENT_ID, null)
                .where(USER.ID.eq(userId, true)).and(USER.DEPARTMENT_ID.isNotNull()).update();
        membershipMapper.clear(userId);
    }

    private List<Member> attachDepartments(List<MemberRow> rows, List<Long> scope) {
        var byUser = memberships(rows.stream().map(MemberRow::getId).toList());
        var matching = new HashSet<>(scope);
        return rows.stream().map(user -> {
            var all = byUser.getOrDefault(user.getId(), List.of());
            var matched = matching.isEmpty() ? List.<DepartmentRef>of()
                    : all.stream().filter(department -> matching.contains(department.id())).toList();
            return new Member(user.getId(), user.getUsername(), user.getAvatar(), user.getNameCn(), user.getNameEn(),
                    user.getDepartmentId(), user.getDepartmentName(), user.isEnable(), user.getDepartmentVersion(), all, matched);
        }).toList();
    }

    /**
     * 部门范围为空时查询全部，未分配条件可独立筛选空归属。
     */
    public Page<Member> page(MemberQuery query, List<Long> departmentIds) {
        String keyword = null;
        if (query.keyword() != null && !query.keyword().isBlank()) {
            keyword = "%" + query.keyword().strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        }
        boolean unassigned = Boolean.TRUE.equals(query.unassigned());
        var result = new Page<Member>();
        var rows = mapper.pageMembers(unassigned, departmentIds, query.enable(), keyword,
                query.pageNo() == null ? 1 : query.pageNo(), query.size());
        result.setTotal(rows.getTotalRow());
        result.setPageData(attachDepartments(rows.getRecords(), departmentIds));
        return result;
    }

    private static Department department(DepartmentEntry row) {
        return new Department(row.getId(), row.getParentId(), row.getName(), row.getSortOrder(), row.getEnabled(),
                row.getLeaderUserId(), row.getLeaderName());
    }

    private static void applyRequest(DepartmentEntry row, DepartmentRequest request, String name) {
        row.setParentId(request.parentId());
        row.setName(name);
        row.setSortOrder(request.sortOrder());
        row.setEnabled(request.enabled());
    }
}
