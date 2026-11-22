package me.lendev.flow.document;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 文档中心 SFTP 连接、信任文件和远端存储目录配置。
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "document.sftp")
public class SftpDocumentStorageProperties {

    @NotBlank
    private String host = "localhost";
    @Min(1)
    @Max(65535)
    private int port = 22;
    @NotBlank
    private String username = "sftp";
    @NotBlank
    private String root = "documents";
    private Path privateKey;
    private Path knownHosts;
    @DurationMin(millis = 1)
    private Duration connectTimeout = Duration.ofSeconds(5);
    @DurationMin(millis = 1)
    private Duration ioTimeout = Duration.ofSeconds(30);
    @Min(1)
    private int poolMaxTotal = 8;
    @DurationMin(millis = 1)
    private Duration poolMaxWait = Duration.ofSeconds(3);
    @DurationMin(millis = 1)
    private Duration poolIdleTimeout = Duration.ofMinutes(1);

    /**
     * 拼接文件在 SFTP 登录目录下的相对路径。
     *
     * @param storageKey 已保存的文档版本存储键
     * @return 包含配置根目录的相对路径
     */
    public String relativePath(String storageKey) {
        return root + "/" + storageKey;
    }
}
