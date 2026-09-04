# RGP Game Load Test — Tài Liệu Kỹ Thuật

**Dự án:** `rgp-game-load-test`
**System Under Test (SUT):** `be-silk-road-caravans` (Silk Road Caravans Slot Game Backend)
**Phiên bản tài liệu:** 1.0
**Ngày phát hành:** 2026-05-12
**Phân loại:** Internal / Customer Demo — Technical Document
**Người soạn:** RGP Engineering

---

## Mục lục

1. [Executive Summary](#1-executive-summary)
2. [Mục tiêu & Phạm vi](#2-mục-tiêu--phạm-vi)
3. [System Under Test](#3-system-under-test-silk-road-caravans)
4. [Tổng quan công nghệ](#4-tổng-quan-công-nghệ)
5. [Kiến trúc tổng thể](#5-kiến-trúc-tổng-thể)
6. [Cấu trúc Source Code](#6-cấu-trúc-source-code)
7. [Test Simulations](#7-test-simulations)
8. [Tiêu chí Production (SLA)](#8-tiêu-chí-production-pr-1--pr-7)
9. [Threshold Variants](#9-threshold-variants-baseline--critical)
10. [Workflow chạy test](#10-workflow-chạy-test)
11. [Monitoring & Output Artifacts](#11-monitoring--output-artifacts)
12. [Reporting Pipeline](#12-reporting-pipeline)
13. [Kết quả tham chiếu](#13-kết-quả-tham-chiếu-baseline-runs)
14. [Risk & Limitations](#14-risk--limitations)
15. [Chi phí dự án](#15-chi-phí-dự-án-cost-breakdown)
16. [Roadmap & Mở rộng](#16-roadmap--mở-rộng)
17. [Phụ lục](#17-phụ-lục)

---

## 1. Executive Summary

`rgp-game-load-test` là **bộ test tải (load-test suite)** chuyên dụng được thiết kế để **xác minh khả năng chịu tải sản xuất (production readiness)** của hệ thống game slot **Silk Road Caravans**.

Suite được xây dựng trên nền **Gatling 3.15** (Java DSL), kết hợp với pipeline tự động hoá viết bằng **Python + Bash** cho phép:

| Khả năng | Mô tả |
|---|---|
| **Đo HTTP performance** | Ghi nhận response time (p50/p95/p99), throughput, error rate trên 3 endpoint chính của Silk Road |
| **Giám sát tài nguyên SUT** | Sampling CPU / Memory mỗi 5 giây (Docker stats hoặc host fallback) song song với quá trình bắn tải |
| **Phát hiện crash / hang** | Health probe mỗi 2 giây — phát hiện server bị treo / mất kết nối |
| **Quyết định Pass/Fail tự động** | Verdict JSON dựa trên 7 production criteria (PR-1 … PR-7) |
| **Báo cáo trực quan** | Tự sinh `summary.html` gồm verdict banner + biểu đồ CPU/Mem + Gatling report embedded |
| **Capacity discovery** | 4 simulation type (Soak / Stress / Spike / Basic) cho phép định danh điểm gãy hệ thống |

**Giá trị mang lại:**

- ✅ Khẳng định khả năng phục vụ **1.000 concurrent players** trong **60 phút** liên tục với p95 ≤ 500ms.
- ✅ Phát hiện sớm bottleneck (CPU, memory, connection pool, GC) **trước khi go-live**.
- ✅ Tạo dữ liệu chuẩn (baseline) làm tham chiếu cho mọi lần release sau.
- ✅ Pipeline có thể tái sử dụng cho các slot game khác trong portfolio RGP (Fruit Respin Mania, Nagas Treasure, Apsara Paradise, …).

---

## 2. Mục tiêu & Phạm vi

### 2.1. Mục tiêu

| # | Mục tiêu | Tiêu chí đo |
|---|---|---|
| M1 | Xác nhận SUT chịu được tải sản xuất | 1000 VU × 60 phút PASS toàn bộ PR-1…PR-7 |
| M2 | Đo response-time profile của 3 endpoint slot | Báo cáo p50/p75/p95/p99/max + chart |
| M3 | Tìm breaking point của hệ thống | Stress run (500 → 2500 VU/min) ghi nhận điểm p95 > 1s hoặc KO > 5% |
| M4 | Đánh giá khả năng phục hồi sau burst | Spike run (200 baseline + 5 × 1500 burst) đo recovery time |
| M5 | Tự động sinh báo cáo compliance | 1 file Markdown + 1 HTML cho mỗi run |
| M6 | Có thể tái sử dụng cho game khác | Source modular — chỉ cần thay endpoint + body templates |

### 2.2. Phạm vi (In Scope)

- **3 endpoint REST của Silk Road:**
  - `POST /api/game/caravans/v1/slot/spin`
  - `POST /api/game/caravans/v1/slot/last-spin`
  - `POST /api/game/caravans/v1/slot/history/summary`
- HTTP/1.1 over TCP, JSON body.
- Đo từ phía client (Gatling) — không inject metric vào SUT.
- Giám sát container Docker chạy SUT (hoặc host process fallback).

### 2.3. Ngoài phạm vi (Out of Scope)

- ❌ gRPC inbound API (PluginService — sẽ là Phase 2, xem [Roadmap](#16-roadmap--mở-rộng)).
- ❌ ZeroMQ outbound publisher (kênh game-state → WsProxy).
- ❌ Stress test downstream services (MongoDB, Redis, RabbitMQ) — chỉ đo black-box từ HTTP layer.
- ❌ Functional / correctness testing (đã có unit test trong repo SUT).
- ❌ Security testing (DDoS, injection, auth) — thuộc phạm vi pentest riêng.

---

## 3. System Under Test: Silk Road Caravans

| Thuộc tính | Giá trị |
|---|---|
| **Project** | `be-silk-road-caravans` |
| **Loại** | Spring Boot multi-module slot game backend |
| **Java** | 17 (Spring Boot) |
| **Context path** | `/api/game/caravans` |
| **HTTP port (local)** | `3000` |
| **gRPC port** | `9093` (PluginService) |
| **ZMQ ports** | `10004` (WS channel) / `11004` (internal) |
| **Storage** | MongoDB (game records, sessions), Redis (hot cache, wallet) |
| **Run mode khi test** | Docker Compose, `ZMQ_PUBLISHER_MOCK=true` (bỏ outbound ZMQ overhead) |

**Endpoint chi tiết:**

| Endpoint | Tỉ lệ trong mix | Body sample |
|---|---|---|
| `POST .../slot/spin` | ~90% | `{"userId":"${userId}","gameId":"silk_road_usecase","betAmount":1.0,"isBuyFeature":false,"isCheatJackpot":false,"freeGameSplittingSymbol":"A"}` |
| `POST .../slot/last-spin` | ~10% | `{"userId":"${userId}"}` |
| `POST .../slot/history/summary` | ~4% | `{"userId":"${userId}","page":0,"size":10}` |

> Tỉ lệ mix mô phỏng hành vi người chơi thực tế: spin liên tục, thi thoảng kiểm tra last-spin và xem history.

---

## 4. Tổng quan công nghệ

### 4.1. Stack chính

| Layer | Công nghệ | Version | Vai trò |
|---|---|---|---|
| **Load generation** | Gatling Highcharts + Java DSL | 3.15.0 | Engine bắn tải HTTP, scenario DSL |
| **Load generation (gRPC ready)** | Gatling gRPC + protoc plugin | gatling-grpc-java 3.15.0, gRPC 1.75.0, protobuf 4.32.1 | Sẵn sàng cho mở rộng test gRPC |
| **Build** | Maven (wrapper `./mvnw`) | 3.x | Build, run simulation, manage deps |
| **Java runtime** | OpenJDK | **11+** (target release=11) | Compile + execute Gatling |
| **Orchestration** | Bash 4+ | macOS / Linux | `run-variant.sh`, `monitor-resources.sh`, `health-poll.sh` |
| **Verifier / Reporting** | Python 3 stdlib | 3.x (no external deps) | `verify-thresholds.py`, `generate-summary-html.py`, `generate-final-report.py` |
| **SUT containerization** | Docker / Docker Compose | 24+ | Đo CPU/Mem qua `docker stats` |

### 4.2. Vì sao chọn Gatling?

| Tiêu chí | Gatling | k6 | JMeter | Locust |
|---|---|---|---|---|
| Concurrency model | Async (Akka, non-blocking) — 1 thread phục vụ nhiều VU | Go goroutine | Thread-per-VU (nặng) | Greenlet |
| Throughput tối đa trên 1 node | 🟢 Rất cao (>10k req/s) | 🟢 Cao | 🟡 Vừa | 🟡 Vừa |
| Báo cáo HTML out-of-box | 🟢 Có (charts đẹp) | 🟡 Cần grafana | 🟡 Có nhưng rời rạc | 🟡 Web UI |
| DSL strongly-typed | 🟢 Java/Scala/Kotlin | 🟡 JavaScript | 🔴 XML | 🟡 Python |
| Tích hợp Maven / Gradle | 🟢 Native | 🔴 Không | 🟡 Plugin | 🔴 Không |
| Assertion → CI exit code | 🟢 Có | 🟢 Có | 🟡 Khó | 🟡 Khó |
| Học theo team Java sẵn có | 🟢 | 🟡 | 🟡 | 🔴 |

**Kết luận:** Team RGP đã có expertise Java, SUT cũng là Java/Spring → chọn Gatling tối ưu cho **developer experience** lẫn **performance**.

### 4.3. Dependencies (pom.xml)

```xml
<dependency>
  <groupId>io.gatling.highcharts</groupId>
  <artifactId>gatling-charts-highcharts</artifactId>
  <version>3.15.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.gatling</groupId>
  <artifactId>gatling-grpc-java</artifactId>
  <version>3.15.0</version>
  <scope>test</scope>
</dependency>
```

Plugins: `gatling-maven-plugin 4.21.5`, `protobuf-maven-plugin 3.9.1`, `maven-compiler-plugin 3.15.0`.

---

## 5. Kiến trúc tổng thể

### 5.1. Luồng dữ liệu

```
┌──────────────────────────────────────────────────────────────────────┐
│                        rgp-game-load-test (Test Client)              │
│                                                                      │
│  ┌────────────────┐    ┌──────────────────┐    ┌─────────────────┐  │
│  │   Gatling      │    │  monitor-        │    │  health-poll.sh │  │
│  │   Simulation   │    │  resources.sh    │    │  (every 2s)     │  │
│  │  (Soak/Stress/ │    │  (docker stats   │    │  curl probe     │  │
│  │   Spike/Basic) │    │   every 5s)      │    │                 │  │
│  └───────┬────────┘    └────────┬─────────┘    └────────┬────────┘  │
│          │ HTTP                  │ docker API           │ HTTP        │
└──────────┼──────────────────────┼──────────────────────┼─────────────┘
           │                       │                      │
           ▼                       ▼                      ▼
   ┌───────────────────────────────────────────────────────────┐
   │   SUT: be-silk-road-caravans (Docker container :3000)     │
   │   ┌────────────┐  ┌──────────┐  ┌──────────┐              │
   │   │ Spring Boot│→ │ MongoDB  │  │ Redis    │              │
   │   │  (REST)    │  │ (game)   │  │ (cache)  │              │
   │   └────────────┘  └──────────┘  └──────────┘              │
   └───────────────────────────────────────────────────────────┘
           │                       │                      │
           ▼                       ▼                      ▼
   ┌───────────────────────────────────────────────────────────┐
   │              target/variants/<variant>-<timestamp>/        │
   │  gatling-report/  resource.csv  health.csv  verdict.json  │
   │             └──── summary.html (verdict + charts) ────┘   │
   └───────────────────────────────────────────────────────────┘
                                  │
                                  ▼
              ┌───────────────────────────────────────┐
              │  generate-final-report.py             │
              │  → docs/report/report-YYMMDD-*.md     │
              └───────────────────────────────────────┘
```

### 5.2. Mô hình triển khai

| Thành phần | Vị trí chạy | Lý do |
|---|---|---|
| Gatling Engine | Cùng máy hoặc máy riêng | Cần CPU tốt + nhiều socket — tránh contend với SUT khi đo prod-like |
| SUT (Docker) | Local Mac / Server riêng | Đo qua `docker stats` chính xác nhất |
| Mongo / Redis | Cùng compose stack với SUT | Loại trừ network latency giả tạo |

---

## 6. Cấu trúc Source Code

```
rgp-game-load-test/
├── pom.xml                                 # Maven config + Gatling/gRPC deps
├── mvnw, mvnw.cmd, .mvn/                   # Maven wrapper
├── README.md                               # Quick-start cho dev
├── docs/                                   # Tài liệu kỹ thuật (folder này)
│   └── silk-road-load-test-technical-document.md
├── scripts/                                # Orchestration + reporting
│   ├── run-variant.sh                      # Entry: chạy 1 variant trọn vẹn
│   ├── monitor-resources.sh                # CPU/Mem sampler (docker stats / ps)
│   ├── health-poll.sh                      # HTTP probe
│   ├── verify-thresholds.py                # → verdict.json
│   ├── generate-summary-html.py            # → summary.html
│   └── generate-final-report.py            # → Markdown compliance report
├── src/test/
│   ├── java/com/rgp/loadtest/
│   │   ├── config/
│   │   │   └── LoadTestConfig.java         # Host, port, users, feeder, HTTP protocol
│   │   ├── utils/
│   │   │   └── Constants.java              # PR-4, PR-5 thresholds, mode names
│   │   ├── requests/
│   │   │   └── SlotRequests.java           # spin() / lastSpin() / historySummary()
│   │   ├── scenarios/
│   │   │   └── SessionJourneyScenario.java # spin (100%) + last-spin (10%) + history (4%)
│   │   └── simulations/                    # Entry points
│   │       ├── SoakSimulation.java         # 1000 VU × 60min — PRODUCTION GATE
│   │       ├── StressSimulation.java       # ramp 500→2500 VU/min — capacity discovery
│   │       ├── SpikeSimulation.java        # baseline + 5 bursts × 1500 VU
│   │       └── BasicSimulation.java        # atomic / chain / burst — single endpoint stress
│   ├── proto/
│   │   └── plugin_service.proto            # gRPC contract (Phase 2 ready)
│   └── resources/
│       ├── gatling.conf                    # Gatling engine tuning
│       ├── logback-test.xml                # Log config
│       ├── application.yml                 # SLA params
│       └── bodies/                         # JSON templates với Gatling EL
│           ├── spin.json
│           ├── last-spin.json
│           └── history-summary.json
└── target/                                 # Build output + Gatling reports
    └── variants/<variant>-<timestamp>/     # Per-run artifacts
```

### 6.1. Nguyên tắc phân tầng (Layer Convention)

| Layer | Trách nhiệm | Quy tắc |
|---|---|---|
| `simulations/` | Injection pattern + SLA assertions + protocol binding | 1 file = 1 test type |
| `scenarios/` | User journey (compose nhiều request) | Không trực tiếp gọi HTTP |
| `requests/` | Wrap 1 HTTP call (URL + method + body + check) | Không biết về scenario/injection |
| `config/` | Shared: baseUrl, feeder, HTTP protocol builder | Single source of truth cho param |
| `utils/` | Constants (SLA, mode names) | Pure constants — no logic |
| `bodies/` | JSON templates với placeholder `${userId}` | Gatling EL — không hard-code dữ liệu |

> Nguyên tắc: **đổi endpoint chỉ động `requests/`**, đổi journey chỉ động `scenarios/`, đổi injection chỉ động `simulations/`. Tách biệt rõ → onboarding dev mới nhanh.

---

## 7. Test Simulations

Bộ test có **4 simulation type**, mỗi loại phục vụ một mục đích riêng:

### 7.1. SoakSimulation — Production Gate ⭐

**Mục đích:** Xác thực SLA sản xuất.

- **Pattern:** `rampUsers(N).during(rampMinutes)` (default) hoặc `atOnceUsers(N)` (flag `-Dparallel=true`).
- **Duration:** sustain `durationMinutes` sau khi ramp xong.
- **Assertions (binding — Maven build FAIL nếu vi phạm):**
  - `global().responseTime().mean() ≤ 500ms` (PR-4)
  - `global().failedRequests().percent() ≤ 1%` (PR-5)
- **Lệnh tiêu chuẩn:**

```bash
./scripts/run-variant.sh --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 \
  --container game-silk-road-caravans
```

| Mode | Inject pattern | Use case |
|---|---|---|
| Ramp (default) | 1000 VU rải đều 5 phút | Realistic production traffic |
| Parallel (`--parallel`) | 1000 VU bùng nổ cùng lúc | Flash-crowd / cold-start stress |

### 7.2. StressSimulation — Capacity Discovery

**Mục đích:** Tìm breaking point — không có SLA assertion.

- **Pattern:** `rampUsersPerSec(start/60).to(end/60).during(durationMinutes)`
- **Default:** `usersStart=500/min, usersEnd=2500/min, duration=30min`
- **Output:** Đường p95 / KO% theo thời gian → xác định ngưỡng SUT bắt đầu suy giảm.

```bash
./scripts/run-variant.sh --variant target --simulation Stress \
  --users 2500 --duration-minutes 30 --ramp-minutes 1 \
  --container game-silk-road-caravans
```

### 7.3. SpikeSimulation — Burst Recovery

**Mục đích:** Đo thời gian hồi phục sau burst.

- **Pattern:** Constant baseline (`200 users/min`) + N cycle, mỗi cycle wait `(interval - spikeDuration)` giây rồi inject `atOnceUsers(1500)`.
- **Default:** `baseline=200, spike=1500, cycles=5, cycleInterval=5min, spikeDuration=30s`.

```bash
./scripts/run-variant.sh --variant target --simulation Spike \
  --users 1700 --duration-minutes 25 --ramp-minutes 0 \
  --container game-silk-road-caravans
```

### 7.4. BasicSimulation — Atomic / Fixed-Volume

**Mục đích:** Test 1 endpoint hoặc 1 chain với số lượng cố định (không time-based).

| Mode (`-Dscenario=`) | Pattern | Total requests |
|---|---|---|
| `spin` | 1 population × `users` × `repeatsPerUser` spin | users × repeats |
| `last-spin` | giống trên với last-spin | users × repeats |
| `history-summary` | giống trên với history | users × repeats |
| `all` | 3 population × users mỗi cái 1 endpoint | users × 3 |
| `chain` | 1 population: mỗi VU chạy spin → last-spin → history tuần tự | users × repeats × 3 |
| `burst` | 3 population × `atOnceUsers(N)`, mỗi cái 1 endpoint | users × 3 |

- **Assertion mặc định:** `failedRequests().count() == 0L` (zero KO).
- **Assertion tùy chọn:** `-DmaxResponseTimeMs=N` → tất cả request phải ≤ N ms.

```bash
# 1M requests trong vài chục giây
./scripts/run-variant.sh --variant target --simulation Basic \
  --users 1000 --requests 1000000 --scenario spin \
  --duration-minutes 10 --ramp-minutes 0 \
  --container game-silk-road-caravans
```

### 7.5. SessionJourneyScenario — Realistic Journey

Được dùng bởi Soak / Stress / Spike. **1 VU = 1 phiên người chơi** chạy lặp trong toàn bộ `sessionLength`.

| Request | Tần suất | Logic |
|---|---|---|
| `spin` | Mỗi iteration (100%) | Spin chính |
| `last-spin` | ~10% (`userIndex % 10 == 0`) | Player check kết quả trước |
| `history-summary` | ~4% (`userIndex % 25 == 0`) | Player xem lịch sử |

Pause: `thinkTimeMin..thinkTimeMax` (default 1-3 giây) giữa các spin → mô phỏng người chơi thực.

---

## 8. Tiêu chí Production (PR-1 → PR-7)

Bộ tiêu chí được tham chiếu từ `Constants.java` và `verify-thresholds.py`:

| ID | Tiêu chí | Ngưỡng | Đo bởi |
|---|---|---|---|
| **PR-1** | Concurrent sessions | ≥ **1000** VU | Gatling injection config |
| **PR-2** | Test duration | ≥ **60** phút | Gatling sustain duration |
| **PR-3** | Server crashes / hang | **0** (không có gap probe > 5s) | `health-poll.sh` + `verify-thresholds.py` |
| **PR-4** | Avg response time | ≤ **500** ms | Gatling `responseTime().mean()` assertion |
| **PR-5** | Error rate (HTTP) | ≤ **1%** | Gatling `failedRequests().percent()` assertion |
| **PR-6** | CPU p95 | ≤ **70%** (variant `target`) | `resource.csv` → percentile |
| **PR-7** | Memory p95 | ≤ **80%** (variant `target`) | `resource.csv` → percentile |

**Cơ chế đảm bảo:** PR-4 và PR-5 là **Gatling assertion** → Maven build trả exit code ≠ 0 nếu vi phạm. PR-6/PR-7 do `verify-thresholds.py` tính → `verdict.json`. Phối hợp 2 lớp này: **không có cách nào "PASS giả"** — sai một tiêu chí là rớt build.

---

## 9. Threshold Variants (baseline → critical)

Cùng workload, 4 profile ngưỡng CPU/Mem khác nhau → mapping graceful degradation:

| Variant | CPU ceil | Mem ceil | Ý nghĩa |
|---|---|---|---|
| `baseline` | 50% | 60% | Headroom — chạy dư công suất |
| `target` | **70%** | **80%** | **Production gate** (PR-6, PR-7 đo ở đây) |
| `stress` | 85% | 90% | Saturation behavior — vẫn còn đáp ứng |
| `critical` | 95% | 95% | Pre-failure — sát ngưỡng đổ vỡ |

Mục đích: thay vì test PASS/FAIL nhị phân, đội ngũ vận hành **biết hệ thống còn cách critical bao xa**.

---

## 10. Workflow chạy test

### 10.1. Sequence diagram (text)

```
Dev/CI           run-variant.sh        monitor-resources.sh      health-poll.sh        ./mvnw gatling:test      verify-thresholds.py    generate-summary-html.py
  │                    │                       │                       │                       │                       │                       │
  ├── invoke ──────────►│                       │                       │                       │                       │                       │
  │                    ├─── spawn (bg) ───────►│                       │                       │                       │                       │
  │                    ├─── spawn (bg) ──────────────────────────────►│                       │                       │                       │
  │                    ├─── invoke ──────────────────────────────────────────────────────────►│                       │                       │
  │                    │                       │ docker stats / ps     │ curl probe            │ HTTP load             │                       │
  │                    │                       │ (5s interval)         │ (2s interval)         │ (continuous)          │                       │
  │                    │                       │ → resource.csv        │ → health.csv          │ → gatling.log         │                       │
  │                    │                       │                       │                       │                       │                       │
  │                    │◄──── Gatling done ────────────────────────────────────────────────────┤                       │                       │
  │                    ├─── kill bg ──────────►│ stop                                                                  │                       │
  │                    ├─── kill bg ──────────────────────────────────► stop                                          │                       │
  │                    │                       │                                                                       │                       │
  │                    ├── invoke ────────────────────────────────────────────────────────────────────────────────────►│                       │
  │                    │                       │                                                                       │ aggregate resource +  │
  │                    │                       │                                                                       │ health + Gatling      │
  │                    │                       │                                                                       │ → verdict.json        │
  │                    │                       │                                                                       │                       │
  │                    ├── invoke ────────────────────────────────────────────────────────────────────────────────────────────────────────────►│
  │                    │                       │                                                                                               │ banner + chart +
  │                    │                       │                                                                                               │ Gatling iframe
  │                    │                       │                                                                                               │ → summary.html
  │◄─── exit code ─────┤                                                                                                                       │
```

### 10.2. Trình tự thực thi (chi tiết)

| # | Bước | Component |
|---|---|---|
| 1 | Parse args, validate variant/simulation/container | `run-variant.sh` |
| 2 | Tạo `target/variants/<variant>-<timestamp>/` | `run-variant.sh` |
| 3 | Khởi động background: resource monitor (5s sampling) | `monitor-resources.sh` |
| 4 | Khởi động background: health probe (2s sampling) | `health-poll.sh` |
| 5 | Chạy Gatling: `./mvnw gatling:test -Dgatling.simulationClass=… -Dusers=… …` | Maven + Gatling |
| 6 | Kill background processes khi Gatling exit | `run-variant.sh` |
| 7 | Copy Gatling HTML report vào `OUT_DIR/gatling-report/` | `run-variant.sh` |
| 8 | Verify thresholds → `verdict.json` | `verify-thresholds.py` |
| 9 | Render `summary.html` (verdict + chart + iframe) | `generate-summary-html.py` |
| 10 | Trả exit code Gatling cho caller (CI có thể đọc) | `run-variant.sh` |

### 10.3. Tham số mặc định

| Property | Default | Áp dụng | Mô tả |
|---|---|---|---|
| `host` | `localhost` | tất cả | Target host |
| `port` | `3000` | tất cả | Target port + monitor fallback |
| `users` | `1000` | Basic, Soak | Số VU concurrent |
| `requests` | `10000` | Basic | Tổng request |
| `durationMinutes` | `60` | Soak, Stress | Sustain |
| `rampMinutes` | `5` | Soak | Ramp-up |
| `parallel` | `false` | Soak | `true` = atOnceUsers |
| `thinkTimeMin` / `thinkTimeMax` | `1` / `3` | Soak, Stress, Spike | Pause giữa spin (giây) |
| `usersStart` / `usersEnd` | `500` / `2500` | Stress | Injection rate (VU/phút) |
| `baseline` / `spike` / `cycles` / `cycleIntervalMinutes` / `spikeDurationSec` | `200` / `1500` / `5` / `5` / `30` | Spike | — |
| `scenario` | `spin` | Basic | `spin` \| `last-spin` \| `history-summary` \| `all` \| `chain` \| `burst` |
| `maxResponseTimeMs` | `0` (off) | Basic | Strict response-time ceiling |

---

## 11. Monitoring & Output Artifacts

### 11.1. Per-run output structure

```
target/variants/target-20260512-101355/
├── summary.html              ← MỞ FILE NÀY ĐẦU TIÊN
├── verdict.json              ← Pass/fail tổng hợp + metrics
├── resource.csv              ← CPU / Mem mỗi 5 giây
├── health.csv                ← HTTP probe status mỗi 2 giây
├── gatling.log               ← Stdout/stderr của Maven + Gatling
└── gatling-report/
    └── index.html            ← Full Gatling HTML (latency over time, throughput, errors)
```

### 11.2. `verdict.json` schema

```json
{
  "variant": "target",
  "users": 1000,
  "duration_sec": 3600,
  "cpu_p95": 32.4,
  "mem_p95": 65.1,
  "cpu_ceil": 70,
  "mem_ceil": 80,
  "cpu_pass": true,
  "mem_pass": true,
  "health_failures": 0,
  "max_consecutive_failures": 0,
  "crash_pass": true,
  "http_total": 1284931,
  "http_ok": 1284931,
  "http_ko": 0,
  "http_ko_percent": 0.0,
  "http_ko_ceil": 1.0,
  "http_pass": true,
  "gatling_exit_code": 0,
  "host_cores": 12,
  "verdict": "PASS"
}
```

### 11.3. Resource Monitor (`monitor-resources.sh`)

- **Primary mode:** `docker stats <container> --no-stream --format` → đọc `CPU%`, `Mem%`.
- **Fallback mode:** Nếu container không tồn tại, scan PID listening trên `--fallback-port` → `ps -o pcpu,pmem -p <pid>`.
- **CPU normalization:** Docker stats trả về CPU% theo *tất cả core* (vd 4 core → 400% là max). Verifier chia cho `host_cores` để chuẩn hoá về 0-100%.
- **Output CSV columns:** `timestamp,cpu_pct,mem_pct,mode`

### 11.4. Health Probe (`health-poll.sh`)

- Probe sequence (auto-detect khả thi):
  1. `GET /actuator/health`
  2. `POST /api/game/caravans/v1/slot/last-spin` với body probe
  3. `GET /`
- **Crash detection rule:** `max_consecutive_failures ≥ 3` (xấp xỉ gap 6 giây) → `crash_pass = false`.
- **Output CSV columns:** `timestamp,status_code,latency_ms`

---

## 12. Reporting Pipeline

### 12.1. `summary.html` — Single Page Verdict

Mỗi run sinh 1 file HTML duy nhất, gồm:

1. **Verdict banner** (PASS / FAIL màu xanh / đỏ).
2. **Quick metrics table** (CPU p95, Mem p95, HTTP KO%, crashes).
3. **CPU / Memory chart** (parse từ `resource.csv` → SVG line chart inline).
4. **Gatling report embedded** qua `<iframe src="gatling-report/index.html">`.

> 1 file → 1 trang → khách hàng / sếp xem đủ verdict + chart + chi tiết trong cùng tab.

### 12.2. Compliance Report tổng hợp

Sau khi chạy nhiều variant, gộp lại bằng:

```bash
python3 scripts/generate-final-report.py \
  --variants-dir target/variants \
  --report-out /Users/rgp/RGP-WorkSpace/docs/report/report-260512-load-test-production-readiness.md
```

Markdown report gồm:

- **Production Criteria — Pass/Fail table** (PR-1 → PR-7).
- **Threshold Variant Sweep** — so sánh baseline/target/stress/critical cạnh nhau.
- **Latency Distribution** từ `stats.json` của Gatling.
- **Resource Timeline** (min/mean/p95/max).
- **Discovery Runs** (stress breaking point, spike recovery time).
- **Unresolved Questions.**

---

## 13. Kết quả tham chiếu (Baseline Runs)

### 13.1. Smoke test (50 VU × 3 phút, target variant)

| Metric | Value |
|---|---|
| Duration | 244 s |
| CPU p95 | 0.36 % |
| Mem p95 | 11.53 % |
| Health failures | 0 |
| HTTP total / OK | 4593 / 1941 |
| Verdict | **FAIL** (do http_ko bất thường — đang điều tra body template) |

### 13.2. Localhost performance ceiling (endpoint `spin`)

| Endpoint | p50 | p95 | p99 | max | KO |
|---|---|---|---|---|---|
| `spin` | 29ms | 76ms | 119ms | 175ms | 0 |

> Tham chiếu nhanh: SUT ở local Docker có response time **rất thấp** (sub-100ms p95). Production trên hạ tầng cloud cần baseline lại tại môi trường thật.

### 13.3. Sample Verdict — PASS

```json
{
  "variant": "target",
  "users": 50,
  "duration_sec": 244,
  "cpu_p95": 1.79,
  "mem_p95": 5.95,
  "cpu_pass": true,
  "mem_pass": true,
  "health_failures": 0,
  "crash_pass": true,
  "verdict": "PASS"
}
```

---

## 14. Risk & Limitations

| # | Rủi ro / Giới hạn | Mitigation |
|---|---|---|
| R1 | Load gen + SUT cùng host → giành CPU | Khi chạy production gate, đẩy SUT sang máy khác, Gatling chạy trên CI runner riêng |
| R2 | macOS default `somaxconn=128` có thể nghẹn connection backlog | Đã cap `maxConnectionsPerHost=1200` trong `LoadTestConfig` |
| R3 | `ZMQ_PUBLISHER_MOCK=true` loại bỏ outbound publish overhead | Phase 2: chạy với ZMQ thật trên môi trường staging |
| R4 | Wallet adapter mock / staging → không phản ánh load DB wallet thật | Cần test e2e với prod-like wallet trước go-live |
| R5 | gRPC PluginService chưa được test | Đã có `plugin_service.proto` + dep `gatling-grpc-java` — Phase 2 |
| R6 | Reporting Python phụ thuộc stdlib → không validate schema | Acceptable risk — script ngắn, dễ review |
| R7 | Cron / CI integration chưa có | Hiện chạy manual. CI workflow yaml là next-step |
| R8 | Không đo downstream Mongo/Redis độc lập | Có thể bổ sung mongostat / redis-cli MONITOR sampler |

---

## 15. Chi phí dự án (Cost Breakdown)

### 15.1. Engineering Effort

| Hạng mục | Mô tả | Effort (MD) |
|---|---|---|
| Khởi tạo project skeleton | Maven + Gatling deps + folder structure | 0.5 |
| Implement requests/scenarios/simulations (Soak/Stress/Spike/Basic) | 4 simulations, 1 scenario, 3 requests | 3.0 |
| LoadTestConfig + feeder + HTTP protocol | Centralize config, deterministic feeder | 0.5 |
| Orchestration scripts (Bash) | `run-variant.sh`, `monitor-resources.sh`, `health-poll.sh` | 2.0 |
| Verifier + Summary HTML (Python) | `verify-thresholds.py`, `generate-summary-html.py` | 1.5 |
| Final compliance report generator | `generate-final-report.py` (Markdown aggregator) | 1.0 |
| Threshold variant system | baseline/target/stress/critical mapping | 0.5 |
| Baseline run + tuning | Chạy thử, fix maxConnectionsPerHost, body template | 1.0 |
| Tài liệu (README + technical doc) | README quick-start + technical document này | 1.5 |
| Code review + handover | Knowledge transfer | 0.5 |
| **Tổng** | | **~12 MD** (≈ 2.5 sprint-week 1 dev) |

> MD = Man-Day (1 dev × 1 ngày làm việc). Đơn giá tham chiếu Senior BE Engineer Vietnam: **~80-120 USD/MD** (tùy hợp đồng).
>
> **Engineering cost ước tính:** 12 MD × ~100 USD = **~1,200 USD** (đã implement xong — đây là sunk cost cho khách hàng tham khảo quy mô).

### 15.2. Infrastructure Cost — VPS Model

Công ty hiện đang sử dụng **VPS** (không phải public cloud hourly). VPS tính phí **theo tháng cố định** → cost model khác hẳn AWS: không tính per-run, mà tính theo **năng lực hạ tầng được dành riêng** cho load test environment.

#### Spec đề xuất cho Load Test Environment

| Node | Vai trò | Spec đề xuất | Ghi chú |
|---|---|---|---|
| **VPS-LOADGEN** | Chạy Gatling | 4 vCPU / 8 GB RAM / 80 GB SSD | Tách riêng khỏi SUT để tránh giành tài nguyên |
| **VPS-SUT** | Spring Boot Silk Road | 4 vCPU / 8 GB RAM / 80 GB SSD | Mô phỏng prod-like — cùng spec với production |
| **VPS-DATA** | MongoDB + Redis | 4 vCPU / 8 GB RAM / 160 GB SSD | Hoặc tách 2 VPS nhỏ hơn (2 vCPU / 4 GB mỗi cái) |

#### Cost ước tính (Vietnam VPS market — tham khảo)

| Provider tham khảo | VPS spec 4vCPU/8GB | Ghi chú |
|---|---|---|
| **Viettel IDC** | ~600.000 – 900.000 VND/tháng | Hạ tầng VN, băng thông nội nhanh |
| **VNG Cloud / BizFly / FPT Cloud** | ~700.000 – 1.000.000 VND/tháng | Có Object Storage, K8s service kèm |
| **Vultr / DigitalOcean / Linode** | $24 – $40 USD/tháng (~600K – 1M VND) | Tier quốc tế, datacenter SG/JP |
| **Hetzner (Đức)** | €13 – €20/tháng (~360K – 550K VND) | Rẻ nhất nhưng latency cao từ VN |

#### Cost dedicated cho load-test environment

**Phương án A — Dedicated VPS thường trực (always-on):**

| Resource | Spec | Cost/tháng (ước tính) |
|---|---|---|
| VPS-LOADGEN | 4 vCPU / 8 GB | ~800.000 VND (~$32 USD) |
| VPS-SUT | 4 vCPU / 8 GB | ~800.000 VND (~$32 USD) |
| VPS-DATA (Mongo + Redis) | 4 vCPU / 8 GB | ~800.000 VND (~$32 USD) |
| Bandwidth / Egress | Thường unlimited hoặc 5-10 TB/tháng | Đã bao gồm |
| Storage thêm (artifacts, log) | 20-50 GB | ~50.000 VND nếu có |
| **Tổng dedicated** | | **~2.400.000 – 2.500.000 VND/tháng** (~$96-100 USD) |

> Cost / run = `cost_tháng / số_run_tháng`. Chạy càng nhiều, cost/run càng rẻ.

**Phương án B — Share VPS với môi trường khác (staging/dev):**

Nếu công ty đã có sẵn staging VPS, có thể **tận dụng** ngoài giờ làm việc (vd: nightly 2AM-5AM):

| Resource | Cost incremental |
|---|---|
| Tận dụng staging VPS sẵn có | **0 VND** (chỉ trả thêm khi nâng cấp spec) |
| Bandwidth phát sinh trong run | Thường không tính thêm với VPS unlimited |
| **Tổng** | **~0 VND/tháng** |

> Khuyến nghị: **bắt đầu bằng phương án B**, đo workload thực tế, nâng cấp lên A nếu thấy cần môi trường biệt lập.

**Phương án C — Tận dụng dev laptop (như hiện tại):**

| Resource | Cost |
|---|---|
| Engineer laptop (Docker) | **0 VND** (chỉ tiêu điện + thời gian dev) |
| **Tổng** | **0 VND/tháng** |

> Phù hợp cho smoke test + debug. **Không phù hợp** cho production gate run 60 phút vì laptop có thể bị giành tài nguyên bởi IDE / browser.

#### Cost / Run trên Phương án A (dedicated VPS)

| Tần suất run / tháng | Cost / run (chia đều) |
|---|---|
| 4 run / tháng (1 lần/tuần) | ~600.000 VND/run |
| 20 run / tháng (1 lần/ngày làm việc) | ~120.000 VND/run |
| 30+ run / tháng (mỗi PR + nightly) | < 80.000 VND/run |

> Khác biệt cốt lõi vs cloud hourly: **VPS đã trả tiền dù không chạy**. Chạy nhiều = rẻ hơn / run. Chạy ít = lãng phí.

### 15.3. Optional — Gatling Enterprise Cloud

Nếu cần report dashboard tập trung, multi-team, history retention:

| Plan | Mô tả | Giá tham khảo |
|---|---|---|
| Gatling Enterprise Cloud (FrontLine) | Hosted, dashboard, trend, alerting | **~$2,500-5,000 USD/year** tùy team size |

*Note:* Hiện đang dùng OSS edition — đủ cho production gate. Enterprise edition là optional upgrade.

### 15.4. Total Cost of Ownership (TCO) — 12 tháng

Quy đổi tham khảo: **1 USD ≈ 25.000 VND** (cập nhật theo tỷ giá thực tế khi quote).

| Kịch bản | Infra / năm | Maintenance (~0.5 MD/tháng) | **Tổng / năm** |
|---|---|---|---|
| **C. Laptop dev (hiện tại)** | 0 VND | ~6 MD × 100 USD = ~15.000.000 VND | **~15.000.000 VND (~$600 USD)** |
| **B. Share staging VPS** | 0 VND (tận dụng sẵn) | ~15.000.000 VND | **~15.000.000 VND (~$600 USD)** |
| **A. Dedicated VPS (3 node)** | ~30.000.000 VND (2.5M × 12) | ~15.000.000 VND | **~45.000.000 VND (~$1.800 USD)** |
| **A + Gatling Enterprise Cloud** | A + ~75.000.000 VND ($3K) | ~15.000.000 VND | **~120.000.000 VND (~$4.800 USD)** |

> **Khuyến nghị cho công ty:** bắt đầu phương án **B (share staging VPS)** — cost 0 đồng, đủ chạy nightly. Nếu cần test thường xuyên với SLA chặt chẽ thì nâng lên **A**. Gatling Enterprise Cloud chỉ cần khi có nhu cầu multi-team dashboard tập trung.

---

## 16. Roadmap & Mở rộng

### Phase 1 — Hoàn thành ✅
- [x] Source modular: simulations / scenarios / requests / config
- [x] 4 simulation types (Soak / Stress / Spike / Basic)
- [x] Orchestration scripts + Python verifier
- [x] Per-run `summary.html` + compliance Markdown report
- [x] Baseline runs trên localhost Docker

### Phase 2 — Đang lên kế hoạch 🟡
- [ ] **gRPC test cho `PluginService` (9 RPCs)** — đã có `plugin_service.proto` + `gatling-grpc-java` dep, cần wrap requests + DSL
- [ ] **ZMQ subscriber side-channel** — measure outbound publish latency
- [ ] **Mongo / Redis sampler** chạy song song để correlation
- [ ] **CI workflow** (GitHub Actions): chạy nightly, post comment lên PR

### Phase 3 — Tương lai 🔵
- [ ] **Multi-game suite:** reuse framework cho Fruit Respin Mania (port 9090), Nagas Treasure (9092), Apsara Paradise (9095)
- [ ] **Distributed load gen** (Gatling cluster mode) — vượt giới hạn 1 node
- [ ] **Time-series persistence** (Prometheus + Grafana) thay cho CSV
- [ ] **A/B compare** giữa 2 build / commit (regression detection)
- [ ] **Auto-rollback gate** trong deployment pipeline (`be-deployment`) khi gate FAIL

---

## 17. Phụ lục

### A. Lệnh tham khảo nhanh

```bash
# Smoke (3 phút)
./scripts/run-variant.sh --variant target --simulation Soak \
  --users 50 --duration-minutes 3 --ramp-minutes 1 \
  --container game-silk-road-caravans

# Production gate (60 phút, 1000 VU)
./scripts/run-variant.sh --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 \
  --container game-silk-road-caravans

# Threshold sweep
for v in baseline target stress critical; do
  ./scripts/run-variant.sh --variant $v --simulation Soak \
    --users 1000 --duration-minutes 60 --ramp-minutes 5 \
    --container game-silk-road-caravans
done

# Capacity discovery (Stress)
./scripts/run-variant.sh --variant target --simulation Stress \
  --users 2500 --duration-minutes 30 --ramp-minutes 1 \
  --container game-silk-road-caravans

# Burst recovery (Spike)
./scripts/run-variant.sh --variant target --simulation Spike \
  --users 1700 --duration-minutes 25 --ramp-minutes 0 \
  --container game-silk-road-caravans

# Final compliance report (tổng hợp tất cả variants)
python3 scripts/generate-final-report.py \
  --variants-dir target/variants \
  --report-out docs/report/report-YYMMDD-load-test-production-readiness.md
```

### B. Naming Convention — Output

```
target/variants/<variant>-<YYYYMMDD-HHMMSS>/
```

VD: `target/variants/target-20260512-101355/`

### C. Yêu cầu môi trường

| Item | Tối thiểu | Khuyến nghị |
|---|---|---|
| JDK | 11 | 17 (cùng Java với SUT) |
| Maven | wrapper trong repo | wrapper trong repo |
| Docker | 24+ | 24+ với BuildKit |
| OS | macOS / Linux | Linux (CI) |
| `ulimit -n` | ≥ 2000 | 65536 |
| RAM | 4 GB free | 8 GB free |
| Python | 3.8+ stdlib only | 3.11+ |

### D. Glossary

| Thuật ngữ | Giải nghĩa |
|---|---|
| **VU** | Virtual User — 1 phiên ảo trong Gatling |
| **SUT** | System Under Test — hệ thống cần kiểm tra (Silk Road Caravans) |
| **SLA** | Service Level Agreement — cam kết chất lượng dịch vụ |
| **PR-N** | Production Readiness criterion #N (PR-1 đến PR-7) |
| **p50 / p95 / p99** | Phân vị 50% / 95% / 99% của response time |
| **KO** | Knocked Out — request fail (HTTP ≠ 200 hoặc assertion fail) |
| **Soak / Stress / Spike** | 3 mô hình tải kinh điển (sustain / capacity / burst) |

### E. Tài liệu liên quan

- `README.md` — Quick-start cho developer
- `docs/plans/plan-260508-gatling-load-test-setup.md` — Plan triển khai gốc
- `docs/report/report-260511-load-test-production-readiness.md` — Compliance report mới nhất
- `docs/report/report-260511-silkroad-load-test-bottleneck.md` — Phân tích bottleneck
- Gatling official docs: <https://docs.gatling.io/>

---

**Trạng thái tài liệu:** ✅ Hoàn chỉnh — phiên bản 1.0
**Tần suất cập nhật:** Khi có thay đổi simulation, SLA threshold, hoặc roadmap phase.
**Contact:** RGP Engineering Team
