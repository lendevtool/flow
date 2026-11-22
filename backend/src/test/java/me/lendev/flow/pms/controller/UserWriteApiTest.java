package me.lendev.flow.pms.controller;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import me.lendev.flow.common.auth.PasswordHashes;

import java.util.List;
import java.util.Map;

import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.service.ProfileService;
import me.lendev.flow.pms.service.UserRoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 用户写操作接口测试
 */
class UserWriteApiTest extends ApiTestBase {

    @Autowired
    private UserService userService;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserRoleService userRoleService;

    @Test
    void createUserWithProfileAndRoles() throws Exception {
        String token = loginAsAdmin();
        var created = data(postJson("/user", token, map(
                "username", "new_user",
                "password", "pw123456",
                "nameCn", "  李小明  ",
                "enable", true,
                "profile", map("nickName", "nn", "gender", 1, "email", "e@e.com",
                        "address", "addr", "avatar", "av"),
                "roleIds", List.of(2))));

        User saved = userService.queryChain().and(USER.USERNAME.eq("new_user")).one();
        assertNotNull(saved.getId());
        assertTrue(saved.getEnable());
        assertEquals("李小明", saved.getNameCn());
        var list = data(getJson("/user", token, "username", "new_user"));
        assertEquals("李小明", list.path("pageData").get(0).path("nameCn").asString());
        assertTrue(PasswordHashes.matches(created.path("temporaryPassword").asString(), saved.getPassword()));

        Profile profile = profileService.findByUserId(saved.getId());
        assertEquals("nn", profile.getNickName());
        assertEquals(Integer.valueOf(1), profile.getGender());
        assertEquals("e@e.com", profile.getEmail());
        assertEquals("addr", profile.getAddress());
        assertEquals("av", profile.getAvatar());

        assertEquals(1, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(saved.getId())).count());
    }

    @Test
    void createUserWithoutProfile() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/user", token, map("username", "no_profile", "password", "pw123456")));

        User saved = userService.queryChain().and(USER.USERNAME.eq("no_profile")).one();
        Profile profile = profileService.findByUserId(saved.getId());
        assertNotNull(profile.getId());
        assertNull(profile.getNickName());
        assertEquals(0, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(saved.getId())).count());
    }

    @Test
    void createUserDuplicated() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/user", token, map("username", "dup_user", "password", "pw123456")));
        assertEquals("用户已存在",
                code(postJson("/user", token, map("username", "dup_user", "password", "pw123456")), 10001)
                        .get("message").asString());
    }

    @Test
    void updateUser() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/user/1", token, map("roleIds", List.of(2), "enable", false)));

        assertEquals(1, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L)).count());
        assertEquals(2L, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L))
                .list().getFirst().getRoleId());
        assertFalse(userService.getById(1L).getEnable());

        // 外层测试事务尚未提交，因此 Redis 会话清理还没有执行。
        // 但数据库中已禁用的账号会通过既有业务逻辑直接拒绝该请求（错误码 10002）。
        code(patchJson("/user/1", token, Map.of()), 10002);
        assertFalse(userService.getById(1L).getEnable());
    }

    @Test
    void updateChineseNameAppearsInTheListAndDetail() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/user/1", token, Map.of("nameCn", "  张三  ")));
        var list = data(getJson("/user", token, "username", ADMIN));
        assertEquals("张三", list.path("pageData").get(0).path("nameCn").asString());
        assertEquals("张三", data(getJson("/user/detail", token)).path("nameCn").asString());

        data(patchJson("/user/1", token, Map.of("nameEn", "Zhang San")));
        code(patchJson("/user/1", token, Map.of("nameCn", "超".repeat(101))), 400);
        assertEquals("张三", userService.getById(1L).getNameCn());

        data(patchJson("/user/1", token, map("nameCn", null)));
        assertNull(userService.getById(1L).getNameCn());
        assertEquals("Zhang San", userService.getById(1L).getNameEn());
        assertEquals("Admin", profileService.findByUserId(1L).getNickName());
    }

    @Test
    void editingChineseNameDoesNotDependOnLegacyProfile() throws Exception {
        String token = loginAsAdmin();
        profileService.removeById(1L);

        data(patchJson("/user/1", token, Map.of("nameCn", "管理员")));

        assertEquals("管理员", userService.getById(1L).getNameCn());
        assertNull(profileService.findByUserId(1L));
    }

    @Test
    void omittedOrNullRoleIdsLeaveExistingAssignmentsUnchanged() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/user/1", token, Map.of()));
        assertEquals(List.of(1L, 2L), userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L))
                .list().stream().map(UserRole::getRoleId).sorted().toList());

        data(patchJson("/user/1", token, map("roleIds", null)));
        assertEquals(List.of(1L, 2L), userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L))
                .list().stream().map(UserRole::getRoleId).sorted().toList());
    }

    @Test
    void emptyRoleIdsClearExistingAssignments() throws Exception {
        data(patchJson("/user/1", loginAsAdmin(), map("roleIds", List.of())));
        assertEquals(0, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L)).count());
    }

    @Test
    void nullRoleIdElementIsRejectedWithoutChangingAssignments() throws Exception {
        code(patchJson("/user/1", loginAsAdmin(),
                map("roleIds", java.util.Arrays.asList(1L, null))), 400);
        assertEquals(List.of(1L, 2L), userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L))
                .list().stream().map(UserRole::getRoleId).sorted().toList());
    }

    @Test
    void removeUser() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/user", token, map("username", "to_delete", "password", "pw123456")));
        User saved = userService.queryChain().and(USER.USERNAME.eq("to_delete")).one();

        data(deleteJson("/user/" + saved.getId(), token));
        assertNull(userService.getById(saved.getId()));
        assertNull(profileService.findByUserId(saved.getId()));
    }

    @Test
    void removeSelfIsRejected() throws Exception {
        String token = loginAsAdmin();
        assertEquals("非法操作，不能删除自己！",
                code(deleteJson("/user/1", token), 11006).get("message").asString());
    }

    @Test
    void updateOwnProfile() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/user/profile/1", token, map(
                "id", 1, "nickName", "newNick", "gender", 0, "address", "a2", "email", "e2")));

        Profile profile = profileService.getById(1L);
        assertEquals("newNick", profile.getNickName());
        assertEquals(Integer.valueOf(0), profile.getGender());
        assertEquals("a2", profile.getAddress());
        assertEquals("e2", profile.getEmail());
    }

    @Test
    void updateOthersProfileIsRejected() throws Exception {
        String token = loginAsAdmin();
        assertEquals("越权操作，用户资料只能本人修改！",
                code(patchJson("/user/profile/2", token, map("id", 2, "nickName", "x")), 11004)
                        .get("message").asString());
    }

    @Test
    void addRoles() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/user/roles/add/1", token, map("roleIds", List.of(1, 2))));
        assertEquals(2, userRoleService.queryChain().and(USER_ROLE.USER_ID.eq(1L)).count());
    }

    @Test
    void resetPassword() throws Exception {
        String token = loginAsAdmin();
        var reset = data(patchJson("/user/password/reset/1", token, null));
        String temporary = reset.path("temporaryPassword").asString();
        assertTrue(PasswordHashes.matches(temporary, userService.getById(1L).getPassword()));
        assertNotNull(login(ADMIN, temporary));
    }

    @Test
    void resetPasswordReturnsAnExpiryAndRestrictsTheNewSession() throws Exception {
        var reset = data(patchJson("/user/password/reset/1", loginAsAdmin(), null));
        assertTrue(reset.path("expiresAt").isString());
        String restricted = login(ADMIN, reset.path("temporaryPassword").asString());
        code(getJson("/user/detail", restricted), 10005);
    }

    @Test
    void changePassword() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/auth/password", token, map("oldPassword", ADMIN_PASSWORD, "newPassword", "NewTest1234!")));
        assertTrue(PasswordHashes.matches("NewTest1234!", userService.getById(1L).getPassword()));
    }

    @Test
    void changePasswordWithWrongOldPassword() throws Exception {
        String token = loginAsAdmin();
        var result = code(postJson("/auth/password", token,
                map("oldPassword", "wrong", "newPassword", "NewTest1234!")), 10004);
        assertEquals("原密码不正确", result.path("message").asString());
    }

    @Test
    void explainsWhichNewPasswordRulesFailed() throws Exception {
        String token = loginAsAdmin();
        var emojis = "😀".repeat(21);
        var length = body(postJson("/auth/password", token,
                map("oldPassword", ADMIN_PASSWORD, "newPassword", emojis)), 400);
        assertEquals("密码长度须为 12–20 个字符", length.path("message").asString());
        var control = body(postJson("/auth/password", token,
                map("oldPassword", ADMIN_PASSWORD, "newPassword", "NewTest1234!\t")), 400);
        assertEquals("密码不能包含控制字符（如换行或制表符）或无效字符", control.path("message").asString());

        var missing = map("oldPassword", ADMIN_PASSWORD, "newPassword", "password1234");
        var chinese = body(postJson("/auth/password", token, missing), 400);
        assertEquals("新密码缺少以下字符类别：大写字母、特殊字符", chinese.path("message").asString());
        var english = body(mockMvc.perform(post("/auth/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(missing))), 400);
        assertEquals("The new password is missing uppercase letters, special characters.",
                english.path("message").asString());
    }

}
