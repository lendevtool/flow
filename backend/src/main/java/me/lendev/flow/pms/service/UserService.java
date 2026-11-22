package me.lendev.flow.pms.service;

import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.domain.dto.PersonDetailsDto;
import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.dto.UserPageDto;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.request.AddUserRolesRequest;
import me.lendev.flow.pms.domain.request.ChangePasswordRequest;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.domain.dto.TemporaryPasswordDto;
import me.lendev.flow.pms.domain.request.UpdateProfileRequest;
import me.lendev.flow.pms.domain.request.UpdateUserRequest;
import me.lendev.flow.pms.domain.request.UserPageRequest;
import com.mybatisflex.core.service.IService;

/**
 * 用户服务
 *
 * @author lendev
 */
public interface UserService extends IService<User> {

    /**
     * 用户登录：依次执行限流、验证码消费、凭据校验；命中 MFA 策略时不签发 token，仅返回挑战。
     *
     * @param request 请求
     * @return 返回token；需要 MFA 时仅携带 mfaChallenge 字段
     */
    LoginTokenDto login(LoginRequest request);

    /**
     * 完成首因素凭据签发的 MFA 挑战，并在验证后签发会话。
     *
     * @param ticket 登录时签发的一次性挑战票据
     * @param code   MFA 验证码或恢复码
     * @return 登录token，可能附带新生成的恢复码
     */
    LoginTokenDto completeMfaLogin(String ticket, String code);

    /**
     * 用户信息
     *
     * @param userId   用户id
     * @param roleCode 用户角色编码
     * @return 用户信息
     */
    UserDetailDto detail(Long userId, String roleCode);

    /**
     * 按账号查询姓名、邮件、部门及角色，用于人员详情。
     *
     * @param userId 用户id
     * @return 人员名片信息
     */
    PersonDetailsDto personDetails(Long userId);

    /**
     * 切换角色
     *
     * @param userId   用户id
     * @param roleCode 角色编码
     * @return 切换后重新获取token
     */
    LoginTokenDto switchRole(Long userId, String roleCode);

    /**
     * 注册用户，同一事务内写入用户、资料、角色与部门归属。
     *
     * @param request 请求
     * @return 未指定密码且非引导模式时返回系统生成的临时密码及过期时间，否则返回 null
     */
    TemporaryPasswordDto register(RegisterUserRequest request);

    /**
     * 刷新token：校验用户仍启用且当前角色仍有效后，返回当前 token。
     *
     * @return 新的token
     */
    LoginTokenDto refreshToken();

    /**
     * 修改当前用户密码：轮换登录凭据并强制注销该用户全部会话。
     *
     * @param request 请求
     */
    void changePassword(ChangePasswordRequest request);

    /**
     * 分页查询
     *
     * @param request 请求
     * @return ret
     */
    Page<UserPageDto> queryPage(UserPageRequest request);

    /**
     * 根据id删除用户，不能删除自己
     *
     * @param id 用户id
     */
    void removeUser(Long id);

    /**
     * 重置密码：生成临时密码、轮换登录凭据并强制注销全部会话。
     *
     * @param userId 用户id
     * @return 一次性展示的临时密码
     */
    TemporaryPasswordDto resetPassword(Long userId);

    /**
     * 整体替换用户的角色分配：先清空原有关联，再写入去重后的新集合。
     *
     * @param userId  用户id
     * @param request 包含角色id
     */
    void addRoles(Long userId, AddUserRolesRequest request);

    /**
     * 更新用户信息
     *
     * @param id      用户id
     * @param request 用户信息
     */
    void updateProfile(Long id, UpdateProfileRequest request);

    /**
     * 更新用户：仅更新请求携带的字段，可整体替换角色与部门归属；
     * 域账号变更或停用账号时轮换登录凭据并强制注销会话。
     *
     * @param id      用户id
     * @param request 用户信息
     */
    void updateById(Long id, UpdateUserRequest request);
}
