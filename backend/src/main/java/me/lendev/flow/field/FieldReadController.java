package me.lendev.flow.field;

import static me.lendev.flow.common.auth.PermissionCode.FIELD_MGT;
import static me.lendev.flow.field.FieldModels.*;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字段定义和字典的只读查询，员工与已获接口授权的应用共用业务查询逻辑。
 */
@RestController
@RequestMapping(FieldController.ROOT_PATH)
@EndpointGrantRead("field-definition")
@RequiredArgsConstructor
@Tag(name = "字段管理")
public class FieldReadController {

    private final FieldService service;

    /**
     * 分页查询配置的字段定义。
     *
     * @param query 字段名称、值类型、启停状态及分页条件
     * @return 匹配的字段配置和总数
     */
    @GetMapping
    public R<Page<Definition>> page(@Valid Query query) {
        permitRead();
        return R.ok(service.page(query));
    }

    /**
     * 读取一个字段的完整定义。
     *
     * @param id 正整数形式的字段 ID
     * @return 字段配置、默认值和校验规则
     */
    @GetMapping("/{id}")
    public R<Definition> get(@PathVariable @Positive long id) {
        permitRead();
        return R.ok(service.get(id));
    }

    /**
     * 读取配置字段时可选择的规则类型。
     *
     * @return 支持的数据类型、格式及字符分类选项
     */
    @GetMapping("/metadata")
    public R<Metadata> metadata() {
        permitRead();
        return R.ok(service.metadata());
    }

    /**
     * 按关键词查询可配置字典，并保留当前选中的字典。
     *
     * @param keyword    可选的匹配文本，最多 100 字符
     * @param selectedId 可选的当前选中字典 ID
     * @return 匹配条件及当前选择对应的字典
     */
    @GetMapping("/dictionaries")
    public R<List<Dictionary>> dictionaries(@RequestParam(required = false) @Size(max = 100) String keyword,
                                            @RequestParam(required = false) @Positive Long selectedId) {
        permitRead();
        return R.ok(service.dictionaries(keyword, selectedId));
    }

    /**
     * 按字典 ID 读取用于配置字段的选项。
     *
     * @param id 正整数形式的参数字典 ID
     * @return 字典选项及其启停状态
     */
    @GetMapping("/dictionaries/{id}/options")
    public R<List<Option>> options(@PathVariable @Positive long id) {
        permitRead();
        return R.ok(service.options(id));
    }

    private static void permitRead() {
        if (ManagedReadInvocation.callerForModule(FieldReadController.class) == null) {
            FieldController.permit(FIELD_MGT);
        }
    }
}
