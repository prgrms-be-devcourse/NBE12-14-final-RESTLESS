#!/usr/bin/env python3
"""Record macOS CPU and interface counters while a ticketing run is active."""
import csv
import math
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

stop = False


def stopped(_signum, _frame):
    global stop
    stop = True


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=8, check=True)
    return result.stdout


def main():
    if sys.platform != 'darwin':
        sys.exit('Mac 지표 수집은 macOS에서만 사용할 수 있습니다.')
    if len(sys.argv) != 4:
        sys.exit('usage: collect-mac-csv.py OUTPUT RUNNER_PID BASE_URL')
    output_path, runner_pid, base_url = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    try:
        interval = float(os.environ.get('MAC_METRICS_INTERVAL', '5'))
        if not math.isfinite(interval) or interval < 2:
            raise ValueError()
    except ValueError:
        sys.exit('MAC_METRICS_INTERVAL은 2 이상의 초 단위 숫자여야 합니다.')
    host = urlsplit(base_url).hostname
    if not host:
        sys.exit('BASE_URL 호스트를 확인할 수 없습니다.')
    interface = os.environ.get('MAC_NET_INTERFACE', '')
    if not interface:
        try:
            match = re.search(r'^\s*interface:\s*(\S+)', command('/sbin/route', '-n', 'get', host), re.M)
            interface = match.group(1) if match else ''
        except (OSError, subprocess.SubprocessError):
            interface = ''
    signal.signal(signal.SIGTERM, stopped)
    signal.signal(signal.SIGINT, stopped)
    previous = None
    print(f'Mac 지표 CSV: {output_path} | 네트워크 인터페이스: {interface or "확인 실패"}', flush=True)
    with output_path.open('x', newline='', encoding='utf-8') as output:
        writer = csv.writer(output)
        writer.writerow(['timestamp_utc', 'cpu_total_percent', 'k6_cpu_percent_one_core', 'interface', 'rx_bytes_total', 'tx_bytes_total', 'rx_bytes_per_sec', 'tx_bytes_per_sec', 'errors'])
        while not stop:
            started = time.monotonic()
            errors = []
            cpu = k6_cpu = rx = tx = rx_rate = tx_rate = ''
            try:
                top = command('/usr/bin/top', '-l', '2', '-s', '1', '-n', '0')
                matches = re.findall(r'CPU usage:\s*([\d.]+)% user,\s*([\d.]+)% sys', top)
                if not matches:
                    raise ValueError('CPU 사용량을 찾을 수 없음')
                cpu = round(sum(map(float, matches[-1])), 2)
            except (OSError, subprocess.SubprocessError, ValueError) as exc:
                errors.append(f'cpu: {exc}')
            try:
                children = command('/usr/bin/pgrep', '-P', runner_pid).split()
                found = []
                for child in children:
                    info = command('/bin/ps', '-p', child, '-o', 'comm=', '-o', '%cpu=').strip()
                    match = re.match(r'^(.+?)\s+([\d.]+)$', info)
                    if match and Path(match.group(1)).name == 'k6':
                        found.append(float(match.group(2)))
                if found:
                    k6_cpu = round(sum(found), 2)
            except (OSError, subprocess.SubprocessError, ValueError):
                pass
            try:
                if not interface:
                    raise ValueError('경로 인터페이스를 찾을 수 없음; MAC_NET_INTERFACE 지정 필요')
                lines = command('/usr/sbin/netstat', '-ibn').splitlines()
                columns = lines[0].split()
                ri, ti = columns.index('Ibytes'), columns.index('Obytes')
                rows = [line.split() for line in lines[1:] if line.split() and line.split()[0] == interface]
                link_rows = [row for row in rows if len(row) > max(ri, ti) and row[2].startswith('<Link#')]
                if not link_rows:
                    raise ValueError('인터페이스 바이트 카운터 없음')
                rx, tx = int(link_rows[0][ri]), int(link_rows[0][ti])
                now = time.monotonic()
                if previous and rx >= previous[1] and tx >= previous[2]:
                    elapsed = now - previous[0]
                    rx_rate, tx_rate = round((rx - previous[1]) / elapsed, 2), round((tx - previous[2]) / elapsed, 2)
                previous = (now, rx, tx)
            except (OSError, subprocess.SubprocessError, ValueError, IndexError) as exc:
                errors.append(f'network: {exc}')
            writer.writerow([datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'), cpu, k6_cpu, interface, rx, tx, rx_rate, tx_rate, '; '.join(errors)])
            output.flush()
            if errors:
                print('Mac 지표 수집 오류: ' + '; '.join(errors), file=sys.stderr, flush=True)
            deadline = started + interval
            while not stop and time.monotonic() < deadline:
                time.sleep(min(0.2, max(0.01, deadline - time.monotonic())))
    print(f'Mac 지표 수집 종료: {output_path}', flush=True)


if __name__ == '__main__':
    main()
