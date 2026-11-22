package me.lendev.flow.document.mapper;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.document.entity.DocumentRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档主表访问。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentRow> {

}
