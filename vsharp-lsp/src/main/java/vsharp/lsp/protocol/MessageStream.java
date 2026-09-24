package vsharp.lsp.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import vsharp.lsp.json.Json;

/// The base-protocol framing layer of LSP: `Content-Length` headers over a byte stream.
///
/// The header block is ASCII and terminated by a blank line; the payload that follows is
/// exactly `Content-Length` **bytes** of UTF-8. Bytes, not characters - which is why the
/// payload is read with [InputStream#readNBytes] and only then decoded. Wrapping stdin in
/// a `Reader` first would let a multi-byte character straddle the boundary and desynchronise
/// the stream permanently.
///
/// Writes are serialised by a lock because the server answers requests on virtual threads:
/// two concurrent responses must not interleave their headers and bodies.
public final class MessageStream implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MessageStream.class.getName());

    private static final String CONTENT_LENGTH = "content-length:";

    /// Largest payload accepted, 64 MiB. A source buffer that big is already pathological,
    /// and the bound turns a corrupt header into a diagnosable failure rather than an
    /// allocation that kills the server.
    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;

    private final InputStream in;

    private final OutputStream out;

    private final ReentrantLock writeLock = new ReentrantLock();

    /// Wraps a duplex byte stream pair, normally the server's stdin and stdout.
    public MessageStream(InputStream in, OutputStream out) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
    }

    /// Reads the next message, or empty at end of input.
    ///
    /// @throws IOException if the underlying stream fails or the framing is malformed
    public Optional<Json> read() throws IOException {
        int length = -1;
        while (true) {
            String header = readHeaderLine();
            if (header == null) {
                return Optional.empty();
            }
            if (header.isEmpty()) {
                break;
            }
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(CONTENT_LENGTH)) {
                String value = header.substring(CONTENT_LENGTH.length()).trim();
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed Content-Length header: " + value, e);
                }
            }
        }
        if (length < 0) {
            throw new IOException("LSP message has no Content-Length header");
        }
        if (length > MAX_PAYLOAD) {
            throw new IOException("LSP message of " + length + " bytes exceeds the "
                    + MAX_PAYLOAD + " byte limit");
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new IOException("LSP message truncated: expected " + length + " bytes, read "
                    + payload.length);
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        LOG.log(System.Logger.Level.TRACE, "<- {0}", text);
        return Optional.of(Json.parse(text));
    }

    /// Reads one CRLF-terminated header line, or `null` at end of input.
    ///
    /// A lone LF is accepted too: the specification requires CRLF, but tolerating LF costs
    /// nothing and makes the server drivable from a shell script during testing.
    private String readHeaderLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return line.isEmpty() ? null : line.toString();
            }
            if (b == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            line.append((char) b);
            if (line.length() > 8192) {
                throw new IOException("LSP header line exceeds 8192 bytes");
            }
        }
    }

    /// Frames and writes one message, flushing so the client sees it immediately.
    ///
    /// @throws IOException if the underlying stream fails
    public void write(Json message) throws IOException {
        Objects.requireNonNull(message, "message");
        String text = message.text();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        writeLock.lock();
        try {
            out.write(header);
            out.write(payload);
            out.flush();
            LOG.log(System.Logger.Level.TRACE, "-> {0}", text);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }
}
