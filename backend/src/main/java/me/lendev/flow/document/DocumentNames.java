package me.lendev.flow.document;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import me.lendev.flow.common.exception.BadRequestException;

/**
 * 规范化上传文件名，并按业务日期和发布序号生成版本号及下载文件名。
 */
final class DocumentNames {

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private DocumentNames() {
    }

    /**
     * 取客户端文件路径的最后一段，兼容正斜杠和反斜杠分隔符。
     *
     * @param value 客户端提供的原始文件名
     * @return 去除首尾空白后的文件名
     * @throws BadRequestException 文件名为空、超长、含控制字符或指向目录自身
     */
    static String originalName(String value) {
        if (value == null || CONTROL.matcher(value).find()) {
            throw BadRequestException.localized("error.document.file-name");
        }
        String portable = value.replace('\\', '/');
        String name = portable.substring(portable.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")) {
            throw BadRequestException.localized("error.document.file-name");
        }
        return name;
    }

    static String versionNo(LocalDate date, int sequence) {
        String number = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        return sequence == 1 ? number : number + "V" + sequence;
    }

    static String downloadName(String original, String versionNo) {
        int dot = original.lastIndexOf('.');
        String suffix = "_" + versionNo;
        if (dot <= 0 || dot == original.length() - 1) {
            return original + suffix;
        }
        return original.substring(0, dot) + suffix + original.substring(dot);
    }

    static String required(String value, int max, String errorKey) {
        if (value == null || value.isBlank() || value.length() > max || CONTROL.matcher(value).find()) {
            throw BadRequestException.localized(errorKey);
        }
        return value.strip();
    }
}
