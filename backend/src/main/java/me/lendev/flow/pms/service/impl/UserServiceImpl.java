package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.common.validation.EnglishName.normalizeEnglishName;
import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;

import com.mybatisflex.core.query.QueryWrapper;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.NotLoginException;
import me.lendev.flow.common.auth.SaTokenConfigure;
import me.lendev.flow.common.auth.AccountSession;
import me.lendev.flow.common.auth.LoginMethod;
import me.lendev.flow.common.auth.mfa.MfaService;
import me.lendev.flow.common.auth.domain.DomainAccount;
import me.lendev.flow.common.auth.domain.DomainAuthenticator;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.AuditPostCommit;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.common.validation.PasswordPolicy;
import me.lendev.flow.pms.service.support.AssignmentValidator;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.convert.ProfileConverter;
import me.lendev.flow.pms.department.DepartmentRepository;
import me.lendev.flow.pms.department.DepartmentService;
import me.lendev.flow.pms.domain.dto.PersonDetailsDto;
import me.lendev.flow.pms.convert.RoleConverter;
import me.lendev.flow.pms.convert.UserConverter;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.domain.dto.ProfileDto;
import me.lendev.flow.pms.domain.dto.RoleDto;
import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.dto.UserPageDto;
import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.domain.request.AddUserRolesRequest;
import me.lendev.flow.pms.domain.request.ChangePasswordRequest;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.domain.request.RegisterUserProfileRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.domain.dto.TemporaryPasswordDto;
import me.lendev.flow.pms.domain.request.UpdateProfileRequest;
import me.lendev.flow.pms.domain.request.UpdateUserRequest;
import me.lendev.flow.pms.domain.request.UserPageRequest;
import me.lendev.flow.pms.mapper.UserMapper;
import me.lendev.flow.pms.service.CaptchaService;
import me.lendev.flow.pms.service.ProfileService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserRoleService;
import me.lendev.flow.pms.service.UserService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import me.lendev.flow.common.auth.PasswordHashes;
import me.lendev.flow.common.auth.SessionRules;
import me.lendev.flow.common.auth.rate.AuthRateLimiter;
import me.lendev.flow.policy.AuthPolicy;
import me.lendev.flow.policy.AuthPolicyAccess;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.transaction.support.TransactionTemplate;
import com.mybatisflex.spring.service.impl.ServiceImpl;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理账号凭据、会话、资料与角色关联，并协调用户的部门归属。
 *
 * @author lendev
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Pattern MOBILE = Pattern.compile("\\+?\\d{7,15}");

    private final AuthPolicyAccess policies;
    private final MfaService mfa;
    private final DomainAuthenticator domain;
    private final AuthRateLimiter rateLimiter;
    private final SessionRules sessions;
    private final Clock clock;
    private final TransactionTemplate transaction;

    private final RoleService roleService;

    private final AuditPostCommit postCommit;

    private final AssignmentValidator assignmentValidator;

    private final ProfileService profileService;
    private final DepartmentRepository departments;
    private final DepartmentService departmentService;

    private final UserRoleService userRoleService;

    private final CaptchaService captchaService;

    private final UserConverter userConverter;

    private final ProfileConverter profileConverter;

    private final RoleConverter roleConverter;

    @Override
    @Audited(AuditOperation.LOGIN)
    public LoginTokenDto login(LoginRequest request) {
        var policy = policies.current();
        LoginMethod method = request.getLoginMethod();
        boolean domainLogin = method == LoginMethod.DOMAIN;
        String account = domainLogin ? DomainAccount.normalize(request.getUsername()) : request.getUsername();
        rateLimiter.checkLogin(request.getRemoteAddress(), account == null ? request.getUsername() : account);
        // 先消费验证码，再查询账号和校验密码。
        if (policy.captcha().required() && (CharSequenceUtil.isBlank(request.getCaptchaKey())
                || !captchaService.verify(request.getCaptchaKey(), request.getCaptcha()))) {
            throw new BizException(BizResponseCode.ERR_10003);
        }
        if (domainLogin ? !policy.login().domainEnabled() : !policy.login().localEnabled()) {
            throw BizException.localized(BizResponseCode.ERR_10002, "error.login.method-disabled");
        }
        if (account == null) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        User user = authenticate(request, domainLogin, account);
        if (!domainLogin && user.getTemporaryPasswordExpiresAt() != null
                && !clock.instant().isBefore(user.getTemporaryPasswordExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        var challenge = mfa.beginLogin(user, method, policy);
        if (challenge != null) {
            var result = new LoginTokenDto();
            result.setMfaChallenge(challenge);
            return result;
        }
        return issueLogin(user, policy, method, null);
    }

    private User authenticate(LoginRequest request, boolean domainLogin, String account) {
        User user = queryChain().and((domainLogin ? USER.DOMAIN_ACCOUNT : USER.USERNAME).eq(account, true)).one();
        boolean enabled = user != null && Boolean.TRUE.equals(user.getEnable());
        boolean passwordMatches;
        if (domainLogin) {
            passwordMatches = enabled && domain.authenticate(account, request.getPassword());
        } else {
            passwordMatches = PasswordHashes.matches(request.getPassword(), user == null ? null : user.getPassword());
        }
        if (!enabled || !passwordMatches) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        return user;
    }

    @Override
    @Audited(AuditOperation.MFA_LOGIN)
    public LoginTokenDto completeMfaLogin(String ticket, String code) {
        var verified = mfa.verifyLogin(ticket, code);
        var result = issueLogin(verified.user(), policies.current(), verified.method(), verified.proof());
        result.setRecoveryCodes(verified.recoveryCodes());
        return result;
    }

    private LoginTokenDto issueLogin(User user, AuthPolicy policy, LoginMethod method, MfaService.Proof mfaProof) {
        List<Role> roles = roleService.findRolesByUserId(user.getId()).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable())).toList();
        String role = roles.isEmpty() ? "" : roles.getFirst().getCode();
        if (policy.session().allowConcurrent()) {
            return generateToken(user, role, policy, method, mfaProof);
        }
        // 单会话策略下，串行化同一账号的签发和顶替。
        return transaction.execute(status -> {
            var current = getMapper().selectOneByQuery(QueryWrapper.create().where(USER.ID.eq(user.getId())).forUpdate());
            if (current == null || !Boolean.TRUE.equals(current.getEnable())
                    || !user.getIdentityKey().equals(current.getIdentityKey())) {
                throw new BizException(BizResponseCode.ERR_10002);
            }
            return generateToken(current, role, policy, method, mfaProof);
        });
    }

    @Override
    public UserDetailDto detail(Long userId, String roleCode) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        UserDetailDto userDetailDto = userConverter.toUserDetailDto(user);
        userDetailDto.setLoginMethod(SessionRules.currentLoginMethod());
        Profile profile = profileService.findByUserId(userId);
        ProfileDto profileDto = profileConverter.toProfileDto(profile);
        List<RoleDto> roleDtoList = roleService.findRolesByUserId(userId)
                .stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable()))
                .map(roleConverter::toRoleDto)
                .toList();
        if (roleDtoList.isEmpty()) {
            throw new BizException(BizResponseCode.ERR_11005);
        }
        userDetailDto.setProfile(profileDto);
        userDetailDto.setAppearance(profileConverter.toAppearance(profile));
        userDetailDto.setRoles(roleDtoList);
        for (RoleDto roleDto : roleDtoList) {
            if (roleDto.getCode().equals(roleCode)) {
                userDetailDto.setCurrentRole(roleDto);
                break;
            }
        }
        return userDetailDto;
    }

    /**
     * 读取人员名片所需的信息，调用方先完成查看授权。
     */
    @Override
    @Transactional(readOnly = true)
    public PersonDetailsDto personDetails(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw BadRequestException.localized("error.user.missing");
        }
        Profile profile = profileService.findByUserId(userId);
        var roles = roleService.findRolesByUserId(userId).stream().map(roleConverter::toRoleDto).toList();
        var memberships = departments.memberships(List.of(userId)).getOrDefault(userId, List.of());
        return new PersonDetailsDto(user.getId(), user.getUsername(), user.getNameCn(), user.getNameEn(),
                profile == null ? null : profile.getEmail(), user.getDepartmentId(), memberships, roles);
    }

    @Override
    public LoginTokenDto switchRole(Long userId, String roleCode) {
        User user = getById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        List<Role> roles = roleService.findRolesByUserId(userId).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable())).toList();
        Role currentRole = null;
        for (Role role : roles) {
            if (roleCode.equals(role.getCode())) {
                currentRole = role;
            }
        }
        if (currentRole == null) {
            throw new BizException(BizResponseCode.ERR_11005);
        }
        StpUtil.getTokenSession().set(SaTokenConfigure.CURRENT_ROLE_KEY, currentRole.getCode());
        return currentToken();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_CREATE)
    public TemporaryPasswordDto register(RegisterUserRequest request) {
        var policy = policies.current();
        String password = request.getPassword();
        boolean temporary = password == null && !request.isBootstrap();
        if (temporary) {
            password = PasswordPolicy.generate(request.getUsername(), policy.password());
        }
        PasswordPolicy.validate(password, request.getUsername(), policy.password());
        Instant now = clock.instant();
        Instant expiresAt = temporary ? now.plusSeconds(policy.password().temporaryTtlSeconds()) : null;
        assignmentValidator.requireRoles(request.getRoleIds());
        // 对同名账号行加锁查询，防止并发注册产生重复用户名
        boolean exists = queryChain().and(USER.USERNAME.eq(request.getUsername(), true)).forUpdate().one() != null;
        if (exists) {
            throw new BizException(BizResponseCode.ERR_10001);
        }
        User user = userConverter.toUser(request);
        user.setNameCn(CharSequenceUtil.trimToNull(request.getNameCn()));
        user.setNameEn(normalizeEnglishName(request.getNameEn()));
        user.setDomainAccount(DomainAccount.validateOptional(request.getDomainAccount()));
        user.setMobile(normalizeMobile(request.getMobile()));
        user.setIdentityKey(UUID.randomUUID().toString());
        user.setPassword(PasswordHashes.encode(password));
        user.setPasswordChangedAt(temporary ? null : utc(now));
        user.setTemporaryPasswordExpiresAt(temporary ? utc(expiresAt) : null);
        user.setPasswordChangeRequired(temporary || request.isBootstrap() && policy.bootstrap().requirePasswordChange());
        this.save(user);
        var memberships = request.getMemberships();
        if (memberships != null) {
            departmentService.applyMemberships(user.getId(), memberships.departmentIds(), memberships.primaryDepartmentId(), null);
        }

        Profile profile = profileConverter.toProfile(
                Optional.ofNullable(request.getProfile()).orElse(new RegisterUserProfileRequest()));
        profile.setUserId(user.getId());
        if (CollUtil.isNotEmpty(request.getRoleIds())) {
            List<UserRole> roleList = request.getRoleIds().stream().distinct()
                    .map(roleId -> {
                        UserRole userRole = new UserRole();
                        userRole.setUserId(user.getId());
                        userRole.setRoleId(roleId);
                        return userRole;
                    }).toList();
            userRoleService.saveBatch(roleList);
        }
        profileService.save(profile);
        log.info("用户账号已写入，requestId={} userId={} temporaryPasswordIssued={} bootstrap={}",
                AuditParticipants.currentRequestId(), user.getId(), temporary, request.isBootstrap());
        return temporary ? new TemporaryPasswordDto(password, expiresAt) : null;
    }

    @Override
    public LoginTokenDto refreshToken() {
        long userId = StpUtil.getLoginIdAsLong();
        String currentRole = SaTokenConfigure.currentRole();
        User user = getById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        List<Role> roles = roleService.findRolesByUserId(userId).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable())).toList();
        if (CharSequenceUtil.isNotBlank(currentRole)
                && roles.stream().noneMatch(role -> currentRole.equals(role.getCode()))) {
            throw new BizException(BizResponseCode.ERR_11005);
        }
        return currentToken();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_PASSWORD_CHANGE)
    public void changePassword(ChangePasswordRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = getById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        AccountSession.check(user);
        if (SessionRules.currentLoginMethod() == LoginMethod.DOMAIN) {
            throw BizException.localized(BizResponseCode.ERR_11006, "error.password.domain-managed");
        }
        var policy = policies.current().password();
        rateLimiter.checkPasswordChange(userId);
        var now = clock.instant();
        if (user.getTemporaryPasswordExpiresAt() != null
                && !now.isBefore(user.getTemporaryPasswordExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw NotLoginException.newInstance(StpUtil.getLoginType(), NotLoginException.INVALID_TOKEN,
                    "临时密码已过期", StpUtil.getTokenValue());
        }
        if (!PasswordHashes.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(BizResponseCode.ERR_10004);
        }
        PasswordPolicy.validate(request.getNewPassword(), user.getUsername(), policy);
        if (policy.rejectCurrent() && PasswordHashes.matches(request.getNewPassword(), user.getPassword())) {
            throw BadRequestException.localized("validation.password.same");
        }
        // 以 identityKey 作为乐观锁条件更新：若校验旧密码后凭据已被其他请求轮换，
        // 此更新影响 0 行，说明当前登录态已失效，强制重新登录而不是覆盖他人修改。
        boolean changed = updateChain().set(USER.IDENTITY_KEY, UUID.randomUUID().toString())
                .set(USER.PASSWORD, PasswordHashes.encode(request.getNewPassword()))
                .set(USER.PASSWORD_CHANGED_AT, utc(now))
                .set(USER.TEMPORARY_PASSWORD_EXPIRES_AT, (Object) null)
                .set(USER.PASSWORD_CHANGE_REQUIRED, false)
                .and(USER.ID.eq(userId, true))
                .and(USER.IDENTITY_KEY.eq(user.getIdentityKey(), true))
                .and(USER.ENABLE.eq(true, true)).update();
        if (!changed) {
            throw NotLoginException.newInstance(StpUtil.getLoginType(), NotLoginException.INVALID_TOKEN,
                    "登录凭证已失效，请重新登录", StpUtil.getTokenValue());
        }
        revokeSessions(userId);
        log.info("用户密码已更新并安排注销现有会话，requestId={} userId={}",
                AuditParticipants.currentRequestId(), userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserPageDto> queryPage(UserPageRequest request) {
        com.mybatisflex.core.paginate.Page<UserPageDto> qp = request.toPage();
        com.mybatisflex.core.paginate.Page<UserPageDto> ret = getMapper().pageDetail(qp, request);
        List<Long> userIds = ret.getRecords().stream().map(UserPageDto::getId).toList();
        if (!userIds.isEmpty()) {
            List<UserRole> relations = userRoleService.queryChain()
                    .and(USER_ROLE.USER_ID.in(userIds)).list();
            List<Long> roleIds = relations.stream().map(UserRole::getRoleId).distinct().toList();
            Map<Long, Role> rolesById = roleIds.isEmpty() ? Map.of()
                    : roleService.listByIds(roleIds).stream()
                    .collect(Collectors.toMap(Role::getId, Function.identity()));
            Map<Long, List<RoleDto>> rolesByUser = relations.stream()
                    .filter(relation -> rolesById.containsKey(relation.getRoleId()))
                    .collect(Collectors.groupingBy(UserRole::getUserId,
                            Collectors.mapping(relation -> roleConverter.toRoleDto(rolesById.get(relation.getRoleId())),
                                    Collectors.toList())));
            var memberships = departments.memberships(userIds);
            ret.getRecords().forEach(dto -> {
                dto.setRoles(rolesByUser.getOrDefault(dto.getId(), List.of()));
                dto.setDepartments(memberships.getOrDefault(dto.getId(), List.of()));
            });
        }
        return Page.convert(ret);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_DELETE)
    public void removeUser(Long id) {
        if (id == 1) {
            throw BizException.localized(BizResponseCode.ERR_11006, "error.user.delete.root");
        }
        profileService.remove(QueryWrapper.create().and(PROFILE.USER_ID.eq(id, true)));
        userRoleService.remove(QueryWrapper.create().and(USER_ROLE.USER_ID.eq(id, true)));
        departments.clearMemberships(id);
        departments.clearLeadership(id);
        removeById(id);
        revokeSessions(id);
        log.info("用户及其角色和部门关联已删除，requestId={} userId={}",
                AuditParticipants.currentRequestId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_PASSWORD_RESET)
    public TemporaryPasswordDto resetPassword(Long userId) {
        User user = getMapper().selectOneByQuery(QueryWrapper.create().where(USER.ID.eq(userId)).forUpdate());
        if (user == null) {
            throw BadRequestException.localized("error.assignment.user.missing");
        }
        var policy = policies.current().password();
        String password = PasswordPolicy.generate(user.getUsername(), policy);
        Instant expiresAt = clock.instant().plusSeconds(policy.temporaryTtlSeconds());
        updateChain().and(USER.ID.eq(userId, true))
                .set(USER.IDENTITY_KEY, UUID.randomUUID().toString())
                .set(USER.PASSWORD, PasswordHashes.encode(password))
                .set(USER.PASSWORD_CHANGED_AT, (Object) null)
                .set(USER.TEMPORARY_PASSWORD_EXPIRES_AT, utc(expiresAt))
                .set(USER.PASSWORD_CHANGE_REQUIRED, true)
                .update();
        revokeSessions(userId);
        log.info("用户临时密码已重置并安排注销现有会话，requestId={} userId={} expiresAt={}",
                AuditParticipants.currentRequestId(), userId, expiresAt);
        return new TemporaryPasswordDto(password, expiresAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_ROLE_REPLACE)
    public void addRoles(Long userId, AddUserRolesRequest request) {
        replaceRoles(userId, request.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_PROFILE_UPDATE)
    public void updateProfile(Long id, UpdateProfileRequest request) {
        Profile profile = profileConverter.toProfile(request);
        Profile existing = profileService.queryChain().and(PROFILE.USER_ID.eq(id)).forUpdate().one();
        if (existing == null) {
            throw BadRequestException.localized("error.profile.missing");
        }
        profile.setId(existing.getId());
        profileService.updateById(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.USER_UPDATE)
    public void updateById(Long id, UpdateUserRequest request) {
        User user = getMapper().selectOneByQuery(QueryWrapper.create().where(USER.ID.eq(id)).forUpdate());
        if (user == null) {
            throw BadRequestException.localized("error.user.missing");
        }
        var memberships = request.getMemberships();
        if (memberships != null) {
            departmentService.applyMemberships(id, memberships.departmentIds(), memberships.primaryDepartmentId(),
                    memberships.expectedVersion());
        }
        String domainAccount = request.getDomainAccount() == null ? user.getDomainAccount()
                : DomainAccount.validateOptional(request.getDomainAccount());
        boolean domainChanged = !Objects.equals(domainAccount, user.getDomainAccount());
        if (request.getRoleIds() != null) {
            replaceRoles(id, request.getRoleIds());
        }
        Boolean enable = request.getEnable();
        if (Boolean.FALSE.equals(enable)) {
            departments.clearLeadership(id);
        }
        boolean revoke = domainChanged || Boolean.FALSE.equals(enable);
        var update = updateChain().where(USER.ID.eq(id));
        if (request.getNameCn() != null) {
            update.set(USER.NAME_CN, CharSequenceUtil.trimToNull(request.getNameCn()), true);
        }
        if (request.getNameEn() != null) {
            update.set(USER.NAME_EN, normalizeEnglishName(request.getNameEn()), true);
        }
        if (domainChanged) {
            update.set(USER.DOMAIN_ACCOUNT, domainAccount, true);
        }
        if (request.getMobile() != null) {
            update.set(USER.MOBILE, normalizeMobile(request.getMobile()), true);
        }
        if (enable != null) {
            update.set(USER.ENABLE, enable);
        }
        if (revoke) {
            update.set(USER.IDENTITY_KEY, UUID.randomUUID().toString());
        }
        if (request.getNameCn() != null || request.getNameEn() != null || request.getMobile() != null
                || enable != null || domainChanged) {
            update.update();
        }
        if (revoke) {
            revokeSessions(id);
        }
        log.info("用户更新处理结束，requestId={} userId={} enableRequested={} domainChanged={} membershipsProvided={} rolesProvided={} sessionRevocationScheduled={}",
                AuditParticipants.currentRequestId(), id, enable, domainChanged, memberships != null,
                request.getRoleIds() != null, revoke);
    }

    private static String normalizeMobile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String number = value.strip();
        if (!MOBILE.matcher(number).matches()) {
            throw BadRequestException.localized("error.user.mobile.invalid");
        }
        return number;
    }

    /**
     * 强制注销用户的全部会话。 通过 {@code postCommit} 延迟到事务提交后执行：若事务回滚，用户会话不应被误清除。
     */
    private void revokeSessions(Long userId) {
        postCommit.run(AuditOperation.USER_SESSION_REVOKE,
                List.of(new AuditObject("USER", userId.toString(), null, userId.toString(), null, null)),
                () -> {
                    StpUtil.logout(userId);
                    return null;
                });
    }

    /**
     * 整体替换用户的角色分配；须运行在两个公开入口（addRoles/updateById）已开启的事务内。
     */
    private void replaceRoles(Long userId, List<Long> roleIds) {
        assignmentValidator.requireUsers(List.of(userId));
        assignmentValidator.requireRoles(roleIds);
        if (roleIds == null) {
            throw BadRequestException.localized("error.assignment.roles.empty");
        }
        userRoleService.remove(QueryWrapper.create().and(USER_ROLE.USER_ID.eq(userId, true)));
        List<UserRole> list = roleIds.stream().distinct()
                .map(roleId -> {
                    UserRole userRole = new UserRole();
                    userRole.setUserId(userId);
                    userRole.setRoleId(roleId);
                    return userRole;
                }).toList();
        if (!list.isEmpty()) {
            userRoleService.saveBatch(list);
        }
    }


    private LoginTokenDto generateToken(User user, String currentRoleCode, AuthPolicy policy, LoginMethod method, MfaService.Proof mfaProof) {
        boolean restricted = sessions.start(user, currentRoleCode, policy, method, mfaProof);
        var token = currentToken();
        token.setPasswordChangeRequired(restricted);
        return token;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private LoginTokenDto currentToken() {
        LoginTokenDto dto = new LoginTokenDto();
        dto.setAccessToken(StpUtil.getTokenValue());
        return dto;
    }

}
