package me.lendev.flow.param.request;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.lendev.flow.common.request.PageRequest;

/**
 * 参数记录分页查询请求，支持按关键字模糊搜索。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ParamPageRequest extends PageRequest {

    @Size(max = 100)
    private String keyword;
}

