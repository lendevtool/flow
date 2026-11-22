package me.lendev.flow.pms.service;

import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.request.UpdateAppearanceRequest;
import com.mybatisflex.core.service.IService;

/**
 * 用户资料服务
 *
 * @author lendev
 */
public interface ProfileService extends IService<Profile> {

    /**
     * 通过用户id获取用户信息
     *
     * @param userId 用户id
     * @return 用户信息
     */
    Profile findByUserId(Long userId);

    /**
     * 保存指定用户提交的界面偏好。每次只更新请求包含的字段，重复提交相同值安全。
     *
     * @param userId  当前登录用户的 ID
     * @param request 偏好更新请求
     */
    void updateAppearance(Long userId, UpdateAppearanceRequest request);

}
