package me.lendev.flow.pms.domain.dto;

import java.time.Instant;

/**
 * 仅在建号或重置成功的响应中返回一次。
 */
public record TemporaryPasswordDto(String temporaryPassword, Instant expiresAt) {

    @Override
    public String toString() {
        return "TemporaryPasswordDto[expiresAt=" + expiresAt + "]";
    }
}
