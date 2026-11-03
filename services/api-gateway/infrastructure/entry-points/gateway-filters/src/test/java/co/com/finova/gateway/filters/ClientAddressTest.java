package co.com.finova.gateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    private static final String NGINX = "172.18.0.9";

    @Test
    void withoutTrustedProxiesTheSocketAddressIsTheClient() {
        ClientAddress clients = new ClientAddress(List.of(), host -> Set.of(NGINX), Clock.systemUTC());

        assertThat(clients.of(request(NGINX, "203.0.113.7"))).isEqualTo(NGINX);
    }

    @Test
    void theTrustedProxyIsCountedByTheAddressItSaw() {
        ClientAddress clients = frontend(Map.of("frontend", Set.of(NGINX)));

        assertThat(clients.of(request(NGINX, " 203.0.113.7 "))).isEqualTo("203.0.113.7");
        assertThat(clients.of(request(NGINX, "2001:db8::1"))).isEqualTo("2001:db8::1");
    }

    @Test
    void anyoneElseCannotChooseTheirAddress() {
        ClientAddress clients = frontend(Map.of("frontend", Set.of(NGINX)));

        assertThat(clients.of(request("10.0.0.5", "203.0.113.7"))).isEqualTo("10.0.0.5");
    }

    @Test
    void theProxyWithoutTheHeaderOrWithANonAddressIsItself() {
        ClientAddress clients = frontend(Map.of("frontend", Set.of(NGINX)));

        assertThat(clients.of(request(NGINX, null))).isEqualTo(NGINX);
        assertThat(clients.of(request(NGINX, "evil.example.com"))).isEqualTo(NGINX);
        assertThat(clients.of(request(NGINX, "1.2.3.4, 5.6.7.8"))).isEqualTo(NGINX);
    }

    @Test
    void anUnresolvedProxyIsTrustedOnceItResolvesAfterTheRefresh() {
        AtomicInteger lookups = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ClientAddress clients = new ClientAddress(List.of(" frontend ", ""),
                host -> lookups.incrementAndGet() == 1 ? Set.of() : Set.of(NGINX), clock);

        assertThat(clients.of(request(NGINX, "203.0.113.7"))).isEqualTo(NGINX);
        clock.now = clock.now.plusSeconds(10);
        assertThat(clients.of(request(NGINX, "203.0.113.7"))).isEqualTo(NGINX);
        assertThat(lookups).hasValue(1);

        clock.now = clock.now.plus(Duration.ofSeconds(31));
        assertThat(clients.of(request(NGINX, "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(lookups).hasValue(2);
    }

    @Test
    void theDefaultResolverAcceptsAddressesAndSkipsUnknownHosts() {
        ClientAddress clients = new ClientAddress(List.of("127.0.0.1", "no-such-host.invalid"));

        assertThat(clients.of(request("127.0.0.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    private static ClientAddress frontend(Map<String, Set<String>> hosts) {
        return new ClientAddress(List.of("frontend"), host -> hosts.getOrDefault(host, Set.of()), Clock.systemUTC());
    }

    private static MockHttpServletRequest request(String peer, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr(peer);
        if (realIp != null) {
            request.addHeader(ClientAddress.HEADER, realIp);
        }
        return request;
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-03T15:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
