package me.lendev.flow.field;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.field.OpenFieldModels.Definition;
import me.lendev.flow.field.OpenFieldModels.Dictionary;
import me.lendev.flow.field.OpenFieldModels.FieldPage;
import me.lendev.flow.field.OpenFieldModels.Metadata;
import me.lendev.flow.field.OpenFieldModels.Option;
import me.lendev.flow.field.OpenFieldModels.Query;
import me.lendev.flow.openapi.OpenApiEndpoint;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供字段管理专用开放 API v1 的五个只读入口。
 *
 * <p>调用方使用应用 Bearer Token，应用鉴权、接口发布状态和 Scope 授权由开放 API
 * 拦截器统一校验。原员工接口及配置导入接口继续使用各自的路径和授权。
 *
 * <p>成功响应直接返回公开 DTO，不包含员工接口的 {@code R} 包装；字段定义仅返回
 * {@code id} 和业务 {@code config}，不返回创建人、创建时间、更新人及更新时间。
 * 业务失败使用开放 API 的 HTTP 状态及 {@code code}、{@code message}、{@code requestId} 错误体。
 */
@RestController
@RequestMapping("/open/v1/field-definition")
@RequiredArgsConstructor
@Tag(name = "字段管理开放接口")
@SecurityRequirement(name = "appBearer")
public class OpenFieldController {
    private final OpenFieldService fields;

    /**
     * 按关键词、字段类型和启用状态分页查询字段的公开业务配置。
     *
     * <p>按字段 ID 倒序返回；不指定启用状态时同时查询启用、停用的字段。
     * 页码、页大小省略时分别使用 1、20，参数校验失败返回 {@code 400 INVALID_ARGUMENT}。
     *
     * @param query 查询条件；各筛选项可省略，关键词最多 100 字符，页码须为正整数，页大小为 1～100
     * @return 当前页字段列表和符合筛选条件的总数；无匹配记录或页码超出范围时列表为空，
     * 总数仍表示全部匹配记录数；各字段不含人员及时间审计信息
     */
    @GetMapping
    @Operation(summary = "字段定义分页查询（专用）")
    @OpenApiEndpoint(value = "field-definition.public.page.read", dataScopeType = "NONE")
    public FieldPage page(@Valid @ParameterObject Query query) {
        return fields.page(query);
    }

    /**
     * 根据字段 ID 查询公开业务配置，启用和停用的字段均可查询。
     *
     * <p>字段 ID 格式错误或不是正整数时返回 {@code 400 INVALID_ARGUMENT}。
     *
     * @param id 字段定义 ID，必须为正整数，作为路径参数传入
     * @return 字段标识和完整业务配置，不包含创建、更新人员及时间
     * @throws me.lendev.flow.openapi.OpenApiException 字段不存在时返回 {@code 404 RESOURCE_NOT_FOUND}
     */
    @GetMapping("/{id}")
    @Operation(summary = "字段定义详情（专用）")
    @OpenApiEndpoint(value = "field-definition.public.detail.read", dataScopeType = "NONE")
    public Definition get(@PathVariable @Positive long id) {
        return fields.get(id);
    }

    /**
     * 查询字段支持的数据类型、文本格式和字符类别元数据，无需业务查询参数。
     *
     * <p>只返回字段模块支持的元数据编码；选项的启用状态同时取决于其参数分类和明细。
     *
     * @return 分别以 {@code types}、{@code formats}、{@code characters} 分组的选项列表；
     * 每项包含编码、中英文名称和启用状态，无对应配置的分组返回空列表
     */
    @GetMapping("/metadata")
    @Operation(summary = "字段定义元数据（专用）")
    @OpenApiEndpoint(value = "field-definition.public.metadata.read", dataScopeType = "NONE")
    public Metadata metadata() {
        return fields.metadata();
    }

    /**
     * 查询字段可引用的参数字典分类，最多返回 50 项。
     *
     * <p>通常返回符合关键词的启用字典；存在的已选字典始终保留并排在首位，
     * 即使它已停用或不符合关键词。其余结果按业务排序值、分类 ID 升序排列。
     * 已选 ID 不存在时不额外补入记录；参数校验失败返回 {@code 400 INVALID_ARGUMENT}。
     *
     * @param keyword    分类编码、中文名或英文名的模糊查询关键词，最多 100 字符；省略或空白时不按关键词筛选
     * @param selectedId 当前已选的字典分类 ID，必须为正整数；省略时仅查询符合条件的启用字典
     * @return 字典分类列表，每项包含 ID、编码、中英文名称和启用状态；无可用结果时返回空列表
     */
    @GetMapping("/dictionaries")
    @Operation(summary = "字段字典列表（专用）")
    @OpenApiEndpoint(value = "field-definition.public.dictionaries.read", dataScopeType = "NONE")
    public List<Dictionary> dictionaries(@RequestParam(required = false) @Size(max = 100) String keyword,
                                         @RequestParam(required = false) @Positive Long selectedId) {
        return fields.dictionaries(keyword, selectedId);
    }

    /**
     * 根据参数字典分类 ID 查询其全部选项，保留分类和选项原有的启用、停用语义。
     *
     * <p>结果按业务排序值、选项 ID 升序排列，不额外过滤停用选项。
     * ID 格式错误或不是正整数时返回 {@code 400 INVALID_ARGUMENT}。
     *
     * @param id 参数字典分类 ID，必须为正整数，作为路径参数传入；不是字段定义 ID
     * @return 字典选项列表，每项包含编码、中英文名称和启用状态；字典存在但没有明细时返回空列表
     * @throws me.lendev.flow.openapi.OpenApiException 字典分类不存在时返回 {@code 404 RESOURCE_NOT_FOUND}
     */
    @GetMapping("/dictionaries/{id}/options")
    @Operation(summary = "字段字典选项（专用）")
    @OpenApiEndpoint(value = "field-definition.public.dictionary-options.read", dataScopeType = "NONE")
    public List<Option> options(@PathVariable @Positive long id) {
        return fields.options(id);
    }
}
