package me.lendev.flow.institution;

import static me.lendev.flow.institution.InstitutionAccess.permitRead;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.institution.InstitutionModels.Institution;
import me.lendev.flow.institution.InstitutionModels.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供机构档案的只读查询，员工和已获接口授权的应用共用业务查询。
 */
@RestController
@RequestMapping("/institutions")
@RequiredArgsConstructor
public class InstitutionReadController {
    private final InstitutionService institutions;

    /**
     * 按调用方授权分页查询机构档案。
     *
     * @param query 机构关键词、启停状态及分页条件
     * @return 命中的机构列表和总数
     */
    @GetMapping
    public R<Page<Institution>> list(@Valid Query query) {
        permitRead();
        return R.ok(institutions.page(query));
    }

    /**
     * 在调用方权限范围内查询指定机构。
     *
     * @param id 机构档案 ID
     * @return 机构档案详情，包括已配置的档案字段
     */
    @GetMapping("/{id}")
    public R<Institution> detail(@PathVariable long id) {
        permitRead();
        return R.ok(institutions.detail(id));
    }
}
