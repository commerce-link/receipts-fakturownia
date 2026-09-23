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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Exactly one HTTP/1.1 GET on a fresh connection, never resent. Used for Fakturownia's side-effecting
 * {@code fiscal_print}: both JDK HTTP clients silently resend a GET when the server closes the connection
 * before answering ({@code java.net.http} via {@code ConnectionExpiredException}, {@code HttpURLConnection}
 * via its one-time retry), which here would fiscalise the sale twice. HTTPS uses the given socket factory with
 * host name verification.
 */
final class OneShotHttpGet {

    record Response(int status, String body) {}

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final SSLSocketFactory tls;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    OneShotHttpGet(SSLSocketFactory tls, Duration connectTimeout, Duration readTimeout) {
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Sends the request and reads the whole response. Throws {@link FakturowniaApiException} of kind
     * {@code NOT_SENT} when no request byte left this JVM (connect or TLS handshake failed) and
     * {@code SENT_NO_ANSWER} when writing or reading failed afterwards. Any HTTP status is returned as-is.
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

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("Connection closed before any response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Malformed status line: " + statusLine);
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed status line: " + statusLine, e);
        }
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        int headerBytes = 0;
        for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("Response headers too large");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                responseHeaders.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
        }
        byte[] body;
        if ("chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"))) {
            body = readChunked(in);
        } else if (responseHeaders.containsKey("content-length")) {
            body = in.readNBytes(Integer.parseInt(responseHeaders.get("content-length")));
        } else {
            body = in.readAllBytes();
        }
        return new Response(status, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                throw new IOException("Truncated chunked body");
            }
            int semicolon = sizeLine.indexOf(';');
            int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).strip(), 16);
            if (size == 0) {
                return body.toByteArray();
            }
            body.write(in.readNBytes(size));
            readLine(in);
        }
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
            if (line.length() > MAX_HEADER_BYTES) {
                throw new IOException("Line too long");
            }
        }
        return line.isEmpty() ? null : line.toString();
    }
}
