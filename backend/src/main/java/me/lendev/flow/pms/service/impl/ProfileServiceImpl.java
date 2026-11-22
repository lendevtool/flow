package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.convert.ProfileConverter;
import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.request.UpdateAppearanceRequest;
import me.lendev.flow.pms.mapper.ProfileMapper;
import me.lendev.flow.pms.service.ProfileService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户资料服务实现类
 *
 * @author lendev
 */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl extends ServiceImpl<ProfileMapper, Profile>
        implements ProfileService {

    private final ProfileConverter profileConverter;

    @Override
    public Profile findByUserId(Long userId) {
        return queryChain().and(PROFILE.USER_ID.eq(userId, true)).one();
    }

    @Override
    public void updateAppearance(Long userId, UpdateAppearanceRequest request) {
        if (!request.hasChanges()) {
            return;
        }
        int updated = getMapper().updateByQuery(profileConverter.toProfile(request), true,
                QueryWrapper.create().where(PROFILE.USER_ID.eq(userId, true)));
        if (updated == 0 && findByUserId(userId) == null) {
            throw BadRequestException.localized("error.profile.missing");
        }
    }

}
