package me.lendev.flow.pms.service.impl;

import me.lendev.flow.pms.domain.entity.RolePermission;
import me.lendev.flow.pms.mapper.RolePermissionMapper;
import me.lendev.flow.pms.service.RolePermissionService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 角色与权限关系服务实现类
 *
 * @author lendev
 */
@Service
public class RolePermissionServiceImpl extends ServiceImpl<RolePermissionMapper, RolePermission>
        implements RolePermissionService {


}
