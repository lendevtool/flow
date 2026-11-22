package me.lendev.flow.field;

import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.field.OpenFieldModels.Config;
import me.lendev.flow.field.OpenFieldModels.DataType;
import me.lendev.flow.field.OpenFieldModels.Definition;
import me.lendev.flow.field.OpenFieldModels.Dictionary;
import me.lendev.flow.field.OpenFieldModels.FieldPage;
import me.lendev.flow.field.OpenFieldModels.Metadata;
import me.lendev.flow.field.OpenFieldModels.Option;
import me.lendev.flow.field.OpenFieldModels.Query;
import me.lendev.flow.field.OpenFieldModels.TextFormat;
import me.lendev.flow.openapi.OpenApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复用字段业务查询，转换独立公开 DTO，并提供稳定的开放接口错误语义。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OpenFieldService {
    private final FieldService fields;

    /**
     * 按字段名称、编码、类型和启用状态查询，默认每页 20 条。
     */
    public FieldPage page(Query query) {
        var filter = new FieldModels.Query(query.keyword(),
                query.dataType() == null ? null : FieldModels.DataType.valueOf(query.dataType().name()),
                query.enable(), query.pageNo(), query.pageSize());
        var page = read(() -> fields.page(filter));
        return new FieldPage(page.getPageData().stream().map(OpenFieldService::definition).toList(), page.getTotal());
    }

    /**
     * 查询单个字段的业务配置。
     *
     * @param id 字段定义 ID
     * @return 不含人员和时间审计信息的定义
     * @throws OpenApiException 字段不存在时返回 404 RESOURCE_NOT_FOUND
     */
    public Definition get(long id) {
        return definition(read(() -> fields.get(id)));
    }

    /**
     * 返回字段支持的数据类型、文本格式和字符类别选项。
     */
    public Metadata metadata() {
        var metadata = read(fields::metadata);
        return new Metadata(options(metadata.types()), options(metadata.formats()), options(metadata.characters()));
    }

    /**
     * 返回业务规则允许选择的字典，并保留 selectedId 对应的已选字典。
     */
    public List<Dictionary> dictionaries(String keyword, Long selectedId) {
        return read(() -> fields.dictionaries(keyword, selectedId)).stream()
                .map(dictionary -> new Dictionary(dictionary.id(), dictionary.code(), dictionary.name(),
                        dictionary.nameEn(), dictionary.enable())).toList();
    }

    /**
     * 查询指定字典的全部业务选项，启用状态保持来源查询语义。
     *
     * @param id 字典分类 ID
     * @return 字典选项
     * @throws OpenApiException 字典不存在时返回 404 RESOURCE_NOT_FOUND
     */
    public List<Option> options(long id) {
        return options(read(() -> fields.options(id)));
    }

    private static List<Option> options(List<FieldModels.Option> options) {
        return options.stream().map(option -> new Option(option.code(), option.name(), option.nameEn(), option.enable()))
                .toList();
    }

    private static Definition definition(FieldModels.Definition definition) {
        var c = definition.config();
        var config = new Config(c.code(), c.name(), c.nameEn(), DataType.valueOf(c.dataType().name()), c.required(),
                c.defaultValue() == null ? null : c.defaultValue().deepCopy(), c.textMinLength(), c.textMaxLength(),
                c.textFormat() == null ? null : TextFormat.valueOf(c.textFormat().name()), c.textPattern(),
                c.textCharLimitEnabled(), c.textAllowChinese(), c.textAllowEnglish(), c.textAllowDigits(),
                c.textAllowSpace(), c.textAllowSeparators(), c.textAllowChinesePunctuation(),
                c.textAllowEnglishPunctuation(), c.numberPositiveOnly(), c.integerMin(), c.integerMax(),
                c.decimalMin(), c.decimalMax(), c.decimalPrecision(), c.decimalScale(), c.dateMin(), c.dateMax(),
                c.dateTimeMin(), c.dateTimeMax(), c.paramGroupId(), c.enumMinItems(), c.enumMaxItems(), c.enable(), c.remark());
        return new Definition(definition.id(), config);
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (BadRequestException e) {
            var message = e.getApiMessage();
            if (message != null && ("error.field.missing".equals(message.key())
                    || "error.field.dictionary".equals(message.key()))) {
                throw new OpenApiException(404, "RESOURCE_NOT_FOUND", "Field or dictionary was not found");
            }
            throw new OpenApiException(400, "INVALID_ARGUMENT", "Invalid field query");
        }
    }
}
