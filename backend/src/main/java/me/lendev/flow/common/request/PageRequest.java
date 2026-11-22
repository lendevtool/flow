package me.lendev.flow.common.request;

import com.mybatisflex.core.paginate.Page;
import lombok.Data;

/**
 * 分页请求
 */
@Data
public class PageRequest {

    /**
     * 页数
     */
    private Integer pageNo = 1;

    /**
     * 页大小
     */
    private Integer pageSize = 10;


    /**
     * 转换成MyBatis-Flex Page
     *
     * @param <T> 类型
     * @return Page
     */
    public <T> Page<T> toPage() {
        Page<T> page = new Page<>();
        page.setPageSize(pageSize == null ? 10 : Math.clamp(pageSize, 1, 500));
        page.setPageNumber(pageNo == null ? 1 : Math.max(1, pageNo));
        return page;
    }
}
