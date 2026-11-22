package me.lendev.flow.common.response;

import java.util.List;

import lombok.Data;

/**
 * 包装分页数据
 */
@Data
public class Page<T> {

    /**
     * 当前页数据
     */
    private List<T> pageData;

    /**
     * 总记录数
     */
    private Long total;


    /**
     * flexPage转成Page
     *
     * @param flexPage Flex 分页结果
     * @param <T>      类型
     * @return page
     */
    public static <T> Page<T> convert(com.mybatisflex.core.paginate.Page<T> flexPage) {
        Page<T> page = new Page<>();
        page.setPageData(flexPage.getRecords());
        page.setTotal(flexPage.getTotalRow());
        return page;
    }
}
