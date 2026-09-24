package pl.commercelink.receipts.fakturownia;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Exactly one HTTP/1.1 GET on a fresh connection, never resent. Used for Fakturownia's side-effecting
 * {@code fiscal_print}: both JDK HTTP clients silently resend a GET when the server closes the connection
 * before answering ({@code java.net.http} via {@code ConnectionExpiredException}, {@code HttpURLConnection}
 * via its one-time retry), which here would fiscalise the sale twice. HTTPS uses the given socket factory with
 * host name verification.
 *
 * <p>The response is read as: a status line ({@code 1xx} is skipped and the next status line read instead),
 * then headers (skipped — never inspected), then the body read until the connection closes, capped at
 * {@link #MAX_BODY_BYTES}. {@code Content-Length} and chunked framing are both ignored: the request always
 * sends {@code Connection: close}, so reading to EOF is correct either way and does not need to parse either
 * framing.
 */
final class OneShotHttpGet {

    record Response(int status, String body) {}

    /** Guards a single line (status or header) against a connection that never sends a CRLF. */
    private static final int MAX_LINE_BYTES = 64 * 1024;
    /** The body is capped, not failed, past this size: nothing this class reads is meant to be this large. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final SSLSocketFactory tls;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    OneShotHttpGet(SSLSocketFactory tls, Duration connectTimeout, Duration readTimeout) {
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Sends the request and reads the response. Throws {@link FakturowniaApiException} of kind {@code NOT_SENT}
     * when no request byte left this JVM (connect or TLS handshake failed) and {@code SENT_NO_ANSWER} when
     * writing failed, or reading failed before any status line came back. Once a status line is read, the
     * request has had its one and only effect and is never retried: any further read failure (headers or body)
     * is not turned into an exception — that status is returned with an empty body instead.
     */
    Response get(URI uri, Map<String, String> headers) {
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
                socket.setSoTimeout((int) readTimeout.toMillis());
                if (https) {
                    SSLSocket ssl = (SSLSocket) tls.createSocket(socket, host, port, true);
                    SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(parameters);
                    ssl.startHandshake();
                    socket = ssl;
                }
            } catch (IOException e) {
                throw FakturowniaApiException.notSent(e);
            }
            try {
                OutputStream out = socket.getOutputStream();
                out.write(requestHead(uri, host, port, https, headers).getBytes(StandardCharsets.UTF_8));
                out.flush();
                return readResponse(new BufferedInputStream(socket.getInputStream()));
            } catch (IOException e) {
                throw FakturowniaApiException.sentNoAnswer("No answer from Fakturownia: " + e, e);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing left to do with this connection
            }
        }
    }

    private static String requestHead(URI uri, String host, int port, boolean https, Map<String, String> headers) {
        String target = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        boolean defaultPort = port == (https ? 443 : 80);
        StringBuilder head = new StringBuilder()
                .append("GET ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n")
                .append("Connection: close\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        return head.append("\r\n").toString();
    }

    /**
     * Reads the status line (skipping any {@code 1xx} and its headers), then the rest of the response. Only a
     * failure while reading a status line propagates as {@link IOException} (translated by {@link #get} into
     * {@code SENT_NO_ANSWER}); once one is read, the caller must get that status back, never an exception.
     */
    private static Response readResponse(InputStream in) throws IOException {
        int status = readStatusLine(in);
        while (status / 100 == 1) {
            skipHeaders(in);
            status = readStatusLine(in);
        }
        try {
            skipHeaders(in);
            return new Response(status, new String(readBody(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Response(status, "");
        }
    }

    private static int readStatusLine(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("Connection closed before any response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Malformed status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed status line: " + statusLine, e);
        }
    }

    /** Headers are never inspected — framing is fixed, see the class comment — so they are only skipped over. */
    private static void skipHeaders(InputStream in) throws IOException {
        for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
            // intentionally discarded
        }
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (body.size() < MAX_BODY_BYTES
                && (read = in.read(buffer, 0, Math.min(buffer.length, MAX_BODY_BYTES - body.size()))) != -1) {
            body.write(buffer, 0, read);
        }
        return body.toByteArray();
    }

    /** One CRLF- (or LF-) terminated line in ISO-8859-1, or null at end of stream before any byte. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int end = line.length();
                return end > 0 && line.charAt(end - 1) == '\r' ? line.substring(0, end - 1) : line.toString();
            }
            line.append((char) b);
            if (line.length() > MAX_LINE_BYTES) {
                throw new IOException("Line too long");
            }
        }
        return line.isEmpty() ? null : line.toString();
    }
}
