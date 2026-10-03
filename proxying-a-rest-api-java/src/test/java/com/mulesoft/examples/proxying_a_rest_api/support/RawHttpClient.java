package com.mulesoft.examples.proxying_a_rest_api.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-scope HTTP/1.1 client that writes request bytes exactly as given and reads response bytes as
 * received, over one new socket per call. See D-059.
 *
 * <p><b>Request.</b> {@link #send} writes the request line {@code <method> <target> HTTP/1.1}, then every
 * header pair as {@code name: value} in list order with duplicates kept, then an empty line, all encoded
 * as ISO-8859-1. The target, names and values are written verbatim. No header is added: {@code Host},
 * {@code Content-Length}, {@code Transfer-Encoding}, {@code Connection}, {@code User-Agent} and
 * {@code Accept} are sent only when the caller lists them. The body follows either unchanged or in
 * chunked framing with at most 8192 bytes per chunk.
 *
 * <p><b>Response.</b> The head block is read up to the first CRLF CRLF. Interim {@code 100} heads are
 * discarded and the next head is read. The body of the final response is selected in this order:
 * <ol>
 *   <li>none for a {@code HEAD} request or a {@code 1xx}, {@code 204} or {@code 304} status;</li>
 *   <li>exactly {@code Content-Length} bytes when that header is present (first occurrence);</li>
 *   <li>chunk-decoded bytes when a {@code Transfer-Encoding} token is {@code chunked}; chunk extensions and
 *       trailer lines are discarded;</li>
 *   <li>otherwise every byte up to end of stream.</li>
 * </ol>
 *
 * <p>Connect and read timeouts are 30 seconds each. The socket is closed before {@link #send} returns.
 * The class holds no state and is safe for concurrent use.
 *
 * <p>Example:
 * <pre>{@code
 * List<String[]> headers = List.of(
 *         new String[] {"Host", "127.0.0.1:" + port},
 *         new String[] {"Connection", "close"});
 * RawHttpClient.RawResponse response =
 *         RawHttpClient.send("127.0.0.1", port, "GET", "/2.0/folders/0", headers, null, false);
 * String type = response.header("Content-Type"); // null when the header is absent, see D-066
 * }</pre>
 */
public final class RawHttpClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private static final int MAX_CHUNK_SIZE = 8192;

    private static final int CR = '\r';

    private static final int LF = '\n';

    /** The last four bytes of a head block, CR LF CR LF, packed into one int. */
    private static final int HEAD_TERMINATOR = 0x0D0A0D0A;

    /** Largest array length the JVM allocates reliably. */
    private static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8L;

    private RawHttpClient() {
    }

    /**
     * Sends one HTTP/1.1 request over a new socket and reads the final response.
     *
     * <p>Nothing beyond the given method, target, headers and body is written, and the output side of the
     * socket stays open while the response is read.
     *
     * @param host    host name or address to connect to
     * @param port    TCP port to connect to
     * @param method  request method, written verbatim
     * @param target  request target (path and query), written verbatim with no encoding
     * @param headers header pairs {@code {name, value}}, written in list order with duplicates kept
     * @param body    request body; {@code null} means no body
     * @param chunked {@code true} writes the body in chunked framing followed by {@code 0\r\n\r\n};
     *                {@code false} writes the body bytes unchanged
     * @return the final response: status line, status code, raw header text, parsed headers, body bytes
     *         and whether the body arrived chunked
     * @throws NullPointerException     when {@code host}, {@code method}, {@code target} or
     *                                  {@code headers} is {@code null}
     * @throws IllegalArgumentException when a header pair is {@code null}, does not have exactly two
     *                                  elements, or holds a {@code null} element; the message names its
     *                                  index
     * @throws EOFException             when the stream ends inside a {@code Content-Length} or chunked body
     * @throws IOException              when connecting, writing or reading fails, or the response head or
     *                                  framing is malformed
     */
    public static RawResponse send(String host, int port, String method, String target,
                                   List<String[]> headers, byte[] body, boolean chunked) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(headers, "headers");
        validateHeaderPairs(headers);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            writeHead(out, method, target, headers);
            writeBody(out, body, chunked);
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            return readResponse(in, method);
        }
    }

    /** Checks that every pair is non-null, has length 2 and holds two non-null strings. */
    private static void validateHeaderPairs(List<String[]> headers) {
        for (int i = 0; i < headers.size(); i++) {
            String[] pair = headers.get(i);
            if (pair == null) {
                throw new IllegalArgumentException("Header pair at index " + i + " is null");
            }
            if (pair.length != 2) {
                throw new IllegalArgumentException("Header pair at index " + i + " has " + pair.length
                        + " elements; exactly 2 (name, value) are required");
            }
            if (pair[0] == null || pair[1] == null) {
                throw new IllegalArgumentException("Header pair at index " + i + " has a null name or value");
            }
        }
    }

    /** Writes the request line, each header line in list order and the empty line, as ISO-8859-1. */
    private static void writeHead(OutputStream out, String method, String target, List<String[]> headers)
            throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        for (String[] pair : headers) {
            head.append(pair[0]).append(": ").append(pair[1]).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Writes the body. Chunked: chunks of at most 8192 bytes, each as hex size, CRLF, data, CRLF, then the
     * terminator {@code 0\r\n\r\n}; a {@code null} or empty body writes only the terminator. Not chunked:
     * the bytes unchanged, or nothing for a {@code null} or empty body.
     */
    private static void writeBody(OutputStream out, byte[] body, boolean chunked) throws IOException {
        byte[] data = body == null ? new byte[0] : body;
        if (chunked) {
            for (int offset = 0; offset < data.length; offset += MAX_CHUNK_SIZE) {
                int length = Math.min(MAX_CHUNK_SIZE, data.length - offset);
                out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(data, offset, length);
                out.write(CR);
                out.write(LF);
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        } else if (data.length > 0) {
            out.write(data);
        }
    }

    /** Reads heads until the status is not 100, then reads the body the final head selects. */
    private static RawResponse readResponse(InputStream in, String method) throws IOException {
        RawResponse head = parseHead(readHeadBlock(in));
        while (head.status() == 100) {
            head = parseHead(readHeadBlock(in));
        }

        byte[] body;
        boolean chunked = false;
        String contentLength = head.header("Content-Length");
        if (hasNoBody(method, head.status())) {
            body = new byte[0];
        } else if (contentLength != null) {
            body = readFixedLength(in, parseContentLength(contentLength));
        } else if (isChunked(head)) {
            body = readChunkedBody(in);
            chunked = true;
        } else {
            body = in.readAllBytes();
        }
        return new RawResponse(head.statusLine(), head.status(), head.rawHeaders(), head.headers(), body,
                chunked);
    }

    /** {@code true} for a {@code HEAD} request (any case) or a {@code 1xx}, {@code 204} or {@code 304} status. */
    private static boolean hasNoBody(String method, int status) {
        return "HEAD".equalsIgnoreCase(method)
                || (status >= 100 && status <= 199)
                || status == 204
                || status == 304;
    }

    /** {@code true} when any comma-separated token of any {@code Transfer-Encoding} value is {@code chunked}. */
    private static boolean isChunked(RawResponse head) {
        for (String value : head.headers("Transfer-Encoding")) {
            for (String token : value.split(",")) {
                if (token.trim().equalsIgnoreCase("chunked")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads byte by byte up to and including the first CR LF CR LF.
     *
     * @throws IOException when the stream ends before that sequence
     */
    private static byte[] readHeadBlock(InputStream in) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream(512);
        int lastFour = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Connection closed before the end of the response headers");
            }
            block.write(b);
            lastFour = (lastFour << 8) | b;
            if (lastFour == HEAD_TERMINATOR) {
                return block.toByteArray();
            }
        }
    }

    /**
     * Decodes a head block as ISO-8859-1 into status line, status code, raw header text and header
     * entries. The returned response has an empty body.
     *
     * @throws IOException when the status code token is missing or not numeric, or a header line has no
     *                     {@code :}
     */
    private static RawResponse parseHead(byte[] block) throws IOException {
        String text = new String(block, StandardCharsets.ISO_8859_1);
        int statusLineEnd = text.indexOf("\r\n");
        String statusLine = text.substring(0, statusLineEnd);
        String rawHeaders = text.substring(statusLineEnd + 2, text.length() - 2);

        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed status line, no status code: " + statusLine);
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed status line, non-numeric status code: " + statusLine, e);
        }

        List<Map.Entry<String, String>> headers = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < rawHeaders.length()) {
            int lineEnd = rawHeaders.indexOf("\r\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = rawHeaders.length();
            }
            String line = rawHeaders.substring(lineStart, lineEnd);
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IOException("Malformed response header line, no ':': " + line);
            }
            headers.add(Map.entry(line.substring(0, colon), line.substring(colon + 1).trim()));
            lineStart = lineEnd + 2;
        }
        return new RawResponse(statusLine, status, rawHeaders, headers, new byte[0], false);
    }

    /**
     * Parses a {@code Content-Length} value as a non-negative decimal.
     *
     * @throws IOException when the value is not numeric, is negative or exceeds the largest array length
     */
    private static long parseContentLength(String value) throws IOException {
        long length;
        try {
            length = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length: " + value, e);
        }
        if (length < 0) {
            throw new IOException("Invalid Content-Length: " + value);
        }
        if (length > MAX_ARRAY_LENGTH) {
            throw new IOException("Content-Length exceeds the largest readable body: " + value);
        }
        return length;
    }

    /**
     * Reads exactly {@code length} bytes.
     *
     * @throws EOFException when the stream ends first
     */
    private static byte[] readFixedLength(InputStream in, long length) throws IOException {
        byte[] data = in.readNBytes((int) length);
        if (data.length < length) {
            throw new EOFException("Connection closed after " + data.length + " of " + length
                    + " body bytes");
        }
        return data;
    }

    /**
     * Decodes a chunked body. Each size line has everything from its first {@code ;} removed, is trimmed
     * and is parsed as hexadecimal. A positive size reads that many bytes followed by CR LF. Size 0 reads
     * and discards trailer lines up to the empty line.
     *
     * @throws EOFException when the stream ends anywhere inside the chunked body
     * @throws IOException  when a size is not hexadecimal or negative, the decoded body exceeds the largest
     *                      array length, or chunk data is not followed by CR LF
     */
    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int semicolon = sizeLine.indexOf(';');
            String sizeText = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size line: " + sizeLine, e);
            }
            if (size < 0) {
                throw new IOException("Invalid chunk size line: " + sizeLine);
            }
            if (size == 0) {
                String trailer = readLine(in);
                while (!trailer.isEmpty()) {
                    trailer = readLine(in);
                }
                return body.toByteArray();
            }
            if (size > MAX_ARRAY_LENGTH - body.size()) {
                throw new IOException("Chunked body exceeds the largest readable body at chunk size line: "
                        + sizeLine);
            }
            body.write(readFixedLength(in, size));
            int cr = in.read();
            int lf = in.read();
            if (cr < 0 || lf < 0) {
                throw new EOFException("Connection closed after chunk data, before its CRLF");
            }
            if (cr != CR || lf != LF) {
                throw new IOException("Chunk data of size " + sizeLine.trim() + " is not followed by CRLF");
            }
        }
    }

    /**
     * Reads one line up to CR LF and returns it without the CR LF, decoded as ISO-8859-1.
     *
     * @throws EOFException when the stream ends before CR LF
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connection closed inside the chunked body");
            }
            if (previous == CR && b == LF) {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            previous = b;
        }
    }

    /**
     * One HTTP/1.1 response as read by {@link RawHttpClient#send}.
     *
     * @param statusLine the first line of the final head, without its CRLF, for example
     *                   {@code HTTP/1.1 200 OK} or {@code HTTP/1.1 400 }
     * @param status     the second space-separated token of {@code statusLine}
     * @param rawHeaders the header bytes as received, decoded as ISO-8859-1: every header line with its
     *                   trailing CRLF and without the terminating empty line; {@code ""} when there are no
     *                   headers
     * @param headers    one entry per header line, in order, repeated names kept; the name as received and
     *                   the value trimmed
     * @param body       the body bytes after any chunk decoding; never {@code null}
     * @param chunked    {@code true} when the body was chunk-decoded
     */
    public record RawResponse(String statusLine, int status, String rawHeaders,
                              List<Map.Entry<String, String>> headers, byte[] body, boolean chunked) {

        /**
         * Copies {@code headers} into an unmodifiable list and replaces a {@code null} body with an empty one.
         *
         * @param statusLine the status line without its CRLF
         * @param status     the status code
         * @param rawHeaders the raw header text
         * @param headers    the header entries in received order; must not be {@code null}
         * @param body       the body bytes; {@code null} becomes an empty array
         * @param chunked    whether the body was chunk-decoded
         */
        public RawResponse {
            headers = List.copyOf(headers);
            body = body == null ? new byte[0] : body;
        }

        /**
         * Returns the value of the first header whose name equals {@code name} ignoring case.
         *
         * @param name header name
         * @return the first value, or {@code null} when no such header was received (see D-066)
         */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /**
         * Returns every value of the headers whose name equals {@code name} ignoring case.
         *
         * @param name header name
         * @return the values in received order as an unmodifiable list; empty when no such header was
         *         received
         */
        public List<String> headers(String name) {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> entry : headers) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    values.add(entry.getValue());
                }
            }
            return List.copyOf(values);
        }
    }
}

