#!/usr/bin/env python3
"""Periodically save Prometheus exposition samples to a timestamped CSV."""

import csv
import os
import re
import signal
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


SCENARIOS = {
    "spread", "simultaneous", "page", "hot", "overlap", "distributed", "replay",
    "mixed", "mixed-distributed",
}
SAMPLE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+(\S+)(?:\s+\d+)?$")
stop = False


def stop_collection(_signum, _frame):
    global stop
    stop = True


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def scrape(url):
    request = Request(url, headers={"Accept": "text/plain"})
    with urlopen(request, timeout=5) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        body = response.read().decode("utf-8", errors="replace")

    samples = []
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        match = SAMPLE.match(line)
        if match:
            samples.append((match.group(1), match.group(2) or "", match.group(3)))
    if not samples:
        raise RuntimeError("response contained no Prometheus samples")
    return samples


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in SCENARIOS:
        sys.exit("사용법: python3 perf/scenarios/ticketing/collect-prometheus-csv.py SCENARIO")

    scenario = sys.argv[1]
    raw_url = os.environ.get("MANAGEMENT_URL", "").rstrip("/")
    if not raw_url:
        sys.exit("MANAGEMENT_URL을 지정하세요 (예: http://3.35.88.242:8081)")
    parsed = urlsplit(raw_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.path not in {"", "/"}:
        sys.exit("MANAGEMENT_URL은 http(s)://호스트:포트 형식이어야 합니다.")

    allowed = {
        value.strip().lower()
        for value in os.environ.get("PERF_ALLOWED_APP_HOSTS", "").split(",")
        if value.strip()
    }
    host = parsed.hostname.lower()
    if not allowed or host not in allowed:
        sys.exit("PERF_ALLOWED_APP_HOSTS에 MANAGEMENT_URL 호스트를 정확히 넣어야 합니다.")
    if "prod" in host:
        sys.exit("운영(prod)으로 보이는 호스트에서는 지표를 수집하지 않습니다.")

    try:
        interval = float(os.environ.get("INTERVAL", "2"))
    except ValueError:
        sys.exit("INTERVAL은 1 이상의 초 단위 숫자여야 합니다.")
    if interval < 1:
        sys.exit("INTERVAL은 1 이상의 초 단위 숫자여야 합니다.")

    endpoint = f"{raw_url}/actuator/prometheus"
    try:
        first_samples = scrape(endpoint)
    except (HTTPError, URLError, TimeoutError, OSError, RuntimeError) as error:
        sys.exit(f"Prometheus 확인 실패: {error}")

    root = Path(__file__).resolve().parents[3]
    out_dir = root / ".ai-output" / "perf" / f"ticketing-{scenario}"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = out_dir / f"prometheus-{stamp}.csv"
    suffix = 1
    while path.exists():
        path = out_dir / f"prometheus-{stamp}-{suffix:02d}.csv"
        suffix += 1

    signal.signal(signal.SIGINT, stop_collection)
    signal.signal(signal.SIGTERM, stop_collection)
    scrape_count = 0
    error_count = 0
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(["timestamp_utc", "metric", "labels", "value", "scrape_error"])

        def write_samples(samples):
            nonlocal scrape_count
            captured_at = utc_now()
            writer.writerows([captured_at, name, labels, value, ""] for name, labels, value in samples)
            output.flush()
            scrape_count += 1

        write_samples(first_samples)
        ready_file = os.environ.get("PROMETHEUS_READY_FILE")
        if ready_file:
            Path(ready_file).write_text(str(path), encoding="utf-8")
        print(f"Prometheus 수집 중: {endpoint} (간격 {interval:g}초)", flush=True)
        print(f"CSV: {path}", flush=True)
        while not stop:
            started = time.monotonic()
            try:
                write_samples(scrape(endpoint))
            except (HTTPError, URLError, TimeoutError, OSError, RuntimeError) as error:
                writer.writerow([utc_now(), "__scrape_error__", "", "", str(error)])
                output.flush()
                error_count += 1
                print(f"지표 수집 실패: {error}", file=sys.stderr, flush=True)
            remaining = interval - (time.monotonic() - started)
            deadline = time.monotonic() + max(0, remaining)
            while not stop and time.monotonic() < deadline:
                time.sleep(max(0.01, min(0.2, deadline - time.monotonic())))

        try:
            write_samples(scrape(endpoint))
        except (HTTPError, URLError, TimeoutError, OSError, RuntimeError) as error:
            writer.writerow([utc_now(), "__scrape_error__", "", "", str(error)])
            output.flush()
            error_count += 1
            print(f"마지막 지표 수집 실패: {error}", file=sys.stderr, flush=True)

    print(f"수집 종료: {path} | 성공 {scrape_count}회, 실패 {error_count}회")


if __name__ == "__main__":
    main()
