package me.lendev.flow.audit.query;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 审计日志 CSV 导出工具：输出带 BOM 的 UTF-8 内容，并对单元格做引号转义与公式注入防护。
 */
public final class AuditCsv {

    private static final String[] COLUMNS = {"occurredAt", "actorName", "actorAccount", "claimedAccount", "actorRoleName", "moduleCode", "summary", "result", "sourceIp", "requestId"};
    private static final String FORMULA_PREFIXES = "=+-@＝＋－＠\t\r\n";

    private AuditCsv() {
    }

    /**
     * 按指定语言输出表头，并对可能被识别为公式的单元格加文本前缀。
     *
     * @param rows   原始审计记录
     * @param locale 表头使用的语言
     * @return 带 BOM 的 UTF-8 CSV
     */
    public static byte[] bytes(List<Map<String, Object>> rows, Locale locale) {
        String headers = Locale.ENGLISH.getLanguage().equals(locale.getLanguage())
                ? "Operation time (UTC),Actor,Authenticated account,Claimed account (unauthenticated),Role,Module,Operation,Result,Source IP,Request ID"
                : "操作时间(UTC),操作者,认证账号,声称账号(未认证),角色,模块,操作内容,结果,来源IP,请求ID";
        StringBuilder csv = new StringBuilder("\ufeff").append(headers).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < COLUMNS.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                String value = Objects.toString(row.get(COLUMNS[i]), "");
                if (needsTextPrefix(value)) {
                    value = "'" + value;
                }
                csv.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean needsTextPrefix(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            // 制表符和换行符本身也需要防护，先检查再跳过其他空白字符。
            if (FORMULA_PREFIXES.indexOf(current) >= 0) {
                return true;
            }
            if (!Character.isWhitespace(current)) {
                return false;
            }
        }
        return false;
    }
}
