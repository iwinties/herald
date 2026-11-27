package io.github.iwinties.herald;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The server side of one HTTP/1.1 exchange: read a request head and body, write one JSON
 * response. Pure Java: no Android classes. PLAN-20261125-herald-v1.
 */
final class Http {
    /** Largest header block, request line through the empty line. */
    static final int MAX_HEAD = 8192;

    private Http() {}

    /** The request cannot be parsed; Api answers 400. */
    static final class BadRequest extends IOException {
        private static final long serialVersionUID = 1L;

        BadRequest(String why) {
            super(why);
        }
    }

    static final class Request {
        final String method;
        final String target;
        final String version;
        /** 0 when absent; Long.MAX_VALUE when too large to represent. */
        final long contentLength;

        final String authorization;

        Request(String method, String target, String version, long contentLength, String authorization) {
            this.method = method;
            this.target = target;
            this.version = version;
            this.contentLength = contentLength;
            this.authorization = authorization;
        }
    }

    /**
     * Reads the head one byte at a time, so nothing after the empty line is consumed. Lines end
     * with CRLF (a bare LF is accepted too).
     */
    static Request readHead(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_HEAD];
        int n = 0;
        while (true) {
            if (n == MAX_HEAD) throw new BadRequest("header block over " + MAX_HEAD + " bytes");
            int c = in.read();
            if (c < 0) throw new BadRequest("stream ended inside the header block");
            buf[n++] = (byte) c;
            if (c == '\n' && headEnds(buf, n)) break;
        }
        String[] lines = new String(buf, 0, n, StandardCharsets.ISO_8859_1).split("\n", -1);
        String[] first = stripCr(lines[0]).split(" ", -1);
        if (first.length != 3 || !token(first[0]) || !first[1].startsWith("/")
                || !printable(first[1]) || !(first[2].equals("HTTP/1.1") || first[2].equals("HTTP/1.0"))) {
            throw new BadRequest("malformed request line");
        }
        long length = -1;
        String authorization = null;
        for (int i = 1; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequest("malformed header line");
            String name = line.substring(0, colon);
            if (!token(name)) throw new BadRequest("malformed header name");
            String valueText = line.substring(colon + 1);
            for (int k = 0; k < valueText.length(); k++) {
                char c = valueText.charAt(k);
                if ((c < ' ' && c != '\t') || c == 127) throw new BadRequest("malformed header value");
            }
            if (name.equalsIgnoreCase("Transfer-Encoding")) throw new BadRequest("unsupported transfer encoding");
            if (name.equalsIgnoreCase("Authorization")) {
                if (authorization != null) throw new BadRequest("duplicate credential header");
                authorization = valueText.trim();
            }
            if (!name.equalsIgnoreCase("Content-Length")) continue;
            long value = contentLength(valueText.trim());
            if (length >= 0 && length != value) throw new BadRequest("conflicting Content-Length");
            length = value;
        }
        return new Request(first[0], first[1], first[2], length < 0 ? 0 : length, authorization);
    }

    /** Reads exactly length bytes; a stream that ends early is a bad request. */
    static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int r = in.read(body, got, length - got);
            if (r < 0) throw new BadRequest("stream ended inside the body");
            got += r;
        }
        return body;
    }

    /** Writes the whole response in one write and flushes. */
    static void write(OutputStream out, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[h.length + body.length];
        System.arraycopy(h, 0, all, 0, h.length);
        System.arraycopy(body, 0, all, h.length, body.length);
        out.write(all);
        out.flush();
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 410: return "Gone";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Content Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    /** True when buf[0..n) ends with an empty line: LF LF or LF CR LF. */
    private static boolean headEnds(byte[] buf, int n) {
        if (n >= 2 && buf[n - 2] == '\n') return true;
        return n >= 3 && buf[n - 2] == '\r' && buf[n - 3] == '\n';
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static boolean token(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') continue;
            if ("!#$%&'*+-.^_`|~".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static boolean printable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c == 127) return false;
        }
        return true;
    }

    /** A non-negative decimal integer, nothing else. */
    private static long contentLength(String value) throws BadRequest {
        if (value.isEmpty()) throw new BadRequest("empty Content-Length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') throw new BadRequest("Content-Length is not a non-negative integer");
        }
        int start = 0;
        while (start < value.length() - 1 && value.charAt(start) == '0') start++;
        String digits = value.substring(start);
        return digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
    }
}
