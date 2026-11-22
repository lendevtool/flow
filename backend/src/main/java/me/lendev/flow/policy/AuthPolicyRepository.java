package me.lendev.flow.policy;

import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.stereotype.Repository;

/**
 * 在认证策略保存事务中读取管理员状态和初始化正式密码的使用时间。
 */
@Repository
@RequiredArgsConstructor
class AuthPolicyRepository {

    private final UserMapper users;

    long countEnabledDomainAdmins() {
        return users.selectCountByQuery(QueryWrapper.create().from(USER)
                .join(USER_ROLE).on(USER_ROLE.USER_ID.eq(USER.ID))
                .join(ROLE).on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
                .where(USER.ENABLE.eq(true)).and(USER.DOMAIN_ACCOUNT.isNotNull())
                .and(ROLE.ENABLE.eq(true)).and(ROLE.CODE.eq(RoleType.SUPER_ADMIN)));
    }

    void initializePasswordChangedAt(LocalDateTime timestamp) {
        UpdateChain.of(users).set(USER.PASSWORD_CHANGED_AT, timestamp)
                .where(USER.PASSWORD_CHANGED_AT.isNull()).and(USER.TEMPORARY_PASSWORD_EXPIRES_AT.isNull()).update();
    }
}
