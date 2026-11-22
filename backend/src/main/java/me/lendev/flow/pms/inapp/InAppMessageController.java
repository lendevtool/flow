package me.lendev.flow.pms.inapp;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 已认证用户的个人站内消息接口。
 */
@RestController
@RequestMapping("/in-app-messages")
@RequiredArgsConstructor
class InAppMessageController {

    private final InAppMessageService service;
    private final MessageSource messages;

    /**
     * 查询本人消息的分页结果。
     */
    @GetMapping
    R<InAppMessageService.MessagePage> list(@RequestParam(required = false) Boolean unread,
                                            @RequestParam(required = false) @Positive Long beforeId,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.page(StpUtil.getLoginIdAsInt(), unread, beforeId, pageSize));
    }

    /**
     * 查询本人未读消息数。
     */
    @GetMapping("/unread-count")
    R<UnreadCount> unreadCount() {
        return R.ok(new UnreadCount(service.unread(StpUtil.getLoginIdAsInt())));
    }

    /**
     * 标记本人消息已读。缺失或非本人消息响应相同。
     */
    @PutMapping("/{id}/read")
    ResponseEntity<R<Void>> read(@PathVariable @Positive long id, Locale locale) {
        if (service.markRead(StpUtil.getLoginIdAsInt(), id)) {
            return ResponseEntity.ok(R.ok());
        }
        return ResponseEntity.status(404).body(new R<Void>().setCode(404)
                .setMessage(messages.getMessage("error.in-app-message.not-found", null, locale)));
    }

    record UnreadCount(long count) {
    }
}
