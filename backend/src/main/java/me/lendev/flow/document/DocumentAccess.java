package me.lendev.flow.document;

import cn.dev33.satoken.stp.StpUtil;

import java.util.Objects;

import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentCenter.BusinessRecord;
import me.lendev.flow.document.entity.DocumentRow;

/**
 * 校验文档中心的登录权限、独立文档的所有者及业务文档的归属范围。
 */
final class DocumentAccess {

    private static final String INVALID_BUSINESS = "error.document.business";

    private DocumentAccess() {
    }

    static int actor() {
        return StpUtil.getLoginIdAsInt();
    }

    static void permit(String permission) {
        StpUtil.checkLogin();
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(permission);
        }
    }

    static BusinessRecord business(BusinessRecord value) {
        if (value == null) {
            return null;
        }
        return new BusinessRecord(DocumentNames.required(value.module(), 64, INVALID_BUSINESS),
                DocumentNames.required(value.type(), 64, INVALID_BUSINESS),
                DocumentNames.required(value.id(), 128, INVALID_BUSINESS));
    }

    static BusinessRecord requireBusiness(BusinessRecord value) {
        if (value == null) {
            throw BadRequestException.localized(INVALID_BUSINESS);
        }
        return business(value);
    }

    static DocumentRow active(DocumentRow row) {
        if (row == null || row.getDeletedAt() != null) {
            throw DocumentFailure.missing();
        }
        return row;
    }

    static BusinessRecord business(DocumentRow row) {
        return row.getSourceModule() == null ? null
                : new BusinessRecord(row.getSourceModule(), row.getBusinessType(), row.getBusinessId());
    }

    /**
     * 读取有效且在指定范围内的文档。独立文档须由创建者访问，已绑定文档须匹配完整的业务记录标识。
     * 越权访问与文档缺失返回相同的错误。
     */
    static DocumentRow requireScope(DocumentRow row, BusinessRecord business, int actor) {
        active(row);
        if (!Objects.equals(business, business(row)) || business == null && row.getCreatedBy() != actor) {
            throw DocumentFailure.missing();
        }
        return row;
    }

    static void checkBind(DocumentRow row, BusinessRecord business, int actor) {
        active(row);
        var owner = business(row);
        if (owner == null && row.getCreatedBy() != actor) {
            throw DocumentFailure.missing();
        }
        if (owner != null && !owner.equals(business)) {
            if (row.getCreatedBy() != actor) {
                throw DocumentFailure.missing();
            }
            throw DocumentFailure.conflict("error.document.business-conflict");
        }
    }
}
