package pl.commercelink.receipts.fakturownia;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneShotHttpGetTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final char[] PASSWORD = "changeit".toCharArray();

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final OneShotHttpGet client = new OneShotHttpGet((SSLSocketFactory) SSLSocketFactory.getDefault(), TIMEOUT, TIMEOUT);

    @AfterEach
    void closeResources() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    /** A raw TCP server that answers every connection with {@code answer} (null = close without answering). */
    private int rawServer(String answer, AtomicInteger connections, List<String> requests) throws IOException {
        ServerSocket server = new ServerSocket(0);
        resources.add(server);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    connections.incrementAndGet();
                    requests.add(readHead(socket.getInputStream()));
                    if (answer != null) {
                        OutputStream out = socket.getOutputStream();
                        out.write(answer.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // server closed
                }
            }
        });
        return server.getLocalPort();
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.append((char) b);
        }
        return head.toString();
    }

    @Test
    void sendsOneGetWithConnectionCloseAndHeaders() throws IOException {
        // given
        List<String> requests = new CopyOnWriteArrayList<>();
        int port = rawServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK", new AtomicInteger(), requests);

        // when
        OneShotHttpGet.Response response = client.get(URI.create("http://127.0.0.1:" + port + "/invoices/fiscal_print?id=1&mode=e-receipt"),
                Map.of("Authorization", "Bearer k"));

        // then
        assertEquals(200, response.status());
        assertEquals("OK", response.body());
        String head = requests.getFirst();
        assertTrue(head.startsWith("GET /invoices/fiscal_print?id=1&mode=e-receipt HTTP/1.1\r\n"), head);
        assertTrue(head.contains("Host: 127.0.0.1:" + port + "\r\n"), head);
        assertTrue(head.contains("Connection: close\r\n"), head);
        assertTrue(head.contains("Authorization: Bearer k\r\n"), head);
    }

    @Test
    void closedConnectionWithoutAnswerIsSentNoAnswerAndNeverResent() throws IOException {
        // given
        AtomicInteger connections = new AtomicInteger();
        int port = rawServer(null, connections, new CopyOnWriteArrayList<>());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
        assertEquals(1, connections.get(), "the request must be sent exactly once");
    }

    @Test
    void readsChunkedBody() throws IOException {
        // given
        int port = rawServer("HTTP/1.1 422 Unprocessable Entity\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHello\r\n6;ext=1\r\n World\r\n0\r\n\r\n",
                new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        OneShotHttpGet.Response response = client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of());

        // then
        assertEquals(422, response.status());
        assertEquals("Hello World", response.body());
    }

    @Test
    void readsBodyUntilCloseWithoutLength() throws IOException {
        // given
        int port = rawServer("HTTP/1.1 200 OK\r\n\r\nqueued", new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        OneShotHttpGet.Response response = client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of());

        // then
        assertEquals("queued", response.body());
    }

    @Test
    void malformedStatusLineIsSentNoAnswer() throws IOException {
        // given
        int port = rawServer("garbage\r\n\r\n", new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void slowServerIsSentNoAnswer() throws IOException {
        // given
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        resources.add(() -> server.stop(0));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void refusedConnectionIsNotSent() throws IOException {
        // given
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + closedPort + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.NOT_SENT, failure.kind());
    }

    @Test
    void truncatedContentLengthBodyIsSentNoAnswer() throws IOException {
        // given
        int port = rawServer("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void malformedContentLengthIsSentNoAnswer() throws IOException {
        // given
        int port = rawServer("HTTP/1.1 200 OK\r\nContent-Length: ten\r\n\r\nOK", new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void malformedChunkSizeIsSentNoAnswer() throws IOException {
        // given
        int port = rawServer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nhello\r\n0\r\n\r\n", new AtomicInteger(), new CopyOnWriteArrayList<>());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> client.get(URI.create("http://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    // ---- TLS ---------------------------------------------------------------------------------------------

    /** Self-signed certificate for {@code localhost} only, generated with the JDK's keytool. */
    private static KeyStore localhostKeyStore(Path dir) throws Exception {
        Path file = dir.resolve("localhost.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    private int httpsServer(KeyStore store) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = "secure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        resources.add(() -> server.stop(0));
        return server.getAddress().getPort();
    }

    private static SSLSocketFactory trusting(KeyStore store) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    @Test
    void httpsVerifiesTheCertificateForTheHostName(@TempDir Path dir) throws Exception {
        // given
        KeyStore store = localhostKeyStore(dir);
        int port = httpsServer(store);
        OneShotHttpGet trustingClient = new OneShotHttpGet(trusting(store), TIMEOUT, Duration.ofSeconds(5));

        // when
        OneShotHttpGet.Response response = trustingClient.get(URI.create("https://localhost:" + port + "/x"), Map.of());

        // then
        assertEquals(200, response.status());
        assertEquals("secure", response.body());
    }

    @Test
    void httpsWithCertificateForAnotherHostIsNotSent(@TempDir Path dir) throws Exception {
        // given: the certificate names localhost, the URL names 127.0.0.1
        KeyStore store = localhostKeyStore(dir);
        int port = httpsServer(store);
        OneShotHttpGet trustingClient = new OneShotHttpGet(trusting(store), TIMEOUT, Duration.ofSeconds(5));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class,
                () -> trustingClient.get(URI.create("https://127.0.0.1:" + port + "/x"), Map.of()));

        // then
        assertEquals(FakturowniaApiException.Kind.NOT_SENT, failure.kind());
    }
}
