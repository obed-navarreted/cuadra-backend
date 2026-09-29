package com.cuadra.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Tope al cuerpo de una petición (Tomcat no lo pone para JSON): con `Content-Length` se rechaza de una vez; sin él (chunked) se corta al pasarse. */
public class RequestSizeFilter extends OncePerRequestFilter {
    private final long maxBytes;

    public RequestSizeFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                ServletInputStream in = super.getInputStream();
                return new ServletInputStream() {
                    private long read;

                    @Override public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0 && ++read > maxBytes) throw new IOException("Request body too large");
                        return b;
                    }

                    @Override public int read(byte[] buf, int off, int len) throws IOException {
                        int n = in.read(buf, off, len);
                        if (n > 0 && (read += n) > maxBytes) throw new IOException("Request body too large");
                        return n;
                    }

                    @Override public boolean isFinished() { return in.isFinished(); }
                    @Override public boolean isReady() { return in.isReady(); }
                    @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
                };
            }
        }, response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\"}");
    }
}
