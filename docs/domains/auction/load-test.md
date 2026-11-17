# Auction load test plan

> Related implementation: perf/scenarios/auction-burst.js, auction-sse.mjs, auction-run.sh
>
> Last reviewed: 2026-11-17 (8,000 → 10,000 burst scenario design)

This plan measures a concentrated load on a single popular auction. It does not generate the background traffic of the other 90,000 concurrent users.

## 1. Goal and assumptions

- Assume 100,000 concurrent service users, with 10,000 converging on one popular auction.
- Reproduce approximately 10,000 requests within one second. Each k6 run sends an 8,000-request phase followed by a 10,000-request phase.
- Do not define a pass threshold before observing the results. Record the measurements for human review.
- Correctness is mandatory: 5xx, lost accepted bids, incorrect winner, or a bid accepted after the original deadline must be zero.
- The tester does not operate the server or copy seed tokens. Scripts log in seeded accounts and register test auctions.
- AU-5 uses its own connection ladder because a progressive SSE connection ramp is needed to observe the number of sustainable connections.

## 2. Scenarios

| ID | Situation | API | Authentication |
|---|---|---|---|
| AU-1 | Burst immediately before the deadline | POST /api/auctions/{id}/bids | Required |
| AU-2 | Burst reads of one popular auction | GET /api/auctions/{id} | Public in current implementation |
| AU-3 | Burst reads of the auction list | GET /api/auctions?status=OPEN&page=0&size=20 | Public in current implementation |
| AU-4 | Ordinary bid burst | POST /api/auctions/{id}/bids | Required |
| AU-5 | Hold real-time watcher connections | GET /api/auctions/{id}/stream | Required |

AU-1 places the original end at phase T + 1,000 ms by default. Every accepted bid extends the end to max(current end, acceptedAt + 60 seconds). Measure rejections as AUCTION_CLOSED when the client sent before the original end, the final end time, and finalization lag. The auction must have exactly one winner, who is the latest accepted bidder.

AU-2 and AU-3 send no token because the current SecurityConfig exposes these GETs. A 401 is recorded as a public GET route being blocked.

AU-4 uses one new auction for each phase. Each bidder logs in once and bids once in each phase. AU-5 progressively opens SSE connections and places one measurement bid per step. Two measurement accounts alternate because the auction rejects a bid from its current highest bidder. The scenario sizes the auction lifetime to the planned run plus the 60-second bid extension, then waits for the final CLOSED event so the server closes connections. If that does not happen within the configured wait, it closes client streams and sends a separately reported cleanup bid. The scenario records how many connections receive each measured revision and the server's connection gauge before and after closure.

## 3. Common and scenario-specific records

Record per phase:

- Request count, received responses, connection failures, 5xx, and status or business-error counts.
- Response latency p50/p95/p99/max, fraction received within one second after T, time from T to the last response, and request-send time spread.
- Conditions: target, phase size, auction IDs, bidder ID range, login requested/succeeded/duration, and options.
- Bid runs: 201 count versus accepted count derived from auction revision and their difference.
- AU-1: original and final endsAt, extension duration, AUCTION_CLOSED total, AUCTION_CLOSED sent before the original deadline, accepted count, SOLD/UNSOLD, winning amount, finalization lag, and whether winner amount matches the latest accepted response.
- AU-5: connection attempts, successful 200 plus initial event, failures by status, disconnects, open connections, streams receiving the changed revision, delivery delay p50/p95/max, and auction_stream_connections.

Any non-zero 5xx, connection failure, accepted-bid mismatch, winner mismatch, or AUCTION_CLOSED before the original deadline is highlighted in the output. No latency or throughput pass line is defined.

The bid API response does not contain revision. For an OPEN auction the expected revision is initial OPEN revision + accepted bids; for a CLOSED auction it is initial OPEN revision + accepted bids + close transition. Auction.openIfDue(), Auction.acceptBid(), and Auction.close() each increment revision once, so server accepted counts are revision - 1 while OPEN and revision - 2 after close. The latest acceptedAt and greatest accepted response amount are recorded separately; accepted prices must rise by at least one increment, so the greatest amount belongs to the latest accepted bid. That amount is compared with the SOLD result.

## 4. Server-side observations

The client stores the k6 JSON, k6 HTML dashboard, logs, and two-second Actuator samples. Server-only checks are used when needed:

- Auction invariant verification: perf/ec2/perf-ec2.sh verify base, once after the day's runs.
- EC2 CPU credit mode: inspect or enable unlimited before a run when required.
- Database lock statistics, RDS CPU, netstat, and server logs: inspect when the measurements point to those causes.

The scripts do not change server settings or create load on other domains.

## 5. Run order

Run one scenario per restored perf database because each load scenario creates auctions or changes bids.

1. AU-2 detail reads.
2. AU-3 list reads.
3. AU-4 ordinary bid burst.
4. AU-1 ending bid burst.
5. AU-5 SSE connection hold.

For each k6 run, execute the 8,000 phase and then the 10,000 phase in the same run. Use the scenario runner to record .json, .html (k6 only), .log, and -metrics.csv under .ai-output/perf/auction/. Write one reviewed experiment report per run under docs/reports/.

Before the day starts, restore a perf database seeded once at base scale and start the app with the perf profile. Scripts create fresh auctions as needed. After the day's experiments, run the seed verification once and record the result with the experiment reports.

## 6. Known limitations

- The load generator is one Mac, so its CPU, file descriptors, network stack, and home network can become the bottleneck.
- The current Tomcat maximum connection count is 8,192, below AU-5's 10,000 connection target.
- No background traffic is generated for the other assumed 90,000 concurrent users.
- Login is paced at 15 requests per second by default; 10,000 bidder logins take about 11 minutes before the first phase.
- AU-2 and AU-3 are public only in the current implementation. A later auth change may require revising the scenario.
- SSE uses Node 24's built-in fetch and the server IP is supplied through PERF_APP_IP. The runner sets Host to perf-local and only allows configured perf- hosts.
- Each run creates test auctions which remain in the database. Use a fresh restored DB for independent runs.
