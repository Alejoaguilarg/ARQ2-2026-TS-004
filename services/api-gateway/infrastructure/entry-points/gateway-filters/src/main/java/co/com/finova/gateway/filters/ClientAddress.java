package co.com.finova.gateway.filters;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// The client a per-IP rate limit counts (spec.md §3.1.2). By default it is the socket address: the Gateway is the
// edge and X-Forwarded-For would be client-controlled. Behind the web frontend's nginx every request would come from
// nginx, so all users would share one login limit. A request from a trusted proxy (TRUSTED_PROXIES, host names
// resolved again every 30 s because container IPs change) is counted by its X-Real-IP, which nginx overwrites
// with the address it saw. Anyone else's X-Real-IP is ignored.
@Component
public class ClientAddress {

    static final String HEADER = "X-Real-IP";
    private static final Duration REFRESH = Duration.ofSeconds(30);
    // An IP literal only: never a host name, which would be resolved.
    private static final Pattern IP = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");
    private static final System.Logger LOG = System.getLogger(ClientAddress.class.getName());

    private final List<String> proxies;
    private final Function<String, Set<String>> resolver;
    private final Clock clock;
    private volatile Set<String> trusted = Set.of();
    private volatile Instant resolvedAt = Instant.MIN;

    @Autowired
    public ClientAddress(@Value("${finova.security.trusted-proxies:}") List<String> proxies) {
        this(proxies, ClientAddress::resolve, Clock.systemUTC());
    }

    ClientAddress(List<String> proxies, Function<String, Set<String>> resolver, Clock clock) {
        this.proxies = proxies.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
        this.resolver = resolver;
        this.clock = clock;
    }

    public String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader(HEADER);
        if (forwarded == null || proxies.isEmpty() || !IP.matcher(forwarded.trim()).matches()
                || !trustedProxies().contains(peer)) {
            return peer;
        }
        return forwarded.trim();
    }

    private Set<String> trustedProxies() {
        Instant now = clock.instant();
        if (resolvedAt.plus(REFRESH).isBefore(now)) {
            trusted = proxies.stream().flatMap(proxy -> resolver.apply(proxy).stream()).collect(Collectors.toUnmodifiableSet());
            resolvedAt = now;
        }
        return trusted;
    }

    private static Set<String> resolve(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (UnknownHostException e) {
            // The proxy is not up yet: trust nobody until the next refresh.
            LOG.log(System.Logger.Level.DEBUG, "Trusted proxy " + host + " does not resolve");
            return Set.of();
        }
    }
}
