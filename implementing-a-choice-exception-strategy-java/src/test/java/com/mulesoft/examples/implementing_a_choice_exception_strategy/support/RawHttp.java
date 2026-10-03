package com.mulesoft.examples.implementing_a_choice_exception_strategy.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP/1.1 client over a plain {@link Socket}, used by the Spring tests of this project.
 *
 * <p>{@link #send} writes one request to {@code localhost:<port>}, reads the reply from the socket
 * as raw bytes and returns it as a {@link Response}. The response keeps the status line, the reason
 * phrase and the header block exactly as received, and a header that the server did not send is
 * reported as absent. See D-010, D-066.
 *
 * <p>Usage:
 * <pre>{@code
 * Map<String, String> headers = new LinkedHashMap<>();
 * headers.put("Content-Type", "application/json");
 * RawHttp.Response response = RawHttp.send(port, "POST", "/", headers, json);
 * assertEquals("HTTP/1.1 400 Missing input data", response.statusLine());
 * assertTrue(response.header("Content-Type").isEmpty());
 * }</pre>
 */
public final class RawHttp {

    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String CRLF = "\r\n";
    private static final int HEADER_TERMINATOR = 0x0D0A0D0A;
    private static final int HEADER_TERMINATOR_LENGTH = 4;
    private static final int CR = '\r';
    private static final int LF = '\n';

    private RawHttp() {
    }

    /**
     * Sends one HTTP/1.1 request to {@code localhost:<port>} and reads the complete reply.
     *
     * <p>The request head is one ISO-8859-1 string whose lines each end in CRLF, in this order:
     * <ol>
     *   <li>{@code <method> <pathAndQuery> HTTP/1.1}, with {@code pathAndQuery} written unchanged;</li>
     *   <li>{@code Host: localhost:<port>};</li>
     *   <li>each entry of {@code headers} as {@code <name>: <value>}, in the map's iteration order,
     *       leaving out every name equal, ignoring case, to {@code Host}, {@code Content-Length} or
     *       {@code Connection};</li>
     *   <li>{@code Content-Length: <body length>};</li>
     *   <li>{@code Connection: close};</li>
     *   <li>an empty line.</li>
     * </ol>
     * The body bytes follow the head unchanged, and the output is flushed without being shut down.
     * The socket read timeout is 10 seconds. The reply is parsed as {@link Response} describes. A reply
     * to a {@code HEAD} request, matched case-sensitively, has an empty body and is not read past its
     * header block, whatever its {@code Content-Length} or {@code Transfer-Encoding}. See D-340.
     *
     * @param port         the local port of the server
     * @param method       the request method, written as given
     * @param pathAndQuery the request target, already encoded by the caller and written as given
     * @param headers      request headers in sending order; {@code null} sends none
     * @param body         request body bytes; {@code null} sends an empty body
     * @return the parsed reply
     * @throws NullPointerException when {@code method}, {@code pathAndQuery}, a header name or a
     *                              header value is {@code null}
     * @throws EOFException         when the connection closes before the header block or, for a
     *                              method other than {@code HEAD}, the framed body is complete
     * @throws IOException          when the connection fails, the read times out, or the status
     *                              line, {@code Content-Length} or a chunk size is malformed
     */
    public static Response send(int port, String method, String pathAndQuery, Map<String, String> headers,
            byte[] body) throws IOException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        Map<String, String> requestHeaders = headers == null ? Collections.emptyMap() : headers;
        byte[] payload = body == null ? new byte[0] : body;
        byte[] head = requestHead(port, method, pathAndQuery, requestHeaders, payload.length)
                .getBytes(StandardCharsets.ISO_8859_1);

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(head);
            out.write(payload);
            out.flush();
            return readResponse(new BufferedInputStream(socket.getInputStream()), method);
        }
    }

    /** Request line, {@code Host}, caller headers, {@code Content-Length}, {@code Connection} and the empty line. */
    private static String requestHead(int port, String method, String pathAndQuery, Map<String, String> headers,
            int contentLength) {
        StringBuilder head = new StringBuilder(128);
        head.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1").append(CRLF);
        head.append("Host: localhost:").append(port).append(CRLF);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = Objects.requireNonNull(header.getKey(), "header name");
            if (isWrittenBySend(name)) {
                continue;
            }
            String value = Objects.requireNonNull(header.getValue(), "header value");
            head.append(name).append(": ").append(value).append(CRLF);
        }
        head.append("Content-Length: ").append(contentLength).append(CRLF);
        head.append("Connection: close").append(CRLF);
        head.append(CRLF);
        return head.toString();
    }

    /** True for {@code Host}, {@code Content-Length} and {@code Connection}, ignoring case. */
    private static boolean isWrittenBySend(String name) {
        return name.equalsIgnoreCase("Host")
                || name.equalsIgnoreCase("Content-Length")
                || name.equalsIgnoreCase("Connection");
    }

    /**
     * Header block, then an empty body for {@code HEAD}, otherwise the body framed by
     * {@code Transfer-Encoding}, {@code Content-Length} or EOF.
     */
    private static Response readResponse(InputStream in, String method) throws IOException {
        String block = readHeaderBlock(in);
        int statusLineEnd = block.indexOf(CRLF);
        String statusLine = statusLineEnd < 0 ? block : block.substring(0, statusLineEnd);
        String rawHeaders = statusLineEnd < 0 ? "" : block.substring(statusLineEnd + CRLF.length());
        Map<String, String> headers = parseHeaders(rawHeaders);
        int statusCode = statusCode(statusLine);
        byte[] body = "HEAD".equals(method) ? new byte[0] : readBody(in, headers);
        return new Response(statusLine, statusCode, reasonPhrase(statusLine), rawHeaders, headers, body);
    }

    /**
     * Reads byte by byte up to and including the first CRLF CRLF and returns the bytes before it,
     * decoded as ISO-8859-1.
     */
    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream(512);
        int lastFour = 0;
        int count = 0;
        while (count < HEADER_TERMINATOR_LENGTH || lastFour != HEADER_TERMINATOR) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("Connection closed before end of response headers");
            }
            block.write(next);
            lastFour = (lastFour << 8) | next;
            count++;
        }
        byte[] bytes = block.toByteArray();
        return new String(bytes, 0, bytes.length - HEADER_TERMINATOR_LENGTH, StandardCharsets.ISO_8859_1);
    }

    /** The text between the first and second space, or after the first space when there is no second. */
    private static int statusCode(String statusLine) throws IOException {
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("Malformed status line: " + statusLine);
        }
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0
                ? statusLine.substring(firstSpace + 1)
                : statusLine.substring(firstSpace + 1, secondSpace);
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed status code in status line: " + statusLine, e);
        }
    }

    /** Everything after the second space, unchanged; empty when there is no second space. */
    private static String reasonPhrase(String statusLine) {
        int firstSpace = statusLine.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : statusLine.indexOf(' ', firstSpace + 1);
        return secondSpace < 0 ? "" : statusLine.substring(secondSpace + 1);
    }

    /**
     * Lower-cased ({@link Locale#ROOT}) header name to the first trimmed value received for it, in
     * arrival order. Lines without {@code :} are skipped. The map is unmodifiable.
     */
    private static Map<String, String> parseHeaders(String rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!rawHeaders.isEmpty()) {
            for (String line : rawHeaders.split(CRLF, -1)) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                headers.putIfAbsent(name, line.substring(colon + 1).trim());
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * De-chunked when {@code transfer-encoding} contains {@code chunked}, ignoring case; otherwise exactly
     * {@code content-length} bytes when that header is present; otherwise every byte up to EOF.
     */
    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            return readExactly(in, contentLength(contentLength), "body");
        }
        return in.readAllBytes();
    }

    /** The trimmed decimal {@code Content-Length} value; negative or non-numeric values are rejected. */
    private static int contentLength(String value) throws IOException {
        int length;
        try {
            length = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed Content-Length: " + value, e);
        }
        if (length < 0) {
            throw new IOException("Malformed Content-Length: " + value);
        }
        return length;
    }

    /**
     * De-chunks the body: each size line is read up to CRLF, its text before any {@code ;} is trimmed
     * and parsed as hex, that many bytes are read and the CRLF after them is consumed. Reading stops
     * at size 0; trailers are not read.
     */
    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int size = chunkSize(readLine(in));
            if (size == 0) {
                return body.toByteArray();
            }
            body.write(readExactly(in, size, "chunk"));
            if (!readLine(in).isEmpty()) {
                throw new IOException("Chunk of " + size + " bytes is not followed by CRLF");
            }
        }
    }

    /** The hex size of a chunk-size line, ignoring any extension after {@code ;}. */
    private static int chunkSize(String line) throws IOException {
        int extension = line.indexOf(';');
        String hex = (extension < 0 ? line : line.substring(0, extension)).trim();
        int size;
        try {
            size = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed chunk size line: " + line, e);
        }
        if (size < 0) {
            throw new IOException("Malformed chunk size line: " + line);
        }
        return size;
    }

    /** Bytes up to the next CRLF, without it, decoded as ISO-8859-1. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(16);
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("Connection closed before end of chunk line");
            }
            if (previous == CR && next == LF) {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
            previous = next;
        }
    }

    /** Exactly {@code length} bytes; an earlier end of stream raises {@link EOFException}. */
    private static byte[] readExactly(InputStream in, int length, String part) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException(
                    "Connection closed after " + bytes.length + " of " + length + " " + part + " bytes");
        }
        return bytes;
    }

    /**
     * One HTTP/1.1 reply as read from the socket.
     *
     * @param statusLine   the first line of the reply without its CRLF, for example
     *                     {@code HTTP/1.1 400 Missing input data}
     * @param statusCode   the numeric status code of the status line
     * @param reasonPhrase everything after the second space of the status line, unchanged; empty when
     *                     the server sent none
     * @param rawHeaders   the header lines exactly as received, joined by CRLF, without the status line
     *                     and without a trailing CRLF; empty when there are no header lines
     * @param headers      unmodifiable map of lower-cased header name to the first trimmed value
     *                     received, in arrival order; repeated headers appear in full only in
     *                     {@code rawHeaders}
     * @param body         the de-framed body bytes; zero length when the reply has no body or answers a
     *                     {@code HEAD} request
     */
    public record Response(String statusLine, int statusCode, String reasonPhrase, String rawHeaders,
            Map<String, String> headers, byte[] body) {

        /** Rejects {@code null} components. */
        public Response {
            Objects.requireNonNull(statusLine, "statusLine");
            Objects.requireNonNull(reasonPhrase, "reasonPhrase");
            Objects.requireNonNull(rawHeaders, "rawHeaders");
            Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(body, "body");
        }

        /**
         * The first value of the named header, matched ignoring case.
         *
         * @param name the header name in any case, for example {@code Content-Type}
         * @return the trimmed value, or empty when the reply has no such header
         */
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }

        /**
         * The body decoded as UTF-8.
         *
         * @return the body text
         */
        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
