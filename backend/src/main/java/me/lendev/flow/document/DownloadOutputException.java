package me.lendev.flow.document;

import java.io.IOException;

/**
 * 文档已打开，但 HTTP 响应端无法继续写入。
 */
final class DownloadOutputException extends IOException {

    DownloadOutputException(IOException cause) {
        super("文档下载响应写入失败", cause);
    }
}
