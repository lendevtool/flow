package me.lendev.flow.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SftpDocumentStorageTest {

    private static final String HOST = "127.0.0.1";
    private static final String KEY = "1752c76a-33bc-44af-8869-1261ee4ed242";

    @TempDir
    Path tempDir;

    private SshServer server;
    private Path documentDirectory;
    private SftpDocumentStorageProperties properties;
    private SftpDocumentStorage storage;
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        Path serverHome = Files.createDirectory(tempDir.resolve("server"));
        documentDirectory = Files.createDirectories(serverHome.resolve("flow/documents"));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair serverKey = generator.generateKeyPair();
        KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider()).generateKeyPair();
        Path privateKey = tempDir.resolve("client-key");
        try (var output = Files.newOutputStream(privateKey)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "storage-test", null, output);
        }

        server = SshServer.setUpDefaultServer();
        server.setHost(HOST);
        SftpModuleProperties.SFTP_VERSION.set(server, 3);
        server.setKeyPairProvider(KeyPairProvider.wrap(serverKey));
        server.setPublickeyAuthenticator((username, key, session) ->
                "sftp".equals(username) && KeyUtils.compareKeys(clientKey.getPublic(), key));
        server.setFileSystemFactory(new VirtualFileSystemFactory(serverHome));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                sessions.incrementAndGet();
            }
        });
        server.start();

        Path knownHosts = tempDir.resolve("known_hosts");
        writeKnownHost(knownHosts, serverKey);
        properties = new SftpDocumentStorageProperties();
        properties.setHost(HOST);
        properties.setPort(server.getPort());
        properties.setRoot("flow/documents");
        properties.setPrivateKey(privateKey);
        properties.setKnownHosts(knownHosts);
        storage = new SftpDocumentStorage(properties);
    }

    @AfterEach
    void stopServer() throws Exception {
        try {
            if (storage != null) {
                storage.close();
            }
        } finally {
            if (server != null) {
                server.stop(true);
            }
        }
    }

    @Test
    void streamsFileToConfiguredDirectoryAndBack() throws IOException {
        byte[] bytes = new byte[1024 * 1024];
        new Random(42).nextBytes(bytes);

        storage.put(KEY, new ByteArrayInputStream(bytes));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        storage.read(KEY, input -> input.transferTo(result));

        assertThat(result.toByteArray()).isEqualTo(bytes);
        assertThat(documentDirectory.resolve(KEY)).hasBinaryContent(bytes);
        assertThat(documentDirectory.resolve(KEY + ".part")).doesNotExist();
    }

    @Test
    void streamsSystemOwnedGenerationFilesWithValidatedPaths() throws IOException {
        String key = "202611/0/" + KEY;
        storage.put(key, new ByteArrayInputStream(new byte[]{1, 2}));
        storage.read(key, input -> assertThat(input.readAllBytes()).containsExactly(1, 2));
        assertThat(documentDirectory.resolve(key)).hasBinaryContent(new byte[]{1, 2});
        storage.delete(key);
        assertThat(documentDirectory.resolve(key)).doesNotExist();
    }

    @Test
    void concurrentUploadsCreateTheSameMonthAndUploaderDirectories() throws Exception {
        String folder = "202611/42/";
        String first = folder + KEY;
        String second = folder + "3752c76a-33bc-44af-8869-1261ee4ed242";
        Path uploader = documentDirectory.resolve(folder);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var uploads = List.of(first, second).stream().map(key -> executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                storage.put(key, new ByteArrayInputStream(new byte[]{7}));
                return null;
            })).toList();
            start.countDown();
            for (var upload : uploads) {
                upload.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(documentDirectory.resolve(first)).hasBinaryContent(new byte[]{7});
        storage.read(first, input -> assertThat(input.readAllBytes()).containsExactly(7));
        storage.delete(first);
        storage.delete(first);
        assertThat(documentDirectory.resolve(first)).doesNotExist();
        assertThat(documentDirectory.resolve(second)).hasBinaryContent(new byte[]{7});
        assertThat(uploader).isDirectory();
    }

    @Test
    void rejectsAnUploadWhenItsMonthDirectoryIsAFile() throws IOException {
        Path occupied = Files.writeString(documentDirectory.resolve("202611"), "existing file");
        String nested = "202611/42/" + KEY;
        var source = InputStream.nullInputStream();

        var failure = assertThrows(IOException.class, () -> storage.put(nested, source));

        assertThat(failure.getCause()).hasMessageContaining("is not a directory");
        assertThat(occupied).hasContent("existing file");
    }

    @Test
    void deleteRemovesPublishedAndStagedFilesAndCanBeRetried() throws IOException {
        Path target = Files.createFile(documentDirectory.resolve(KEY));
        Path staged = Files.createFile(documentDirectory.resolve(KEY + ".part"));

        storage.delete(KEY);
        storage.delete(KEY);

        assertThat(target).doesNotExist();
        assertThat(staged).doesNotExist();
    }

    @Test
    void reusesTheSameSftpConnectionForSequentialOperations() throws IOException {
        storage.put(KEY, InputStream.nullInputStream());
        storage.read(KEY, input -> assertThat(input.read()).isEqualTo(-1));
        storage.delete(KEY);

        assertThat(sessions).hasValue(1);
    }

    @Test
    void reusesConnectionAfterDownloadOutputFailsButDiscardsItOnRemoteFailure() throws IOException {
        storage.put(KEY, new ByteArrayInputStream(new byte[]{7}));
        var closedClient = new DownloadOutputException(new IOException("client disconnected"));
        DocumentStorage.Reader brokenOutput = input -> {
            assertThat(input.read()).isEqualTo(7);
            throw closedClient;
        };
        assertThat(assertThrows(IOException.class, () -> storage.read(KEY, brokenOutput))).isSameAs(closedClient);
        storage.read(KEY, input -> assertThat(input.read()).isEqualTo(7));
        assertThat(sessions).hasValue(1);

        Files.delete(documentDirectory.resolve(KEY));
        DocumentStorage.Reader reader = InputStream::read;
        assertThrows(IOException.class, () -> storage.read(KEY, reader));
        storage.put(KEY, InputStream.nullInputStream());
        assertThat(sessions).hasValue(2);
    }

    @Test
    void requiresKnownHostsBeforeConnecting() {
        properties.setKnownHosts(null);
        var source = InputStream.nullInputStream();

        var failure = assertThrows(IOException.class, () -> storage.put(KEY, source));

        assertThat(failure.getCause()).hasMessageContaining("document.sftp.known-hosts");
        assertThat(sessions).hasValue(0);
    }

    @Test
    void missingRemoteDirectoryReportsUploadStageAndSftpStatus() {
        properties.setRoot("flow/missing");
        var source = InputStream.nullInputStream();

        var failure = assertThrows(IOException.class, () -> storage.put(KEY, source));

        assertThat(failure).hasMessageContaining("SFTP upload failed").hasMessageContaining("root=flow/missing");
        assertThat(failure.getCause()).isInstanceOfSatisfying(SftpException.class,
                cause -> assertThat(cause.getStatus()).isEqualTo(SftpConstants.SSH_FX_NO_SUCH_FILE));
    }

    @Test
    void rejectedPrivateKeyReportsAuthenticationStage() {
        server.setPublickeyAuthenticator((username, key, session) -> false);
        var source = InputStream.nullInputStream();

        var failure = assertThrows(IOException.class, () -> storage.put(KEY, source));

        assertThat(failure).hasMessageContaining("SFTP SSH authentication failed").hasCauseInstanceOf(IOException.class);
        assertThat(documentDirectory.resolve(KEY)).doesNotExist();
    }

    @Test
    void configuredButMissingKnownHostsStillFails() {
        properties.setKnownHosts(tempDir.resolve("missing-known-hosts"));

        assertThrows(IOException.class, () -> storage.put(KEY, InputStream.nullInputStream()));

        assertThat(sessions).hasValue(0);
    }

    @Test
    void replacesAConnectionClosedByTheServer() throws IOException {
        storage.put(KEY, InputStream.nullInputStream());
        var originalSession = server.getActiveSessions().getFirst();
        originalSession.close(true);
        await().atMost(Duration.ofSeconds(5)).until(server.getActiveSessions()::isEmpty);

        storage.read(KEY, input -> assertThat(input.read()).isEqualTo(-1));

        assertThat(sessions).hasValue(2);
    }

    @Test
    void boundsConcurrentConnectionsAndTimesOutWhenAllAreInUse() throws Exception {
        properties.setPoolMaxTotal(1);
        properties.setPoolMaxWait(Duration.ofMillis(200));
        storage.close();
        storage = new SftpDocumentStorage(properties);
        storage.put(KEY, InputStream.nullInputStream());

        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer = executor.submit(() -> {
                storage.read(KEY, input -> {
                    borrowed.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to finish the test download");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                });
                return null;
            });
            try {
                assertThat(borrowed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThrows(IOException.class, () -> storage.delete(KEY));
                assertThat(sessions).hasValue(1);
            } finally {
                release.countDown();
            }
            transfer.get(5, TimeUnit.SECONDS);
        }
        storage.delete(KEY);
        assertThat(sessions).hasValue(1);
    }

    @Test
    void interruptedUploadNeverPublishesPartialFile() throws IOException {
        InputStream failsAfterFirstChunk = new SequenceInputStream(new ByteArrayInputStream(new byte[64 * 1024]),
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        assertThat(documentDirectory.resolve(KEY)).doesNotExist();
                        throw new IOException("interrupted source");
                    }
                });

        assertThrows(IOException.class, () -> storage.put(KEY, failsAfterFirstChunk));

        assertThat(documentDirectory.resolve(KEY)).doesNotExist();
        storage.delete(KEY);
        assertThat(documentDirectory.resolve(KEY + ".part")).doesNotExist();
    }

    @Test
    void refusesToOverwriteAnExistingObject() throws IOException {
        byte[] original = {1, 2, 3};
        Path target = Files.write(documentDirectory.resolve(KEY), original);
        InputStream replacement = new ByteArrayInputStream(new byte[]{4, 5, 6});

        assertThrows(IOException.class, () -> storage.put(KEY, replacement));

        assertThat(target).hasBinaryContent(original);
    }

    @Test
    void rejectsUntrustedHostWithoutWritingKnownHostsOrFiles() throws IOException {
        Files.writeString(properties.getKnownHosts(), "");
        InputStream source = InputStream.nullInputStream();

        assertThrows(IOException.class, () -> storage.put(KEY, source));

        assertThat(properties.getKnownHosts()).isEmptyFile();
        assertThat(documentDirectory.resolve(KEY)).doesNotExist();
        assertThat(documentDirectory.resolve(KEY + ".part")).doesNotExist();
    }

    @Test
    void rejectsChangedHostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        writeKnownHost(properties.getKnownHosts(), generator.generateKeyPair());
        InputStream source = InputStream.nullInputStream();

        assertThrows(IOException.class, () -> storage.put(KEY, source));

        assertThat(documentDirectory.resolve(KEY)).doesNotExist();
    }

    @Test
    void rejectsStorageKeyAndRootTraversal() throws IOException {
        Path outside = Files.createFile(documentDirectory.getParent().resolve("outside"));

        assertThrows(IOException.class, () -> storage.delete("../outside"));
        String invalidNested = "202611/../" + KEY;
        assertThrows(IOException.class, () -> storage.delete(invalidNested));
        properties.setRoot("flow/documents/..");
        assertThrows(IOException.class, () -> storage.delete(KEY));

        assertThat(outside).exists();
    }

    private void writeKnownHost(Path path, KeyPair hostKey) throws IOException {
        Files.writeString(path, "[" + HOST + "]:" + server.getPort() + " "
                + PublicKeyEntry.toString(hostKey.getPublic()) + "\n");
    }
}
