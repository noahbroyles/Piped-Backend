package me.kavin.piped.utils;

import io.activej.http.*;
import io.activej.promise.Promisable;
import me.kavin.piped.consts.Constants;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static io.activej.http.HttpHeaders.*;

public class CustomServletDecorator implements AsyncServlet {

    private static final HttpHeader HEADER = HttpHeaders.of("Server-Timing");

    // Endpoints that only ever receive a small credentials payload
    private static final Set<String> SMALL_BODY_PATHS = Set.of("/login", "/register", "/logout", "/user/delete");
    private static final int SMALL_BODY_LIMIT = 16 * 1024;

    private final AsyncServlet servlet;

    public CustomServletDecorator(AsyncServlet servlet) {
        this.servlet = servlet;
    }

    @Override
    public @NotNull Promisable<HttpResponse> serve(@NotNull HttpRequest request) throws Exception {
        int limit = SMALL_BODY_PATHS.contains(request.getPath()) ? Math.min(SMALL_BODY_LIMIT, Constants.MAX_BODY_SIZE)
                : Constants.MAX_BODY_SIZE;

        // Reject up front when the declared size is too large, without reading the body
        String contentLength = request.getHeader(CONTENT_LENGTH);
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.trim()) > limit)
                    return HttpResponse.ofCode(413).withHeader(CONNECTION, "close")
                            .withHeader(CONTENT_TYPE, HttpHeaderValue.of("application/json"))
                            .withBody("{\"error\":\"Request body is too large.\"}".getBytes(StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                return HttpResponse.ofCode(400);
            }
        }

        // Also enforced while streaming, which covers chunked bodies with no Content-Length
        request.setMaxBodySize(limit);

        long before = System.nanoTime();
        return servlet.serve(request).promise().map(response -> {

            HttpHeaderValue headerValue = HttpHeaderValue.of("app;dur=" + (System.nanoTime() - before) / 1000000.0);

            return response.withHeader(HEADER, headerValue)
                    .withHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .withHeader(ACCESS_CONTROL_ALLOW_HEADERS, "*, Authorization")
                    .withHeader(ACCESS_CONTROL_ALLOW_METHODS, "*");

        });
    }
}
