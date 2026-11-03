package co.com.finova.gateway.filters;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// spec.md §3.1 step 5: services trust X-User-Id, X-Device-Id and X-User-Roles, so whatever the
// client sent is dropped and only values from a verified token are forwarded.
final class IdentityHeadersRequest extends HttpServletRequestWrapper {

    static final Set<String> IDENTITY_HEADERS = Set.of("x-user-id", "x-device-id", "x-user-roles");

    private final Map<String, String> overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    IdentityHeadersRequest(HttpServletRequest request, Map<String, String> headers) {
        super(request);
        overrides.putAll(headers);
    }

    @Override
    public String getHeader(String name) {
        if (overrides.containsKey(name)) {
            return overrides.get(name);
        }
        return isIdentity(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (overrides.containsKey(name)) {
            return Collections.enumeration(Set.of(overrides.get(name)));
        }
        return isIdentity(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : Collections.list(super.getHeaderNames())) {
            if (!isIdentity(name) && !overrides.containsKey(name)) {
                names.add(name);
            }
        }
        names.addAll(overrides.keySet());
        return Collections.enumeration(names);
    }

    private static boolean isIdentity(String name) {
        return IDENTITY_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
