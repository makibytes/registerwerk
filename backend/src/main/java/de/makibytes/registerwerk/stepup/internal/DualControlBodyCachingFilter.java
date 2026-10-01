package de.makibytes.registerwerk.stepup.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Makes the raw request body readable twice for requests that carry an {@code X-Dual-Control-Token}:
 * the approval of a body-bound reason (mint, burn, forced transfer) covers the canonical JSON body, so
 * the enforcement code needs the bytes before the controller's {@code @RequestBody} consumes the stream.
 * Runs after the security filter chain (unauthenticated callers are rejected before anything is
 * buffered) and only for body-carrying methods; bodies above the configured cap are not buffered and
 * a body-bound approval for them fails closed.
 */
@Component
class DualControlBodyCachingFilter extends OncePerRequestFilter {

    static final String CACHED_BODY_ATTRIBUTE = "stepup.cachedRequestBody";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final DualControlProperties properties;

    DualControlBodyCachingFilter(DualControlProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(DualControlApproverInterceptor.DUAL_CONTROL_HEADER) == null
                || !BODY_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > properties.getMaxBodyBytes()) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = readBounded(request.getInputStream(), properties.getMaxBodyBytes());
        if (body == null) {
            // Over the cap with chunked encoding: the stream is partly consumed, so refuse outright.
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            Charset cs = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), cs));
        }
    }
}
