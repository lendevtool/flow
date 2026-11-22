package me.lendev.flow.docgen.mapper;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.docgen.entity.DocGenRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocGenRecordMapper extends BaseMapper<DocGenRecord> {
    /**
     * 清空到期的输入快照，其余历史字段继续保留。
     */
    @Update("UPDATE biz_doc_generation SET inputJson = NULL WHERE id = #{id}")
    void clearInput(@Param("id") long id);
}
