package me.lendev.flow.param.request;

import jakarta.validation.constraints.*;

import java.util.List;

import lombok.Data;

/**
 * 参数排序请求：按给定 id 顺序重排同组内参数记录的 sort 值。
 */
@Data
public class ParamSortRequest {

    /**
     * 排序后的参数记录 id 列表，列表顺序即目标展示顺序。
     */
    @NotNull
    @Size(max = 5000)
    private List<@NotNull @Positive Long> ids;
}

