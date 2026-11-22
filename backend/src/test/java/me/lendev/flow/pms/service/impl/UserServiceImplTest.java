package me.lendev.flow.pms.service.impl;

import me.lendev.flow.ServeApplicationTests;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.service.CaptchaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import me.lendev.flow.support.SaTokenTestExtension;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(SaTokenTestExtension.class)
class UserServiceImplTest extends ServeApplicationTests {

    @Autowired
    private UserServiceImpl userService;

    @Autowired
    private CaptchaService captchaService;

    @Test
    void loginConsumesCaptcha() {
        var captcha = captchaService.create();
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("123456");
        request.setCaptchaKey(captcha.getKey());
        request.setCaptcha(captcha.getValue().getCode());
        LoginTokenDto tokenDto = userService.login(request);
        assertNotNull(tokenDto);
        assertNotNull(tokenDto.getAccessToken());
        assertFalse(captchaService.verify(captcha.getKey(), captcha.getValue().getCode()));
    }
}
