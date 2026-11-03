package co.com.finova.gateway.filters;

import jakarta.servlet.http.HttpServletRequest;

final class RequestPaths {

    private RequestPaths() {
    }

    // The path the routes match, not the raw URI: "/transfers;x=1", "//transfers" and
    // "/transfers/" all reach Transfer, so every check must see them as "/transfers".
    // The servlet container already removes ;params, resolves dot segments and merges slashes.
    static String of(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
