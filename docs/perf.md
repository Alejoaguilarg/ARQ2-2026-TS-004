# Performance: balance read (Feature 2)

Target of the development plan (milestone 2.3): `GET /wallets/me/balance` with p99 < 20 ms at 2,000 req/s on the local
compose.

## How to run it

With the whole system up (`docker compose --profile apps up -d`), from the repository root:

```bash
docker run --rm --network finova_backend -v "$PWD/infra/perf:/perf:ro" eclipse-temurin:21-jdk-alpine \
  java /perf/BalanceLoadTest.java http://wallet-service:8080/wallets/me/balance 2000 30 10
#                                 URL                                          req/s  s  warm-up s
```

[`infra/perf/BalanceLoadTest.java`](../infra/perf/BalanceLoadTest.java) is one file with no dependencies (JDK 21). It
starts requests at a fixed rate whatever the server does, and measures each latency from the moment the request was
due, so a slow server shows up in the percentiles instead of silently lowering the rate (coordinated omission). It
cycles over the three demo users.

**Why Wallet and not the Gateway.** The Gateway limits the balance read to 120 requests per minute per user (spec.md
§3.1.2), so with the three demo users it answers `429` long before 2,000 req/s; that limit is the intended behavior.
The test calls `wallet-service` inside the compose network, with the `X-User-Id` header the Gateway would inject: the
path Feature 2 is about (the Redis read model).

## Results

Machine: the 4-CPU cloud container where the system was developed, with the 20 containers of the compose and the load
generator sharing those CPUs. Numbers on a laptop will differ; what matters is the shape.

**Redis up (F2.1), 2,000 req/s for 30 s, 6 runs:**

| Run | Requests | Errors | p50 | p95 | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 1 | 60,000 | 0 | 1.70 ms | 24.67 ms | 185.43 ms | 397.66 ms |
| 2 | 60,000 | 0 | 0.97 ms | 3.91 ms | 8.64 ms | 17.64 ms |
| 3 | 60,000 | 0 | 1.00 ms | 4.18 ms | 9.55 ms | 21.34 ms |
| 4 | 60,000 | 0 | 0.85 ms | 2.98 ms | 7.74 ms | 18.18 ms |
| 5 | 60,000 | 0 | 0.81 ms | 2.87 ms | 6.49 ms | 51.70 ms |
| 6 | 60,000 | 0 | 3.65 ms | 25.79 ms | 49.80 ms | 65.54 ms |

The target is met in 4 of 6 runs (p99 6.5 to 9.6 ms), with no errors in any. Runs 1 and 6 are the first after the
service started and after Redis came back: the JVM was still compiling the hot path and the read model refilling.
Run the test twice and keep the second.

**Redis down (F2.6), 50 req/s for 20 s:** 1,000 requests, 0 errors, p50 403 ms, p99 407 ms. Every read answers `200`
from `wallet_db`, as the spec requires, but it first waits for the Redis read to time out (200 ms) and then for the
write-back to time out (200 ms more). Each request holds a server thread for those 400 ms, so the balance read
saturates at about 80 req/s on this machine: at 500 req/s every request timed out. Once Redis is back, Wallet
reconnects on its own within a few seconds and the reads return to milliseconds.

## Possible improvements (not done)

- **Skip the write-back after a failed read.** If the read already failed, the write-back almost surely fails too;
  skipping it halves the cost of a read with Redis down (`GetBalanceUseCase`).
- **A short circuit breaker on the read model.** After a few failures, go straight to `wallet_db` for some seconds.
  The read would then cost what PostgreSQL costs, and the capacity with Redis down would be that of the database.
