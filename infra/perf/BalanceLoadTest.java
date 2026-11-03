// Load test of the balance read (F2, docs/perf.md). One file, JDK 21 only: `java BalanceLoadTest.java URL RPS SECONDS`.
//
// Open model: requests start at a fixed rate whatever the server does, and each latency is measured from the moment
// the request was due, not from when it was sent. A slow server therefore shows up in the percentiles instead of
// silently lowering the rate (coordinated omission).
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BalanceLoadTest {

    // The demo users of identity V2__demo_users.sql (wallets in wallet V2__demo_wallets.sql).
    private static final String[] USERS = {
            "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c", "2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f",
            "5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c"};

    public static void main(String[] args) throws Exception {
        URI uri = URI.create(args[0]);
        int rps = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 10;

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        run(client, uri, rps, warmup, null);
        long[] latencies = new long[rps * seconds];
        AtomicInteger errors = new AtomicInteger();
        long elapsed = run(client, uri, rps, seconds, new Sink(latencies, errors));

        Arrays.sort(latencies);
        System.out.printf("%s at %d req/s for %d s (after %d s of warm-up)%n", uri, rps, seconds, warmup);
        System.out.printf("requests %d, errors %d, achieved %.0f req/s%n", latencies.length, errors.get(),
                latencies.length / (elapsed / 1e9));
        System.out.printf("p50 %.2f ms  p95 %.2f ms  p99 %.2f ms  p99.9 %.2f ms  max %.2f ms%n",
                pct(latencies, 50), pct(latencies, 95), pct(latencies, 99), pct(latencies, 99.9),
                latencies[latencies.length - 1] / 1e6);
    }

    private record Sink(long[] latencies, AtomicInteger errors) {
    }

    private static long run(HttpClient client, URI uri, int rps, int seconds, Sink sink) throws InterruptedException {
        int total = rps * seconds;
        long intervalNanos = 1_000_000_000L / rps;
        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                long due = start + i * intervalNanos;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                int n = i;
                executor.submit(() -> {
                    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                            .header("X-User-Id", USERS[n % USERS.length]).GET().build();
                    boolean ok;
                    try {
                        ok = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    } catch (Exception e) {
                        ok = false;
                    }
                    if (sink != null) {
                        sink.latencies[n] = System.nanoTime() - due;
                        if (!ok) {
                            sink.errors.incrementAndGet();
                        }
                    }
                });
            }
        }
        return System.nanoTime() - start;
    }

    private static double pct(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)] / 1e6;
    }
}
