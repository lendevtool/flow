package me.lendev.flow.mail;

import static me.lendev.flow.common.auth.PermissionCode.MAIL_READ;
import static me.lendev.flow.common.auth.PermissionCode.MAIL_SEND;
import static me.lendev.flow.common.auth.PermissionCode.MAIL_TEMPLATE;
import static me.lendev.flow.mail.MailModels.*;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供与邮箱接入协议无关的邮件收发及模板预览接口。
 */
@RestController
@RequestMapping("/mail")
@RequiredArgsConstructor
public class MailController {
    private final MailService mail;

    /**
     * 根据组合条件按需读取收件箱，返回邮件和下一页游标。
     */
    @GetMapping("/inbox")
    public R<InboxPage> inbox(@RequestParam @NotBlank @Size(max = 64) String accountCode,
                              @RequestParam(required = false) @Email @Size(max = 254) String sender,
                              @RequestParam(required = false) @Size(max = 255) String subject,
                              @RequestParam(required = false) Instant receivedFrom, @RequestParam(required = false) Instant receivedTo,
                              @RequestParam(required = false) Boolean read, @RequestParam(defaultValue = "false") boolean includeBody,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize,
                              @RequestParam(required = false) @Size(max = MailSecurity.MAX_CURSOR_LENGTH) String cursor,
                              HttpServletResponse response) {
        StpUtil.checkPermission(MAIL_READ);
        String requestId = requestId(response);
        var query = new InboxQuery(sender, subject, receivedFrom, receivedTo, read, includeBody, pageSize);
        return R.ok(mail.inbox(accountCode, query, cursor, StpUtil.getLoginIdAsString(), requestId));
    }

    /**
     * 提交模板邮件，返回持久化日志标识及邮件服务受理状态。
     */
    @PostMapping("/send")
    public R<SendResult> send(@Valid @RequestBody SendRequest request, HttpServletResponse response) {
        StpUtil.checkPermission(MAIL_SEND);
        return R.ok(mail.send(request, StpUtil.getLoginIdAsString(), requestId(response)));
    }

    /**
     * 使用未保存的模板和样例数据生成实际邮件的预览。
     */
    @PostMapping("/templates/preview")
    public R<RenderedMail> preview(@Valid @RequestBody PreviewRequest request, HttpServletResponse response) {
        StpUtil.checkPermission(MAIL_TEMPLATE);
        requestId(response);
        return R.ok(mail.preview(request));
    }

    private static String requestId(HttpServletResponse response) {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return requestId;
    }
}
