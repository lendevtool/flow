package me.lendev.flow.pms.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.ProfileDto;
import me.lendev.flow.pms.domain.dto.RoleDto;
import me.lendev.flow.pms.domain.dto.RolePageDto;
import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.request.CreatePermissionRequest;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import me.lendev.flow.pms.domain.request.RegisterUserProfileRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.domain.request.UpdatePermissionRequest;
import me.lendev.flow.pms.domain.request.UpdateProfileRequest;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * 转换器映射契约测试
 */
class ConverterTest {

    private final RoleConverter roleConverter = Mappers.getMapper(RoleConverter.class);

    private final PermissionConverter permissionConverter = Mappers.getMapper(PermissionConverter.class);

    private final UserConverter userConverter = Mappers.getMapper(UserConverter.class);

    private final ProfileConverter profileConverter = Mappers.getMapper(ProfileConverter.class);

    @Test
    void nullSourceReturnsNull() {
        assertNull(roleConverter.toRoleDto(null));
        assertNull(roleConverter.toRolePageDto(null));
        assertNull(roleConverter.toRole(null));
        assertNull(permissionConverter.toPermissionDto(null));
        assertNull(permissionConverter.toPermission((CreatePermissionRequest) null));
        assertNull(permissionConverter.toPermission((UpdatePermissionRequest) null));
        assertNull(userConverter.toUserDetailDto(null));
        assertNull(userConverter.toUser(null));
        assertNull(profileConverter.toProfileDto(null));
        assertNull(profileConverter.toProfile((RegisterUserProfileRequest) null));
        assertNull(profileConverter.toProfile((UpdateProfileRequest) null));
    }

    @Test
    void roleMappings() {
        Role role = new Role();
        role.setId(1L);
        role.setCode("ROLE_X");
        role.setName("角色");
        role.setEnable(true);

        RoleDto dto = roleConverter.toRoleDto(role);
        assertEquals(1L, dto.getId());
        assertEquals("ROLE_X", dto.getCode());
        assertEquals("角色", dto.getName());
        assertTrue(dto.getEnable());

        RolePageDto pageDto = roleConverter.toRolePageDto(role);
        assertEquals("ROLE_X", pageDto.getCode());
        assertNull(pageDto.getPermissionIds());

        CreateRoleRequest request = new CreateRoleRequest();
        request.setCode("ROLE_Y");
        request.setName("新角色");
        request.setEnable(false);
        Role created = roleConverter.toRole(request);
        assertEquals("ROLE_Y", created.getCode());
        assertEquals("新角色", created.getName());
        assertEquals(false, created.getEnable());
        assertNull(created.getId());
    }

    @Test
    void permissionMappings() {
        Permission permission = new Permission();
        permission.setId(2L);
        permission.setName("权限");
        permission.setCode("PERM_X");
        permission.setType("MENU");
        permission.setParentId(1L);
        permission.setPath("/x");
        permission.setRedirect("/y");
        permission.setIcon("icon");
        permission.setComponent("comp");
        permission.setLayout("layout");
        permission.setKeepAlive(true);
        permission.setMethod("GET");
        permission.setDescription("desc");
        permission.setShow(true);
        permission.setEnable(true);
        permission.setOrder(7);

        PermissionDto dto = permissionConverter.toPermissionDto(permission);
        assertEquals(2L, dto.getId());
        assertEquals("PERM_X", dto.getCode());
        assertEquals("MENU", dto.getType());
        assertEquals(1L, dto.getParentId());
        assertEquals("/x", dto.getPath());
        assertEquals("/y", dto.getRedirect());
        assertEquals("icon", dto.getIcon());
        assertEquals("comp", dto.getComponent());
        assertEquals("layout", dto.getLayout());
        assertTrue(dto.getKeepAlive());
        assertEquals("GET", dto.getMethod());
        assertEquals("desc", dto.getDescription());
        assertTrue(dto.getShow());
        assertTrue(dto.getEnable());
        assertEquals(Integer.valueOf(7), dto.getOrder());

        CreatePermissionRequest createRequest = new CreatePermissionRequest();
        createRequest.setName("新建");
        createRequest.setCode("PERM_C");
        createRequest.setType("BUTTON");
        createRequest.setShow(true);
        Permission created = permissionConverter.toPermission(createRequest);
        assertEquals("PERM_C", created.getCode());
        assertEquals("BUTTON", created.getType());
        assertTrue(created.getShow());
        assertNull(created.getId());

        UpdatePermissionRequest updateRequest = new UpdatePermissionRequest();
        updateRequest.setName("更新");
        updateRequest.setKeepAlive(true);
        Permission updated = permissionConverter.toPermission(updateRequest);
        assertEquals("更新", updated.getName());
        assertTrue(updated.getKeepAlive());
    }

    @Test
    void userMappings() {
        User user = new User();
        user.setId(3L);
        user.setUsername("user");
        user.setPassword("secret");
        user.setEnable(true);

        UserDetailDto dto = userConverter.toUserDetailDto(user);
        assertEquals(3L, dto.getId());
        assertEquals("user", dto.getUsername());
        assertTrue(dto.getEnable());
        assertNull(dto.getProfile());
        assertNull(dto.getRoles());
        assertNull(dto.getCurrentRole());

        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("new_user");
        request.setPassword("pw");
        request.setEnable(true);
        RegisterUserProfileRequest profileRequest = new RegisterUserProfileRequest();
        profileRequest.setNickName("nick");
        request.setProfile(profileRequest);

        User created = userConverter.toUser(request);
        assertEquals("new_user", created.getUsername());
        assertEquals("pw", created.getPassword());
        assertTrue(created.getEnable());
        assertNull(created.getId());
    }

    @Test
    void profileMappings() {
        Profile profile = new Profile();
        profile.setId(4L);
        profile.setUserId(3L);
        profile.setNickName("nick");
        profile.setGender(1);
        profile.setAvatar("avatar");
        profile.setAddress("address");
        profile.setEmail("mail");

        ProfileDto dto = profileConverter.toProfileDto(profile);
        assertEquals(4L, dto.getId());
        assertEquals(3L, dto.getUserId());
        assertEquals("nick", dto.getNickName());
        assertEquals(Integer.valueOf(1), dto.getGender());
        assertEquals("avatar", dto.getAvatar());
        assertEquals("address", dto.getAddress());
        assertEquals("mail", dto.getEmail());

        RegisterUserProfileRequest registerRequest = new RegisterUserProfileRequest();
        registerRequest.setNickName("reg");
        registerRequest.setGender(0);
        Profile fromRegister = profileConverter.toProfile(registerRequest);
        assertEquals("reg", fromRegister.getNickName());
        assertEquals(Integer.valueOf(0), fromRegister.getGender());
        assertNull(fromRegister.getUserId());

        UpdateProfileRequest updateRequest = new UpdateProfileRequest();
        updateRequest.setId(4L);
        updateRequest.setNickName("upd");
        updateRequest.setEmail("e2");
        Profile fromUpdate = profileConverter.toProfile(updateRequest);
        assertEquals(4L, fromUpdate.getId());
        assertEquals("upd", fromUpdate.getNickName());
        assertEquals("e2", fromUpdate.getEmail());
        assertNull(fromUpdate.getAvatar());
    }

}
