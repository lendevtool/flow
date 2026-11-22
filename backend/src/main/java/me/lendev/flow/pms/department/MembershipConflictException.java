package me.lendev.flow.pms.department;

/**
 * 部门归属版本已变化，或旧客户端无法安全修改兼岗账号。
 */
public class MembershipConflictException extends RuntimeException {

    public MembershipConflictException(String messageKey) {
        super(messageKey);
    }
}
