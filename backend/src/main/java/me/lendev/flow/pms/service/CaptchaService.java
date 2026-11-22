package me.lendev.flow.pms.service;

import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;

/**
 * 验证码服务
 *
 * @author lendev
 */
public interface CaptchaService {

    /**
     * 创建验证码
     *
     * @return key与验证码
     */
    default Pair<String, ICaptcha> create() {
        return create(60);
    }

    /**
     * 创建验证码并写入共享缓存，到期自动失效。
     *
     * @param ttlSeconds 验证码有效期（秒）
     * @return key与验证码
     */
    Pair<String, ICaptcha> create(long ttlSeconds);

    /**
     * 校验验证码
     *
     * @param key  验证码的key
     * @param code 验证码
     * @return 判断是一致
     */
    boolean verify(String key, String code);

}
