package me.lendev.flow.institution;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 档案或申请不存在，以及当前操作存在业务冲突。
 */
@Getter
public class InstitutionFailure extends RuntimeException {

    private final HttpStatus status;

    private InstitutionFailure(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    static InstitutionFailure missing() {
        return new InstitutionFailure(HttpStatus.NOT_FOUND, "error.institution.missing");
    }

    static InstitutionFailure conflict(String code) {
        return new InstitutionFailure(HttpStatus.CONFLICT, code);
    }
}
