package me.lendev.flow.pms.department;

import static me.lendev.flow.common.auth.PermissionCode.DEPARTMENT_VIEW;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.pms.department.DepartmentModels.Department;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询组织架构树与部门负责人工作目录。
 */
@RestController
@RequestMapping(DepartmentController.ROOT)
@RequiredArgsConstructor
@Tag(name = "组织架构")
@EndpointGrantRead("department")
public class DepartmentReadController {

    private final DepartmentService service;

    /**
     * 获取部门树。员工访问使用组织架构查看权限，开放应用访问由接口授权校验。
     *
     * @return 部门层级、状态及负责人工作目录
     */
    @GetMapping
    public R<List<Department>> list() {
        if (ManagedReadInvocation.callerForModule(DepartmentReadController.class) == null) {
            DepartmentController.permit(DEPARTMENT_VIEW);
        }
        return R.ok(service.list());
    }
}
