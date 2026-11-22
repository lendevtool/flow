package me.lendev.flow.pms.service.impl;

import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.mapper.UserRoleMapper;
import me.lendev.flow.pms.service.UserRoleService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户与角色关系服务实现类
 *
 * @author lendev
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole>
        implements UserRoleService {

}
