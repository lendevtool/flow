package me.lendev.flow.param.mapper;

/**
 * 参数分类和明细共享的排序更新语句。
 */
final class ParamSortSql {

    static final String SET_ORDER = """
            SET sort = CASE id
                <foreach collection="positions" index="id" item="position">
                    WHEN #{id} THEN #{position}
                </foreach>
                ELSE sort END,
                updatedBy = #{actor}, updatedAt = #{now}
            WHERE id IN
                <foreach collection="positions" index="id" item="position" open="(" separator="," close=")">
                    #{id}
                </foreach>
            </script>
            """;

    private ParamSortSql() {
    }
}
