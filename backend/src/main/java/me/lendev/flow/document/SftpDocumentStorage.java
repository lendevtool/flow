package me.lendev.flow.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.future.CancelOption;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.stereotype.Component;

/**
 * 复用 SFTP 连接存储文档，文件完整上传后才发布。
 */
@Component
class SftpDocumentStorage implements DocumentStorage, AutoCloseable {

    private static final Pattern STORAGE_DIRECTORY = Pattern.compile("\\d{4}(?:0[1-9]|1[0-2])/(?:0|[1-9]\\d{0,9})");
    private static final Pattern STORAGE_FILENAME = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    private static final int MAX_STORAGE_KEY_LENGTH = 54; // 年月、最多十位的所有者 ID、UUID 和两个分隔符。
    private static final String STAGING_SUFFIX = ".part";

    private final SftpDocumentStorageProperties properties;
    private final Connections connections = new Connections();
    private final GenericObjectPool<Connection> pool;

    SftpDocumentStorage(SftpDocumentStorageProperties properties) {
        this.properties = properties;
        var config = new GenericObjectPoolConfig<Connection>();
        config.setMaxTotal(properties.getPoolMaxTotal());
        config.setMaxIdle(properties.getPoolMaxTotal());
        config.setMaxWait(properties.getPoolMaxWait());
        config.setMinEvictableIdleDuration(properties.getPoolIdleTimeout());
        config.setTimeBetweenEvictionRuns(properties.getPoolIdleTimeout());
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        pool = new GenericObjectPool<>(connections, config);
    }

