package me.lendev.flow.param.mapper;

import com.mybatisflex.core.BaseMapper;

import java.time.LocalDateTime;
import java.util.Map;

import me.lendev.flow.param.domain.ParamGroup;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 参数分类 Mapper，继承 MyBatis-Flex 通用 CRUD 能力。
 */
public interface ParamGroupMapper extends BaseMapper<ParamGroup> {

    /**
     * 批量更新已锁定分类的序号和更新人信息。
     */
    @Update("<script>UPDATE sys_param_group " + ParamSortSql.SET_ORDER)
    void updateSort(@Param("positions") Map<Long, Integer> positions, @Param("actor") long actor,
                    @Param("now") LocalDateTime now);
}

