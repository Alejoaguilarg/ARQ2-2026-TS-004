package co.com.finova.gateway.filters;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

// Lets the proxy read the body again after a filter consumed it.
final class CachedBodyRequest extends HttpServletRequestWrapper {

    // A gateway body is a few hundred bytes; the limit keeps an untrusted client from filling the Gateway's memory.
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    // Reads at most MAX_BODY_BYTES + 1 bytes; a longer result means the body is over the limit.
    static byte[] readLimited(HttpServletRequest request) throws IOException {
        return request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
