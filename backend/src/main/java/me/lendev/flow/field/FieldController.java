package me.lendev.flow.field;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.field.FieldModels.*;

import java.util.Locale;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.R;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.*;

/**
 * 字段定义的维护与试测接口。
 */
@RestController
@RequestMapping(FieldController.ROOT_PATH)
@RequiredArgsConstructor
@Tag(name = "字段管理")
public class FieldController {

    static final String ROOT_PATH = "/field-definition";

    private final FieldService service;
    private final MessageSource messages;

    /**
     * 使用当前保存的配置试测输入值，并按请求语言返回第一条失败原因。
     */
    @PostMapping("/{id}/validate")
    public R<ValidationResult> validate(@PathVariable @Positive long id, @RequestBody ValidationRequest request,
                                        Locale locale) {
        permit(FIELD_MGT);
        var failure = service.validate(id, request.value());
        if (failure == null) {
            return R.ok(new ValidationResult(true, null, null));
        }
        return R.ok(new ValidationResult(false, failure.key(),
                messages.getMessage(failure.key(), failure.arguments(), locale)));
    }

    /**
     * 创建字段定义及其配置规则。
     *
     * @param config 字段编码、类型、默认值与校验配置
     * @return 新字段 ID
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody Config config) {
        permit(FIELD_CREATE);
        return R.ok(service.create(config));
    }

    /**
     * 以请求中的配置更新指定字段。
     *
     * @param id     待更新字段的正整数 ID
     * @param config 更新后的配置，须与关联字典定义兼容
     * @return 空业务数据的成功响应
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable @Positive Long id, @Valid @RequestBody Config config) {
        permit(FIELD_EDIT);
        service.update(id, config);
        return R.ok();
    }

    /**
     * 删除指定的字段定义。
     *
     * @param id 待删除字段的正整数 ID
     * @return 空业务数据的成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable @Positive Long id) {
        permit(FIELD_DELETE);
        service.delete(id);
        return R.ok();
    }

    static void permit(String operation) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(FIELD_MGT);
            if (!FIELD_MGT.equals(operation)) {
                StpUtil.checkPermission(operation);
            }
        }
    }
}