    @Override
    public void put(String key, InputStream source) throws IOException {
        String path = remotePath(key);
        String stagedPath = path + STAGING_SUFFIX;
        withSftp("upload", sftp -> {
            if (key.indexOf('/') >= 0) {
                int fileSeparator = path.lastIndexOf('/');
                ensureDirectory(sftp, path.substring(0, path.lastIndexOf('/', fileSeparator - 1)));
                ensureDirectory(sftp, path.substring(0, fileSeparator));
            }
            OutputStream staged = sftp.write(stagedPath, OpenMode.Write, OpenMode.Create, OpenMode.Exclusive);
            try {
                try (staged) {
                    source.transferTo(staged);
                }
                sftp.rename(stagedPath, path);
            } catch (IOException | RuntimeException failure) {
                try {
                    removeIfPresent(sftp, stagedPath);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        });
    }

    private static void ensureDirectory(SftpClient sftp, String path) throws IOException {
        if (directoryExists(sftp, path)) {
            return;
        }
        try {
            sftp.mkdir(path);
        } catch (IOException failure) {
            // SFTP v3 可能用通用失败状态报告目录已存在，用查询确认并发创建的结果。
            try {
                if (directoryExists(sftp, path)) {
                    return;
                }
            } catch (IOException checkFailure) {
                failure.addSuppressed(checkFailure);
            }
            throw failure;
        }
    }

    private static boolean directoryExists(SftpClient sftp, String path) throws IOException {
        try {
            if (!sftp.lstat(path).isDirectory()) {
                throw new IOException("SFTP upload directory is not a directory: " + path);
            }
            return true;
        } catch (SftpException failure) {
            if (isMissing(failure)) {
                return false;
            }
            throw failure;
        }
    }

    @Override
    public void read(String key, Reader reader) throws IOException {
        String path = remotePath(key);
        withSftp("download", sftp -> {
            try (InputStream source = sftp.read(path)) {
                reader.read(source);
            }
        });
    }

    @Override
    public void delete(String key) throws IOException {
        String path = remotePath(key);
        withSftp("delete", sftp -> {
            removeIfPresent(sftp, path + STAGING_SUFFIX);
            removeIfPresent(sftp, path);
        });
    }

    private String remotePath(String key) throws IOException {
        if (key == null || key.length() > MAX_STORAGE_KEY_LENGTH || !validStorageKey(key)) {
            throw new IOException("Invalid document storage key");
        }
        String root = properties.getRoot();
        if (root == null || root.indexOf('\\') >= 0 || root.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid document SFTP root directory");
        }
        for (String segment : root.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Invalid document SFTP root directory");
            }
        }
        return properties.relativePath(key);
    }

    private static boolean validStorageKey(String key) {
        int filenameStart = key.lastIndexOf('/') + 1;
        return STORAGE_FILENAME.matcher(key).region(filenameStart, key.length()).matches()
                && (filenameStart == 0 || STORAGE_DIRECTORY.matcher(key).region(0, filenameStart - 1).matches());
    }

    private void withSftp(String action, SftpOperation operation) throws IOException {
        Connection connection;
        try {
            connection = pool.borrowObject();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw sftpFailure("connection pool wait interrupted", interrupted);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw sftpFailure("connection pool borrow", failure);
        }

        boolean discard = false;
        try {
            operation.execute(connection.sftp());
        } catch (IOException | RuntimeException failure) {
            // 流关闭失败会附在响应端异常上。只有远端流正常关闭时才归还连接。
            if (failure instanceof DownloadOutputException outputFailure && outputFailure.getSuppressed().length == 0) {
                throw outputFailure;
            }
            discard = true;
            try {
                pool.invalidateObject(connection);
            } catch (Exception closingFailure) {
                failure.addSuppressed(closingFailure);
            }
            if (failure instanceof IOException ioFailure) {
                throw sftpFailure(action, ioFailure);
            }
            throw failure;
        } finally {
            if (!discard) {
                pool.returnObject(connection);
            }
        }
    }

    private IOException sftpFailure(String stage, Throwable cause) {
        return new IOException("SFTP %s failed (host=%s, port=%d, root=%s)"
                .formatted(stage, properties.getHost(), properties.getPort(), properties.getRoot()), cause);
    }

    /**
     * 应用关闭时回收池中连接，并关闭共享 SSH 客户端。
     */
    @Override
    public void close() throws IOException {
        pool.close();
        connections.closeClient();
    }

    private record Connection(ClientSession session, SftpClient sftp) {

    }

    private final class Connections extends BasePooledObjectFactory<Connection> {

        private SshClient client;
        private boolean closed;

        @Override
        public Connection create() throws IOException {
            Duration timeout = properties.getConnectTimeout();
            var session = connect(timeout);
            String stage = "SSH authentication";
            try {
                session.auth().verify(timeout);
                stage = "channel open";
                return new Connection(session, SftpClientFactory.instance().createSftpClient(session));
            } catch (IOException | RuntimeException failure) {
                session.close(true);
                throw sftpFailure(stage, failure);
            }
        }

        private ClientSession connect(Duration timeout) throws IOException {
            try {
                return sshClient().connect(properties.getUsername(), properties.getHost(), properties.getPort())
                        .verify(timeout, CancelOption.CANCEL_ON_TIMEOUT, CancelOption.CANCEL_ON_INTERRUPT).getSession();
            } catch (IOException | RuntimeException failure) {
                throw sftpFailure("SSH connection setup", failure);
            }
        }

        private synchronized SshClient sshClient() throws IOException {
            if (closed) {
                throw new IOException("SFTP storage is closed");
            }
            if (client != null) {
                return client;
            }
            Path privateKey = requiredFile(properties.getPrivateKey(), "document.sftp.private-key");
            Path knownHosts = requiredFile(properties.getKnownHosts(), "document.sftp.known-hosts");
            var verifier = new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHosts);
            Duration connectTimeout = properties.getConnectTimeout();
            Duration ioTimeout = properties.getIoTimeout();
            SshClient next = SshClient.setUpDefaultClient();
            next.setServerKeyVerifier(verifier);
            next.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            next.setAgentFactory(null);
            next.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
            next.setKeyIdentityProvider(new FileKeyPairProvider(privateKey));
            CoreModuleProperties.NIO_WORKERS.set(next, 2);
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(next, connectTimeout);
            CoreModuleProperties.AUTH_TIMEOUT.set(next, connectTimeout);
            CoreModuleProperties.IDLE_TIMEOUT.set(next, ioTimeout);
            AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(next, ioTimeout);
            SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(next, ioTimeout);
            try {
                next.start();
            } catch (RuntimeException failure) {
                try {
                    next.close();
                } catch (IOException closingFailure) {
                    failure.addSuppressed(closingFailure);
                }
                throw failure;
            }
            client = next;
            return client;
        }

        private synchronized void closeClient() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (client != null) {
                client.close();
            }
        }

        private static Path requiredFile(Path file, String setting) throws IOException {
            if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IOException("Missing or unreadable SFTP configuration file for " + setting);
            }
            return file;
        }

        @Override
        public PooledObject<Connection> wrap(Connection connection) {
            return new DefaultPooledObject<>(connection);
        }

        @Override
        public boolean validateObject(PooledObject<Connection> pooled) {
            var connection = pooled.getObject();
            return connection.session().isOpen() && connection.sftp().isOpen() && !connection.sftp().isClosing();
        }

        @Override
        public void destroyObject(PooledObject<Connection> pooled) throws IOException {
            var connection = pooled.getObject();
            try {
                connection.sftp().close();
            } finally {
                connection.session().close(true);
            }
        }
    }

    private static void removeIfPresent(SftpClient sftp, String path) throws IOException {
        try {
            sftp.remove(path);
        } catch (SftpException failure) {
            if (!isMissing(failure)) {
                throw failure;
            }
        }
    }

    private static boolean isMissing(SftpException failure) {
        return failure.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE
                || failure.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH;
    }

    @FunctionalInterface
    private interface SftpOperation {

        void execute(SftpClient sftp) throws IOException;
    }
}
