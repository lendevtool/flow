package me.lendev.flow.docgen.mapper;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.docgen.entity.DocGenVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocGenVersionMapper extends BaseMapper<DocGenVersion> {
}
