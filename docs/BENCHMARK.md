# Concurrency & Load Benchmark Report (`v2.0`)

Reproducible multi-threaded HTTP load test executed against a live `Spring Boot 3.4.13` instance using [`benchmarks/run_load_benchmark.py`](../benchmarks/run_load_benchmark.py) (Python 3 standard library only: `urllib.request` + `concurrent.futures.ThreadPoolExecutor`).

## Test Environment

| Component | Configuration |
| :--- | :--- |
| **Runtime** | OpenJDK Temurin `21.0.8+9` LTS |
| **Framework & Profile** | Spring Boot `3.4.13` (`dev` profile: H2 PostgreSQL compatibility mode, Caffeine Cache, HikariCP) |
| **Host CPU** | 12th Gen Intel Core i5-12500H (16 logical cores, Windows) |
| **Target Endpoint** | `http://127.0.0.1:8101` |

## How to Reproduce

```bash
# 1. Start the API locally (or via Docker Compose)
.\mvnw.cmd spring-boot:run

# 2. Run the 3-wave concurrent load & invariant benchmark
python benchmarks/run_load_benchmark.py --base-url http://127.0.0.1:8101 --requests 100 --workers 25 --stock 15 --voucher-quota 10
```

## Measured Results (`2026-11-18`)

| Benchmark Wave | Workload | Status Distribution | Throughput (RPS) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Invariant Check |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Wave 1: Voucher Contention** | 25 concurrent users claiming `quota=10` | `201`: **10**, `409`: **15** | **100.19** | 237.87 | 241.12 | 241.76 | 241.76 | `0` quota overruns; `ProblemDetail.requestId == X-Request-Id` |
| **Wave 2: Order Checkout Contention** | 100 requests across 25 workers on `stock=15` | `201`: **15**, `409`: **85** | **96.79** | 67.97 | 746.82 | 764.87 | 769.60 | **0 oversells** (`sellableQty=0`, `reservedQty=15`) |
| **Wave 3: Idempotent Replay Race** | 25 concurrent retries with identical `Idempotency-Key` (+ 1 altered payload) | `201`: **25** (+ `409`: **1** on payload mutation) | **17.49** | 351.73 | 462.04 | 511.70 | 511.70 | **1** order created (`reservedQty=1`); `24` safe replays; `409 IDEMPOTENCY_KEY_CONFLICT` |

### Live Micrometer Counter Deltas (`/api/v1/metrics/summary`)

During the benchmark run above, the `/api/v1/metrics/summary` counters updated strictly in sync with database commits and conflict rejections:

```json
{
  "ordersReserved": 16,
  "oversellConflicts": 85,
  "idempotentReplays": 24,
  "vouchersClaimed": 10,
  "voucherQuotaConflicts": 15
}
```

### Engineering Observations

1. **Zero Oversells Under Hot-Row Contention:** In Wave 2 (`100` checkout requests across `25` workers contending on a single SKU with `stock=15`), all `85` losing requests exited immediately via conditional `UPDATE inventory_items ... WHERE (available_qty - reserved_qty) >= :qty`, achieving a `p50` latency of **67.97 ms** and **96.79 RPS** while tail requests (`p95 = 746.82 ms`) waited behind HikariCP/H2 row locks for the winning `15` commits.
2. **Concurrent Same-Key Idempotency Recovery:** In Wave 3, firing `25` simultaneous requests with the same `Idempotency-Key` exercised `OrderPlacementFacade`'s `UNIQUE(user_id, idempotency_key)` race-recovery path—rolling back duplicate stock holds and replaying the exact committed `OrderResponse` (`1` order created, `24` replays), while rejecting a subsequent quantity mutation with `409 IDEMPOTENCY_KEY_CONFLICT`.
3. **End-to-End Correlation Tracing:** Every `201` and `409` response verified `X-Request-Id` header parity, and every `409` RFC 7807 `ProblemDetail` payload carried matching `requestId`.
