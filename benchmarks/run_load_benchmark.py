#!/usr/bin/env python3
"""Concurrent load & invariant benchmark for FlashSale Order & Inventory API v2.0.

Uses Python 3 standard library only (urllib.request + concurrent.futures).
Verifies zero oversells, voucher quota integrity, idempotency replay safety,
and X-Request-Id ProblemDetail propagation under multi-threaded contention.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
import json
import math
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


def http_json(
    base_url: str,
    method: str,
    path: str,
    payload: dict | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 15.0,
) -> tuple[int, dict[str, str], dict | list | str | None, float]:
    url = base_url.rstrip("/") + path
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)

    body_bytes = None
    if payload is not None:
        body_bytes = json.dumps(payload).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")

    req = urllib.request.Request(url, data=body_bytes, headers=req_headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            latency_ms = (time.perf_counter() - started) * 1000.0
            resp_headers = {k.lower(): v for k, v in resp.headers.items()}
            parsed = json.loads(raw) if raw else None
            return resp.status, resp_headers, parsed, latency_ms
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8")
        latency_ms = (time.perf_counter() - started) * 1000.0
        resp_headers = {k.lower(): v for k, v in err.headers.items()} if err.headers else {}
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw
        return err.code, resp_headers, parsed, latency_ms


def percentile(sorted_values: list[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    index = math.ceil((p / 100.0) * len(sorted_values)) - 1
    bounded = max(0, min(index, len(sorted_values) - 1))
    return sorted_values[bounded]


def compute_stats(latencies_ms: list[float], elapsed_sec: float) -> dict[str, float]:
    ordered = sorted(latencies_ms)
    return {
        "count": len(ordered),
        "elapsed_sec": elapsed_sec,
        "rps": len(ordered) / elapsed_sec if elapsed_sec > 0 else 0.0,
        "min_ms": ordered[0] if ordered else 0.0,
        "mean_ms": statistics.fmean(ordered) if ordered else 0.0,
        "p50_ms": percentile(ordered, 50),
        "p95_ms": percentile(ordered, 95),
        "p99_ms": percentile(ordered, 99),
        "max_ms": ordered[-1] if ordered else 0.0,
    }


def print_wave_report(title: str, statuses: Counter[int], stats: dict[str, float]) -> None:
    status_str = ", ".join(f"{code}={count}" for code, count in sorted(statuses.items()))
    print(f"\n=== {title} ===")
    print(f"Requests: {int(stats['count'])} | Elapsed: {stats['elapsed_sec']:.3f}s | Throughput: {stats['rps']:.2f} RPS")
    print(
        f"Latency (ms): min={stats['min_ms']:.2f}, p50={stats['p50_ms']:.2f}, "
        f"p95={stats['p95_ms']:.2f}, p99={stats['p99_ms']:.2f}, max={stats['max_ms']:.2f}"
    )
    print(f"HTTP Statuses: {status_str}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run concurrent contention benchmark against FlashSale API")
    parser.add_argument("--base-url", default="http://127.0.0.1:8101", help="Base URL of running FlashSale API")
    parser.add_argument("--requests", type=int, default=100, help="Total checkout requests in stock contention wave")
    parser.add_argument("--workers", type=int, default=25, help="Concurrent worker threads")
    parser.add_argument("--stock", type=int, default=15, help="Available stock for contention SKU")
    parser.add_argument("--voucher-quota", type=int, default=10, help="Max redemptions for contention voucher")
    parser.add_argument("--admin-email", default="admin@flashsale.local")
    parser.add_argument("--admin-password", default="Admin@1234")
    parser.add_argument("--json-out", default="", help="Optional path to write JSON benchmark report")
    args = parser.parse_args()

    if args.requests < args.stock:
        raise SystemExit("--requests must be >= --stock")
    if args.workers < args.voucher_quota:
        raise SystemExit("--workers must be >= --voucher-quota")

    # 1. Health check
    status, _, health_body, _ = http_json(args.base_url, "GET", "/api/v1/health")
    if status != 200 or not isinstance(health_body, dict) or health_body.get("status") != "UP":
        print(f"Health probe failed: HTTP {status} -> {health_body}", file=sys.stderr)
        return 1

    # 2. Admin login
    status, _, admin_auth, _ = http_json(
        args.base_url,
        "POST",
        "/api/v1/auth/login",
        {"email": args.admin_email, "password": args.admin_password},
    )
    if status != 200 or not isinstance(admin_auth, dict):
        print(f"Admin login failed: HTTP {status} -> {admin_auth}", file=sys.stderr)
        return 1
    admin_token = admin_auth["accessToken"]
    admin_headers = {"Authorization": f"Bearer {admin_token}"}

    # 3. Baseline metrics snapshot
    status, _, metrics_before, _ = http_json(args.base_url, "GET", "/api/v1/metrics/summary")
    if status != 200 or not isinstance(metrics_before, dict):
        print(f"Metrics summary probe failed: HTTP {status}", file=sys.stderr)
        return 1

    run_tag = uuid.uuid4().hex[:8].upper()

    # 4. Provision isolated SKU & Voucher & Worker Customer accounts
    status, _, sku_resp, _ = http_json(
        args.base_url,
        "POST",
        "/api/v1/admin/products",
        {
            "sku": f"BM-SKU-{run_tag}",
            "name": f"Benchmark Limited GPU {run_tag}",
            "description": "Isolated concurrency benchmark item",
            "category": "Benchmarks",
            "price": 199.00,
            "active": True,
            "initialStock": args.stock,
        },
        headers=admin_headers,
    )
    if status != 201 or not isinstance(sku_resp, dict):
        print(f"Failed to create benchmark SKU: HTTP {status} -> {sku_resp}", file=sys.stderr)
        return 1
    product_id = sku_resp["id"]

    status, _, idem_sku_resp, _ = http_json(
        args.base_url,
        "POST",
        "/api/v1/admin/products",
        {
            "sku": f"BM-IDEM-{run_tag}",
            "name": f"Benchmark Idempotency Item {run_tag}",
            "description": "Isolated idempotency benchmark item",
            "category": "Benchmarks",
            "price": 75.00,
            "active": True,
            "initialStock": 5,
        },
        headers=admin_headers,
    )
    if status != 201 or not isinstance(idem_sku_resp, dict):
        print(f"Failed to create idempotency SKU: HTTP {status} -> {idem_sku_resp}", file=sys.stderr)
        return 1
    idem_product_id = idem_sku_resp["id"]

    now_utc = datetime.now(timezone.utc)
    voucher_code = f"BMV{run_tag}"
    status, _, voucher_resp, _ = http_json(
        args.base_url,
        "POST",
        "/api/v1/admin/vouchers",
        {
            "code": voucher_code,
            "discountType": "PERCENTAGE",
            "discountValue": 15.00,
            "minOrderAmount": 50.00,
            "maxRedemptions": args.voucher_quota,
            "startsAt": (now_utc - timedelta(minutes=2)).isoformat(),
            "endsAt": (now_utc + timedelta(hours=2)).isoformat(),
            "active": True,
        },
        headers=admin_headers,
    )
    if status != 201 or not isinstance(voucher_resp, dict):
        print(f"Failed to create benchmark voucher: HTTP {status} -> {voucher_resp}", file=sys.stderr)
        return 1

    buyer_tokens: list[str] = []
    for idx in range(args.workers):
        email = f"bench-{run_tag.lower()}-{idx}@flashsale.local"
        s, _, reg_body, _ = http_json(
            args.base_url,
            "POST",
            "/api/v1/auth/register",
            {"email": email, "password": "BenchUser@1234", "fullName": f"Bench Buyer {idx}"},
        )
        if s != 201 or not isinstance(reg_body, dict):
            print(f"Failed registering buyer {email}: HTTP {s} -> {reg_body}", file=sys.stderr)
            return 1
        buyer_tokens.append(reg_body["accessToken"])

    # Wave 1: Voucher quota contention
    voucher_barrier = threading.Barrier(args.workers)

    def claim_task(worker_idx: int) -> tuple[int, str, dict | list | str | None, str, float]:
        req_id = f"bm-v-{run_tag}-{worker_idx}"
        voucher_barrier.wait(timeout=15)
        code, headers, body, lat = http_json(
            args.base_url,
            "POST",
            f"/api/v1/vouchers/{voucher_code}/claim",
            headers={
                "Authorization": f"Bearer {buyer_tokens[worker_idx]}",
                "X-Request-Id": req_id,
            },
        )
        return code, req_id, body, headers.get("x-request-id", ""), lat

    w1_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        w1_results = list(pool.map(claim_task, range(args.workers)))
    w1_elapsed = time.perf_counter() - w1_start

    w1_statuses: Counter[int] = Counter(r[0] for r in w1_results)
    w1_stats = compute_stats([r[4] for r in w1_results], w1_elapsed)
    print_wave_report(
        f"Wave 1: Voucher Contention ({args.workers} users -> quota={args.voucher_quota})",
        w1_statuses,
        w1_stats,
    )
    assert w1_statuses[201] == args.voucher_quota, f"Expected {args.voucher_quota} voucher claims, got {w1_statuses}"
    assert w1_statuses[409] == args.workers - args.voucher_quota, f"Expected remaining 409 conflicts, got {w1_statuses}"
    for code, req_id, body, resp_req_id, _ in w1_results:
        assert resp_req_id == req_id, f"X-Request-Id mismatch: sent={req_id}, got={resp_req_id}"
        if code == 409:
            assert isinstance(body, dict) and body.get("requestId") == req_id
            assert body.get("errorCode") == "VOUCHER_QUOTA_EXHAUSTED"

    # Wave 2: Stock contention checkout burst (100 requests across 25 workers -> stock=15)
    burst_barrier = threading.Barrier(min(args.workers, args.requests))

    def order_task(req_idx: int) -> tuple[int, str, dict | list | str | None, str, float]:
        req_id = f"bm-o-{run_tag}-{req_idx}"
        token = buyer_tokens[req_idx % len(buyer_tokens)]
        if req_idx < min(args.workers, args.requests):
            burst_barrier.wait(timeout=15)
        code, headers, body, lat = http_json(
            args.base_url,
            "POST",
            "/api/v1/orders",
            payload={"items": [{"productId": product_id, "quantity": 1}]},
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"bm-order-{run_tag}-{req_idx}",
                "X-Request-Id": req_id,
            },
        )
        return code, req_id, body, headers.get("x-request-id", ""), lat

    w2_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(order_task, i) for i in range(args.requests)]
        w2_results = [f.result() for f in as_completed(futures)]
    w2_elapsed = time.perf_counter() - w2_start

    w2_statuses: Counter[int] = Counter(r[0] for r in w2_results)
    w2_stats = compute_stats([r[4] for r in w2_results], w2_elapsed)
    print_wave_report(
        f"Wave 2: Order Checkout Contention ({args.requests} requests / {args.workers} workers -> stock={args.stock})",
        w2_statuses,
        w2_stats,
    )

    _, _, product_after, _ = http_json(args.base_url, "GET", f"/api/v1/products/{product_id}")
    assert w2_statuses[201] == args.stock, f"Oversell/undersell detected: {w2_statuses}"
    assert w2_statuses[409] == args.requests - args.stock, f"Unexpected status distribution: {w2_statuses}"
    assert len(w2_statuses) == 2, f"Unexpected non-201/409 statuses: {w2_statuses}"
    assert isinstance(product_after, dict)
    assert product_after["sellableQty"] == 0, f"Expected sellableQty == 0, got {product_after}"
    assert product_after["reservedQty"] == args.stock, f"Expected reservedQty == {args.stock}, got {product_after}"

    for code, req_id, body, resp_req_id, _ in w2_results:
        assert resp_req_id == req_id, f"X-Request-Id mismatch: sent={req_id}, got={resp_req_id}"
        if code == 409:
            assert isinstance(body, dict) and body.get("requestId") == req_id
            assert body.get("errorCode") == "INSUFFICIENT_STOCK"

    # Wave 3: Concurrent idempotency replay + payload mismatch
    idem_key = f"bm-idem-race-{run_tag}"
    idem_barrier = threading.Barrier(args.workers)

    def idem_task(worker_idx: int) -> tuple[int, dict[str, str], dict | list | str | None, float]:
        idem_barrier.wait(timeout=15)
        return http_json(
            args.base_url,
            "POST",
            "/api/v1/orders",
            payload={"items": [{"productId": idem_product_id, "quantity": 1}]},
            headers={
                "Authorization": f"Bearer {buyer_tokens[0]}",
                "Idempotency-Key": idem_key,
                "X-Request-Id": f"bm-idem-{run_tag}-{worker_idx}",
            },
        )

    w3_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        w3_results = list(pool.map(idem_task, range(args.workers)))
    w3_elapsed = time.perf_counter() - w3_start

    w3_statuses: Counter[int] = Counter(r[0] for r in w3_results)
    w3_stats = compute_stats([r[3] for r in w3_results], w3_elapsed)
    print_wave_report(
        f"Wave 3: Concurrent Idempotency Replay ({args.workers} concurrent identical retries)",
        w3_statuses,
        w3_stats,
    )

    assert w3_statuses[201] == args.workers, f"Expected all {args.workers} idempotent calls to return 201, got {w3_statuses}"
    returned_order_ids = {r[2]["id"] for r in w3_results if isinstance(r[2], dict)}
    assert len(returned_order_ids) == 1, f"Duplicate orders created under same Idempotency-Key: {returned_order_ids}"
    _, _, idem_after, _ = http_json(args.base_url, "GET", f"/api/v1/products/{idem_product_id}")
    assert isinstance(idem_after, dict) and idem_after["reservedQty"] == 1 and idem_after["sellableQty"] == 4

    conflict_req_id = f"bm-idem-conflict-{run_tag}"
    c_status, c_headers, c_body, _ = http_json(
        args.base_url,
        "POST",
        "/api/v1/orders",
        payload={"items": [{"productId": idem_product_id, "quantity": 2}]},
        headers={
            "Authorization": f"Bearer {buyer_tokens[0]}",
            "Idempotency-Key": idem_key,
            "X-Request-Id": conflict_req_id,
        },
    )
    assert c_status == 409 and isinstance(c_body, dict)
    assert c_body.get("errorCode") == "IDEMPOTENCY_KEY_CONFLICT"
    assert c_headers.get("x-request-id") == conflict_req_id and c_body.get("requestId") == conflict_req_id

    # Final metrics delta check
    status, _, metrics_after, _ = http_json(args.base_url, "GET", "/api/v1/metrics/summary")
    assert status == 200 and isinstance(metrics_after, dict)
    deltas = {
        k: metrics_after[k] - metrics_before[k]
        for k in (
            "ordersReserved",
            "oversellConflicts",
            "idempotentReplays",
            "vouchersClaimed",
            "voucherQuotaConflicts",
        )
    }

    print("\n=== Invariants & Micrometer Delta Summary ===")
    print(f"Oversells: 0 (sellableQty={product_after['sellableQty']}, reservedQty={product_after['reservedQty']})")
    print(f"Idempotent Order IDs Created: 1 across {args.workers} concurrent retries (payload mismatch -> 409)")
    print(f"Metrics Deltas: {json.dumps(deltas)}")

    if args.json_out:
        report = {
            "baseUrl": args.base_url,
            "config": {
                "requests": args.requests,
                "workers": args.workers,
                "stock": args.stock,
                "voucherQuota": args.voucher_quota,
            },
            "wave1_voucher_contention": {"statuses": dict(w1_statuses), **w1_stats},
            "wave2_order_contention": {"statuses": dict(w2_statuses), **w2_stats},
            "wave3_idempotency_replay": {"statuses": dict(w3_statuses), **w3_stats},
            "metricsDeltas": deltas,
            "oversells": 0,
        }
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)

    print("\nALL CONCURRENCY, IDEMPOTENCY, AND CORRELATION ASSERTIONS PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
