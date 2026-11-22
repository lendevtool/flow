package me.lendev.flow.pms.directory;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.directory.UserDirectoryModels.Entry;
import me.lendev.flow.pms.directory.UserDirectoryModels.Query;
import me.lendev.flow.pms.mapper.UserMapper;
import me.lendev.flow.pms.mapper.UserMapper.DirectoryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询用户工作目录，将业务字段转换为工作目录契约。
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final UserMapper users;

    /**
     * 查询工作目录，默认返回第一页的最多 20 个条目。
     */
    @Transactional(readOnly = true)
    public Page<Entry> page(Query query) {
        int pageNo = query.pageNo() == null ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null ? 20 : query.pageSize();
        var rows = users.pageDirectory(pageNo, pageSize, query.keyword(), query.enabled(), query.departmentId());
        var result = new Page<Entry>();
        result.setTotal(rows.getTotalRow());
        result.setPageData(rows.getRecords().stream().map(UserDirectoryService::entry).toList());
        return result;
    }

    /**
     * 按用户 ID 查询工作目录条目。
     *
     * @throws ResourceNotFoundException 用户记录不存在
     */
    public Entry get(long id) {
        var row = users.findDirectory(id);
        if (row == null) {
            throw new ResourceNotFoundException();
        }
        return entry(row);
    }

    private static Entry entry(DirectoryRow row) {
        return new Entry(row.getId(), row.getNameCn(), row.getNameEn(), row.isEnabled(), row.getDepartmentId(),
                row.getDepartmentName());
    }
}
