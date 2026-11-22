package me.lendev.flow.mail;

import static me.lendev.flow.common.auth.PermissionCode.MAIL_ACCOUNT;
import static me.lendev.flow.common.auth.PermissionCode.MAIL_LOG;
import static me.lendev.flow.common.auth.PermissionCode.MAIL_TEMPLATE;
import static me.lendev.flow.mail.MailAdminModels.*;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.R;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailStore.SendLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮箱配置、邮件模板及发送历史的管理接口。
 */
@RestController
@RequestMapping("/mail")
@RequiredArgsConstructor
@Tag(name = "邮件管理")
public class MailAdminController {

    private final MailAdminService service;

    /**
     * 按关键词和启停状态筛选邮箱配置。
     *
     * @param pageNo   从 1 开始的页码，默认 1
     * @param pageSize 每页数量，默认 20，最多 100
     * @param keyword  可选的邮箱配置搜索词
     * @param enable   可选的启用状态
     * @return 当前页账号配置和总数，凭据仅显示配置状态
     */
    @GetMapping("/accounts")
    public R<Page<AccountView>> accounts(@RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                         @RequestParam(required = false) @Size(max = 100) String keyword,
                                         @RequestParam(required = false) Boolean enable) {
        permit(MAIL_ACCOUNT);
        return R.ok(service.listAccounts(pageNo, pageSize, keyword, enable));
    }

    /**
     * 登记邮箱连接配置并加密保存凭据。
     *
     * @param request 邮箱标识、服务商连接参数与凭据
     * @return 新邮箱配置和凭据配置状态
     */
    @PostMapping("/accounts")
    public R<AccountView> createAccount(@Valid @RequestBody AccountRequest request) {
        permit(MAIL_ACCOUNT);
        return R.ok(service.createAccount(request, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 更新已登记的邮箱连接配置。
     *
     * @param id      邮箱配置 ID
     * @param request 修改后的服务商参数及可选的新凭据
     * @return 更新后的配置和凭据配置状态
     */
    @PutMapping("/accounts/{id}")
    public R<AccountView> updateAccount(@PathVariable @Positive Long id, @Valid @RequestBody AccountRequest request) {
        permit(MAIL_ACCOUNT);
        return R.ok(service.updateAccount(id, request, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 提供邮件模板选择邮箱所需的账号选项。
     *
     * @return 可选择的邮箱标识和展示名称
     */
    @GetMapping("/accounts/options")
    public R<List<Option>> accountOptions() {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermissionOr(MAIL_ACCOUNT, MAIL_TEMPLATE);
        }
        return R.ok(service.accountOptions());
    }

    /**
     * 按关键词和启停状态筛选邮件模板。
     *
     * @param pageNo   从 1 开始的页码，默认 1
     * @param pageSize 每页数量，默认 20，最多 100
     * @param keyword  可选的模板搜索词
     * @param enable   可选的启用状态
     * @return 当前页模板和匹配总数
     */
    @GetMapping("/templates")
    public R<Page<TemplateView>> templates(@RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                           @RequestParam(required = false) @Size(max = 100) String keyword,
                                           @RequestParam(required = false) Boolean enable) {
        permit(MAIL_TEMPLATE);
        return R.ok(service.listTemplates(pageNo, pageSize, keyword, enable));
    }

    /**
     * 登记使用指定邮箱的邮件模板。
     *
     * @param request 关联邮箱、发送方式和模板内容
     * @return 新模板配置及修订号
     */
    @PostMapping("/templates")
    public R<TemplateView> createTemplate(@Valid @RequestBody TemplateRequest request) {
        permit(MAIL_TEMPLATE);
        return R.ok(service.createTemplate(request, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 更新邮件模板配置。
     *
     * @param id      邮件模板 ID
     * @param request 修改后的邮箱引用、发送方式和模板内容
     * @return 更新后的模板配置及修订号
     */
    @PutMapping("/templates/{id}")
    public R<TemplateView> updateTemplate(@PathVariable @Positive Long id, @Valid @RequestBody TemplateRequest request) {
        permit(MAIL_TEMPLATE);
        return R.ok(service.updateTemplate(id, request, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 分页查询邮件提交记录。
     *
     * @param pageNo     从 1 开始的页码，默认 1
     * @param pageSize   每页数量，默认 20，最多 100
     * @param accountId  可选的邮箱配置 ID
     * @param templateId 可选的模板 ID
     * @param status     可选的批次处理状态
     * @param from       可选的查询起始时点
     * @param to         可选的查询结束时点
     * @return 发送批次摘要和符合条件的总数
     */
    @GetMapping("/logs")
    public R<Page<SendLog>> logs(@RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                 @RequestParam(required = false) @Positive Long accountId,
                                 @RequestParam(required = false) @Positive Long templateId,
                                 @RequestParam(required = false) Status status,
                                 @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to) {
        permit(MAIL_LOG);
        return R.ok(service.listLogs(pageNo, pageSize, accountId, templateId, status, from, to));
    }

    /**
     * 读取用于筛选发送日志的关联选项。
     *
     * @return 日志查询可使用的选项集合
     */
    @GetMapping("/logs/options")
    public R<LogOptions> logOptions() {
        permit(MAIL_LOG);
        return R.ok(service.logOptions());
    }

    /**
     * 查询一个发送批次及其有序明细。
     *
     * @param id 发送日志 ID
     * @return 批次状态、各明细提交状态及可用的错误码
     */
    @GetMapping("/logs/{id}")
    public R<LogView> log(@PathVariable @Positive long id) {
        permit(MAIL_LOG);
        return R.ok(service.log(id));
    }

    private static void permit(String code) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(code);
        }
    }
}
