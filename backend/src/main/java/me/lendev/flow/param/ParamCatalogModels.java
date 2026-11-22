package me.lendev.flow.param;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;
import me.lendev.flow.param.request.ParamPageRequest;

/**
 * 参数目录的分页请求及分类、明细的公开元数据。
 */
public final class ParamCatalogModels {
    private ParamCatalogModels() {
    }

    /**
     * 支持按编码、中英文名称查询，未指定分页时使用参数管理的默认值。
     */
    public record Query(@Size(max = 100) String keyword, @Positive Integer pageNo,
                        @Min(1) @Max(500) Integer pageSize) {
        ParamPageRequest toPageRequest() {
            var request = new ParamPageRequest();
            request.setKeyword(keyword);
            request.setPageNo(pageNo);
            request.setPageSize(pageSize);
            return request;
        }
    }

    /**
     * 参数分类的标识、显示名称、状态及顺序。
     */
    public record Group(long id, String code, String name, String nameEn, boolean enable, int sort) {
        static Group from(ParamGroup group) {
            return new Group(group.getId(), group.getCode(), group.getName(), group.getNameEn(),
                    group.getEnable(), group.getSort());
        }
    }

    /**
     * 参数明细的标识、所属分类、显示名称、状态及顺序。
     */
    public record Item(long id, long groupId, String code, String name, String nameEn, boolean enable, int sort) {
        static Item from(ParamItem item) {
            return new Item(item.getId(), item.getGroupId(), item.getCode(), item.getName(), item.getNameEn(),
                    item.getEnable(), item.getSort());
        }
    }
}
