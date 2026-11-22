package me.lendev.flow.pms.workflownotification.mapper;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.pms.workflownotification.persistence.NoticeRecipient;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 投递收件人访问及当前可投递账号资料。
 */
public interface NoticeRecipientMapper extends BaseMapper<NoticeRecipient> {

    /**
     * 查找仍启用的账号及当前邮件地址；账号无邮箱时仍可写站内消息。
     */
    @Select("""
            SELECT p.email, COALESCE(NULLIF(u.nameCn, ''), NULLIF(u.nameEn, ''), u.username) AS name
            FROM `user` u LEFT JOIN profile p ON p.userId = u.id
            WHERE u.id = #{userId} AND u.enable = TRUE
            """)
    ActiveUser activeUser(@Param("userId") int userId);

    record ActiveUser(String email, String name) {
    }
}
