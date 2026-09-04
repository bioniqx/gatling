# Getting Started — RGP Game Load Test

> Hướng dẫn cho **người mới**: từ zero đến chạy được load test đầu tiên trong **~5 phút**, và biết cách thêm game của riêng mình.

## Doc này dành cho ai?

| Đối tượng | Cần đọc section nào | Có cần biết Java/Gradle không? |
|---|---|---|
| **Non-tech (PM / QA / DevOps)** | 0, 1, 2, 3, **4 + 4.1**, 6, 8, 11 | KHÔNG — chỉ copy-paste lệnh |
| **Dev mới vào team** | thêm 5 (thêm game), 7 (workflow) | Có — cần đọc Java khi clone game |
| **Dev senior / Performance engineer** | thêm 9, 10 (reference đầy đủ) + `README.md` (concise English) + `CLAUDE.md` (architecture notes) | Có |

---

## 0. Bạn cần biết gì trước?

- Đây là project test hiệu năng dùng **Gatling** (Java DSL) — gọi nhiều HTTP request đồng thời tới game backend, đo thời gian phản hồi & lỗi.
- Project là **Gradle multi-module**: 1 module chung (`:core`) + mỗi game = 1 subproject (`:games:<name>`).
- Đã có sẵn **2 game** với 2 pattern khác nhau:
  - **Silk Road Caravans** (`games/silkroad/`) — stateless, open-model injection, ratio-modulo routing. Template chuẩn cho game đơn giản. REST only.
  - **Golden Boat Bonanza** (`games/bonanza/`) — stateful (capture sessionId/roundId), closed-model injection, randomSwitch routing. Có **cả REST và gRPC** (gRPC sim viết bằng Scala). Template cho game phức tạp hơn (xem [Section 5.9](#b%C6%B0%E1%BB%9Bc-59--optional-khi-game-c%C3%B3-stateful-journey-ho%E1%BA%B7c-routing-kh%C3%A1c)).
- Mỗi test ghi báo cáo vào `target/variants/<variant>-<timestamp>/` — mở `summary.html` để xem.

---

## 1. Cài đặt (~2 phút)

### Yêu cầu
- **JDK 17** (`java -version` phải báo `17.x`)
- **Docker** (để chạy game backend làm SUT — System Under Test)
- **Python 3** (cho verifier & report tổng hợp)
- Macbook / Linux. Windows chưa test.

### Verify
```bash
cd <path-to-rgp-game-load-test>      # vd ~/projects/rgp-game-load-test-minh

java -version        # 17.x
./gradlew --version  # Gradle 9.2.1, JVM 17
docker --version     # 20+
python3 --version    # 3.9+
```

Không cài Gradle riêng — wrapper `./gradlew` đi kèm repo tự pull Gradle 9.2.1.

---

## 2. Chạy test đầu tiên (~3 phút)

### Bước 2.1 — Khởi động SUT (game backend)

Backend Silk Road sống ở repo sibling `../be-silk-road-caravans` (không build từ repo này — xem `CLAUDE.md`).

```bash
cd ../be-silk-road-caravans          # từ load-test repo, sang sibling backend
ZMQ_PUBLISHER_MOCK=true docker compose up -d
```

Đợi ~30 giây cho Spring Boot start. Verify:
```bash
curl -s -X POST http://localhost:3000/api/game/caravans/v1/slot/spin \
  -H 'content-type: application/json' \
  -d '{"userId":"smoke","gameId":"silk_road_usecase","betAmount":1.0,"isBuyFeature":false,"isCheatJackpot":false,"freeGameSplittingSymbol":"A"}'
```
→ Phải trả `200` kèm JSON có `winAmount`, `balance`, `matrix`. Nếu lỗi → xem [Troubleshooting](#troubleshooting).

### Bước 2.2 — Chạy load test (smoke 1 phút)

```bash
cd -                                  # quay về load-test repo (hoặc cd <path-to-load-test>)

./gradlew :games:silkroad:soak \
  -Dusers=50 -DdurationMinutes=1 -DrampMinutes=1
```

Trong ~80 giây bạn sẽ thấy:
- Gatling in progress mỗi 5 giây
- `Global: count = 1499 (OK=1499 KO=0)` cuối cùng — `OK` = request trả 2xx, `KO` = request lỗi (timeout / 4xx / 5xx)
- `BUILD SUCCESSFUL`

### Bước 2.3 — Xem báo cáo

```bash
open games/silkroad/build/reports/gatling/$(ls -t games/silkroad/build/reports/gatling | head -1)/index.html
```

Hoặc dùng wrapper script kèm CPU/Mem monitor + verdict:

```bash
./scripts/run-variant.sh --game silkroad \
  --variant target --simulation Soak \
  --users 50 --duration-minutes 1 --ramp-minutes 1 \
  --container game-silk-road-caravans
```

Output ở `target/variants/silkroad/target-<timestamp>/summary.html` — mở browser thấy verdict banner + chart + report iframe.

> ⚠️ `--game` là **bắt buộc** (không còn default silkroad). Nếu quên, script fail với thông báo rõ ràng. Form cũ `GAME=<name> ./scripts/run-variant.sh ...` cũng vẫn hoạt động.

**Xong!** Bạn đã chạy được test đầu tiên.

---

## 3. Hiểu cấu trúc thư mục

```
rgp-game-load-test/
├── settings.gradle          ← khai báo subprojects (include :core, :games:silkroad, :games:bonanza)
├── build.gradle             ← shared Java 17, version chung + task verifyVariant
├── gradlew                  ← wrapper script
├── config/                  ← sla-thresholds.yml (CORE — tiêu chí chung)
│
├── core/                    ← THƯ VIỆN CHUNG, KHÔNG SỬA khi thêm game mới
│   ├── build.gradle
│   └── src/main/java/com/rgp/loadtest/core/
│       ├── config/          ← LoadTestConfig + SlaConfig + SlaConfigLoader (host/port/contextPath/YAML merge)
│       ├── utils/           ← SlaConstants (đọc từ YAML), SystemProps (key names)
│       ├── verify/          ← ThresholdVerifier (post-run verdict.json)
│       ├── scenarios/       ← SessionJourneyTemplate (generic loop + ratio)
│       └── simulations/     ← {Soak,Stress,Spike,Basic}SimulationBase (abstract, open-model)
│
├── games/silkroad/          ← Game 1 — STATELESS pattern (template chuẩn)
│   ├── build.gradle         ← Gatling plugin, alias `:soak/:stress/:spike/:basic`
│   └── src/gatling/
│       ├── java/com/rgp/loadtest/silkroad/
│       │   ├── requests/SlotRequests.java          ← URL + body file của 3 endpoint
│       │   ├── scenarios/SessionJourneyScenario.java ← compose request mix theo ratio modulo
│       │   ├── utils/Endpoints.java                ← name string của endpoint
│       │   └── simulations/                        ← 4 file ≤30 dòng, extends base
│       └── resources/
│           ├── application.yml, gatling.conf, logback-test.xml
│           ├── games/silkroad/bodies/              ← JSON body template (#{userId})
│           └── sla-thresholds.yml                  ← per-game SLA override (PR-4=300ms)
│
├── games/bonanza/            ← Game 2 — STATEFUL + CLOSED-MODEL pattern (REST + gRPC)
│   ├── build.gradle         ← Gatling + Scala plugin, alias `:soak/:basic/:grpc`
│   └── src/gatling/
│       ├── java/com/rgp/loadtest/bonanza/                ← REST sim (Java DSL)
│       │   ├── requests/SlotRequests.java               ← 10 endpoint + bonusFlowIfTriggered
│       │   ├── scenarios/PlayerJourneyScenario.java     ← Init phase + randomSwitch weighted
│       │   ├── utils/Endpoints.java                     ← 10 name string
│       │   └── simulations/                             ← SoakSimulation (extends Simulation, KHÔNG qua base), BasicSimulation
│       ├── scala/com/rgp/loadtest/bonanza/grpc/          ← gRPC sim (Gatling Java DSL gọi từ Scala)
│       │   └── BonanzaGrpcSimulation.scala              ← WSProxy plugin path (ConnectAndCall + Call, MessagePack payloads)
│       └── resources/
│           ├── games/bonanza/bodies/                     ← 4 body template (playerId/userId, betAmount type khác)
│           └── sla-thresholds.yml                       ← per-game SLA override (PR-4=500ms, PR-5=0.99%)
│
├── scripts/                 ← run-variant.sh + Python report (verifier đã port sang Java/Gradle task)
├── docs/                    ← guide + technical document
└── target/variants/         ← output mỗi lần chạy
```

**Quy ước phân lớp** (đi từ trên xuống — base → cụ thể):

| Lớp | Trách nhiệm |
|---|---|
| `simulations/` | Inject pattern (ramp/burst), assertion SLA, gắn HTTP protocol |
| `scenarios/` | Compose nhiều request thành 1 user journey (loop + think time + ratio) |
| `requests/` | Wrap 1 HTTP call: URL + method + body + check |
| `bodies/*.json` | Template body, dùng Gatling EL `#{userId}` |

---

## 4. Các kịch bản test có sẵn

Gọi qua Gradle alias: `./gradlew :games:silkroad:<alias> -D<prop>=<value>`

| Alias | Mục đích | Cách load được rải ra | Có chấm điểm? |
|---|---|---|---|
| `:soak` | Chạy dài (10-60 phút) để phát hiện memory leak / suy giảm dần | Tăng dần số user lên rồi giữ nguyên | ✅ **PR-4** (response time trung bình ≤ 500ms) + **PR-5** (tỉ lệ lỗi ≤ 1%) |
| `:stress` | Tìm điểm gãy của hệ thống | Tăng dần từ `usersStart` → `usersEnd` user/phút | ❌ chỉ đo, không gắn ceiling |
| `:spike` | Test khả năng hồi phục sau đột biến | Nền thấp liên tục + nhiều burst đột ngột | ❌ chỉ đo |
| `:basic` | Smoke nhanh / test 1 endpoint cụ thể | Tất cả user vào cùng lúc, mỗi user gọi N lần | ✅ Yêu cầu KO=0 (zero error) |

> **Thuật ngữ:** "VU" (virtual user) = 1 người dùng ảo trong Gatling, mỗi VU là 1 luồng độc lập. "Ramp" = tăng dần số user theo thời gian thay vì vào cùng lúc.

### Ví dụ thường dùng

```bash
# Smoke nhanh: 1 user, 1 spin
./gradlew :games:silkroad:basic -Dscenario=spin -Dusers=1 -Drequests=1

# Soak 5 phút × 100 VU
./gradlew :games:silkroad:soak -Dusers=100 -DdurationMinutes=5 -DrampMinutes=1

# Stress: ramp 500 → 2500 VU/giây trong 10 phút
./gradlew :games:silkroad:stress -DusersStart=500 -DusersEnd=2500 -DdurationMinutes=10

# Spike: 200 baseline + 3 bursts × 1500 mỗi 2 phút
./gradlew :games:silkroad:spike -Dbaseline=200 -Dspike=1500 -Dcycles=3 -DcycleIntervalMinutes=2

# Burst: 3 endpoint song song, mỗi endpoint nhận 1000 VU đồng loạt
./gradlew :games:silkroad:basic -Dscenario=burst -Dusers=1000

# Bonanza (stateful + closed model): 3 alias — :soak, :basic, :grpc
# Nhớ pass -Dhost / -Dport / -DcontextPath cho REST (backend khác silkroad)
./gradlew :games:bonanza:soak -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1 \
  -Dhost=localhost -Dport=3005 -DcontextPath=/golden
./gradlew :games:bonanza:basic -Dscenario=BetLevels -Dusers=1 -Drequests=1 \
  -Dhost=localhost -Dport=3005 -DcontextPath=/golden

# Bonanza gRPC (WSProxy plugin path — không qua REST surface)
# Dùng -DgrpcHost / -DgrpcPort, KHÔNG cần -DcontextPath
./gradlew :games:bonanza:grpc -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1 \
  -DgrpcHost=localhost -DgrpcPort=9091
```

> **Bonanza thiếu Stress/Spike — có chủ đích.** Standalone repo cũ không có (team trước đánh giá không cần) + chưa có baseline production để set default `usersStart`/`usersEnd`/`baseline`/`spike` có ý nghĩa. Sẽ add khi Soak production data có sẵn. Nếu cần test capacity/burst gấp → dùng `:basic -Dscenario=burst` hoặc soak với `-Dusers` cao.
>
> **`:grpc` chỉ có ở bonanza.** Là Scala simulation gọi Gatling Java DSL — tận dụng `io.gatling:gatling-grpc:3.15.0`. Cùng Gatling version với REST sim + assertion PR-4/PR-5 lấy chung từ `SlaConstants`. Khi clone game mới cần gRPC → xem `games/bonanza/src/gatling/scala/.../BonanzaGrpcSimulation.scala` làm reference.

### 4.1. Test 1 endpoint cụ thể (không chạy full journey)

**Câu hỏi thường gặp:** *"Tôi chỉ muốn test 1 endpoint (vd `/spin`), không muốn chạy mix nhiều endpoint cùng lúc — gõ gì?"*

**Trả lời:** Dùng `:basic` với `-Dscenario=<tên-endpoint>`.

**Endpoint hợp lệ cho Silk Road:**

| Tên endpoint (`-Dscenario=`) | URL backend gọi tới |
|---|---|
| `spin` | `POST /api/game/caravans/v1/slot/spin` |
| `last-spin` | `POST /api/game/caravans/v1/slot/last-spin` |
| `history-summary` | `POST /api/game/caravans/v1/slot/history/summary` |

**Endpoint hợp lệ cho Bonanza** (stateless — endpoint stateful như BonusReveal, RoundDetail cần state từ Spin nên không list ở Basic):

| Tên endpoint (`-Dscenario=`) | URL backend gọi tới |
|---|---|
| `BetLevels` | `GET /api/configs/bet-levels` |
| `ReelStrips` | `GET /api/configs/reel-strips` |
| `CreateSession` | `POST /api/sessions` |
| `Spin` | `POST /api/spin` (response 201, không phải 200) |
| `JackpotPools` | `GET /api/jackpot/pools` |
| `HistorySessions` | `GET /api/history/sessions?userId=...` |

**Ví dụ copy-paste:**

```bash
# 1. Smoke: 1 người dùng, gọi 1 lần → verify endpoint không die
./gradlew :games:silkroad:basic -Dscenario=spin -Dusers=1 -Drequests=1

# 2. Load nhẹ: 100 user, mỗi user gọi 50 lần spin (= 5000 spin tổng)
./gradlew :games:silkroad:basic -Dscenario=spin -Dusers=100 -Drequests=5000

# 3. Load nặng có monitor CPU/Mem + verdict (qua wrapper script)
./scripts/run-variant.sh --game silkroad --variant target --simulation Basic \
  --users 1000 --scenario spin --requests 100000 \
  --duration-minutes 5 --ramp-minutes 0 \
  --container game-silk-road-caravans

# 4. Đổi endpoint: thay `spin` bằng `last-spin` hoặc `history-summary`
./gradlew :games:silkroad:basic -Dscenario=last-spin -Dusers=100 -Drequests=1000
./gradlew :games:silkroad:basic -Dscenario=history-summary -Dusers=100 -Drequests=1000
```

> ⚠️ **Quan trọng:** Chỉ `:basic` support test 1 endpoint riêng. `:soak`, `:stress`, `:spike` LUÔN chạy **full user journey** (mỗi user mở phiên rồi spin liên tục, thi thoảng xen kẽ lastSpin và historySummary theo tỉ lệ) — không có cách tách 1 endpoint. Muốn test sustain dài chỉ 1 endpoint → dùng `:basic` với `-Drequests` đủ lớn.

**Game khác silkroad có endpoint gì?** Mở file `games/<game>/src/gatling/java/com/rgp/loadtest/<game>/utils/Endpoints.java`. Mỗi dòng kiểu `public static final String XXX = "spin";` là 1 tên endpoint dùng được với `-Dscenario=`. Vd silkroad có 3 endpoint nên trong file là:
```java
public static final String SPIN = "spin";
public static final String LAST_SPIN = "last-spin";
public static final String HISTORY_SUMMARY = "history-summary";
```

### Parameter chính (tham khảo nhanh)

| Property | Default | Áp dụng | Mô tả |
|---|---|---|---|
| `users` | 1000 | Soak, Basic | Số người dùng ảo chạy đồng thời (VU = virtual user) |
| `requests` | 10000 | Basic | Tổng số request muốn bắn (chia đều cho `users`) |
| `durationMinutes` | 60 | Soak, Stress | Thời gian chạy chính (phút) |
| `rampMinutes` | 5 | Soak | Thời gian tăng dần user lên đến `users` (phút) |
| `parallel` | false | Soak | `true` = tất cả user vào cùng lúc, không ramp dần |
| `scenario` | spin | Basic | Tên endpoint hoặc mode: `spin` \| `last-spin` \| `history-summary` \| `all` \| `chain` \| `burst` |

→ **Bảng đầy đủ TẤT CẢ biến** xem [Section 10](#10-reference-t%E1%BA%A5t-c%E1%BA%A3-bi%E1%BA%BFn).

---

## 5. Thêm game mới (silkroad là TEMPLATE)

> Quy tắc vàng: **KHÔNG sửa `core/`**. Mọi thứ game-specific nằm trong `games/<your-game>/`.

Ví dụ thêm game `fruit_respin_mania` (theo đúng workspace convention dùng underscore).

### Bước 5.1 — Clone subproject

```bash
cp -r games/silkroad games/fruit_respin_mania
```

Sau đó rename package trong code:
```bash
cd games/fruit_respin_mania
# Đổi tên thư mục package
mv src/gatling/java/com/rgp/loadtest/silkroad src/gatling/java/com/rgp/loadtest/fruit_respin_mania
mv src/gatling/resources/games/silkroad src/gatling/resources/games/fruit_respin_mania
# Sửa import + package declarations
grep -rl "silkroad" src/ | xargs sed -i '' 's/silkroad/fruit_respin_mania/g'
```

### Bước 5.2 — Khai báo subproject

Thêm vào `settings.gradle` (root):
```groovy
include ':games:fruit_respin_mania'
```

### Bước 5.3 — Update `build.gradle` của subproject

Trong `games/fruit_respin_mania/build.gradle`, đổi FQCN trong map `SIMULATIONS`:
```groovy
def SIMULATIONS = [
    soak  : 'com.rgp.loadtest.fruit_respin_mania.simulations.SoakSimulation',
    stress: 'com.rgp.loadtest.fruit_respin_mania.simulations.StressSimulation',
    spike : 'com.rgp.loadtest.fruit_respin_mania.simulations.SpikeSimulation',
    basic : 'com.rgp.loadtest.fruit_respin_mania.simulations.BasicSimulation',
]
```

### Bước 5.4 — Sửa 3 chỗ game-specific

**(a) `Endpoints.java`** — name string của endpoint:
```java
public static final String SPIN = "spin";
public static final String FREE_SPIN = "free-spin";   // endpoint riêng game này
```

> ℹ️ **Đây cũng là nơi non-tech tra cứu** tên endpoint hợp lệ cho `-Dscenario=` khi muốn test 1 endpoint cụ thể (xem [Section 4.1](#41-test-1-endpoint-c%E1%BB%A5-th%E1%BB%83-kh%C3%B4ng-ch%E1%BA%A1y-full-journey)). Mỗi dòng `public static final String XXX = "spin"` → dùng `-Dscenario=spin`.

**(b) `requests/SlotRequests.java`** — URL thật + body path:
```java
public static ChainBuilder spin() {
  return exec(http("spin")
      .post("/api/game/fruit-respin-mania/v1/slot/spin")
      .body(ElFileBody("games/fruit_respin_mania/bodies/spin.json"))
      .check(status().is(200)));
}
```

**(c) `bodies/*.json`** — sửa `gameId` và schema cho đúng game:
```json
{
  "userId": "#{userId}",
  "gameId": "fruit_respin_mania_usecase",
  "betAmount": 1.0
}
```

⚠️ **CẢNH BÁO QUAN TRỌNG**: Body **bắt buộc** dùng `#{userId}` (Gatling Java DSL syntax). KHÔNG dùng `${userId}` — Gatling sẽ KHÔNG substitute → tất cả VU gửi cùng 1 userId literal → distributed lock collide → 51% HTTP 400.

### Bước 5.5 — Đặt tên scenario duy nhất

Trong `scenarios/SessionJourneyScenario.java`, đổi default name:
```java
public static final String DEFAULT_NAME = "fruit-respin-session-journey";
```

Gatling 3.15 **strict**: nếu 2 game cùng đặt `"session-journey"` và chạy đồng thời → conflict. Format khuyên: `<game>-<purpose>`.

Đối với `SpikeSimulation` (có 2 population — baseline + burst), 2 scenario phải khác tên:
```java
SessionJourneyScenario.build("fruit-respin-spike-baseline", feeder, ...)
SessionJourneyScenario.build("fruit-respin-spike-burst", feeder, ...)
```

### Bước 5.6 — Verify

```bash
./gradlew :games:fruit_respin_mania:basic -Dscenario=spin -Dusers=1 -Drequests=1
```

Nếu pass → game mới đã tích hợp xong. Chạy full soak:
```bash
./gradlew :games:fruit_respin_mania:soak -Dusers=100 -DdurationMinutes=5 -DrampMinutes=1
```

> 💡 Muốn test 1 endpoint cụ thể của game mới (không chạy full journey)? Xem [Section 4.1](#41-test-1-endpoint-c%E1%BB%A5-th%E1%BB%83-kh%C3%B4ng-ch%E1%BA%A1y-full-journey). Pattern y hệt silkroad — chỉ cần đổi `silkroad` thành tên game mới.

### Bước 5.7 — (Optional) Dùng `run-variant.sh`

Script wrapper hỗ trợ qua flag `--game` (hoặc env var `GAME`):
```bash
./scripts/run-variant.sh --game fruit_respin_mania \
  --variant target --simulation Soak \
  --users 100 --duration-minutes 5 --ramp-minutes 1 \
  --container game-fruit-respin-mania
```

### Bước 5.8 — (Optional) Per-game SLA override

Nếu game mới cần tiêu chí pass/fail KHÁC core (vd response time lớn hơn do bonus loop, hoặc strict hơn do state ít), tạo file:

```
games/<your-game>/src/gatling/resources/sla-thresholds.yml
```

Chỉ list field muốn đổi — field thiếu fall back về `config/sla-thresholds.yml` (core). Xem chi tiết ở [Section 10.B → Per-game override](#10-reference-t%E1%BA%A5t-c%E1%BA%A3-bi%E1%BA%BFn).

```yaml
# Ví dụ: nới PR-4 cho game có bonus loop
sla:
  pr4_mean_response_ms_max: 700
# pr5, crash, variants → inherited từ core
```

Gradle subproject tự inject `-DgameName=<your-game>` → loader tự tìm file. Không cần nhập tay.

### Bước 5.9 — (Optional) Khi game có stateful journey hoặc routing khác

> ⚠️ **Khi nào KHÔNG clone `SessionJourneyScenario` của silkroad?** Khi game có:
> - **Stateful flow**: capture `sessionId` / `roundId` / `gameId` từ 1 response → dùng ở step sau (vd bonanza spin captures session/round IDs, history endpoint cần các ID đó).
> - **Conditional branch**: 1 nhánh chỉ chạy nếu điều kiện đúng (vd bonanza chỉ chạy bonus flow khi `jackpotTriggered=true`).
> - **Random-switch routing** với weight (vd bonanza: 80% spin / 8% jackpot / 5% history…) thay vì ratio modulo (vd silkroad: `userIndex % 10` → lastSpin).
> - **Closed injection model**: N người dùng sống suốt run, mỗi user chạy Init 1 lần rồi loop journey (silkroad mặc định open-model — VU mới đến theo rate).
> - **Context path** trong URL (vd bonanza `/golden`): truyền qua `-DcontextPath=/golden` — core đã hỗ trợ.
>
> **Pattern thay thế**: viết scenario class riêng (vd `PlayerJourneyScenario` ở bonanza), `SoakSimulation` extends `Simulation` trực tiếp (không qua `SoakSimulationBase`) để full control closed injection + per-endpoint assertions. Xem [`games/bonanza/src/gatling/java/com/rgp/loadtest/bonanza/`](../games/bonanza/src/gatling/java/com/rgp/loadtest/bonanza/) làm reference (cả khác biệt REST/gRPC giải thích thêm ở root `README.md` và `CLAUDE.md`).

---

## 6. Test mới đúng/sai như thế nào?

### Smoke pass nghĩa là gì?

Test pass khi tất cả 3 điều kiện:
1. **`BUILD SUCCESSFUL`** từ Gradle
2. **KO = 0** trong Gatling summary (`Global: ... KO=0`)
3. **Verdict PASS** (nếu chạy qua `run-variant.sh`):
   - CPU p95 ≤ ceiling của variant
   - Memory p95 ≤ ceiling
   - Health probe failures = 0

### Variant nghĩa là gì?

| Variant | CPU ceiling | Mem ceiling | Khi dùng |
|---|---|---|---|
| `baseline` | 50% | 60% | Headroom check |
| `target` | 70% | 80% | **Production gate** (mặc định) |
| `stress` | 85% | 90% | Saturation behavior |
| `critical` | 95% | 95% | Pre-failure |

Nếu CPU > ceiling → verdict FAIL dù Gatling pass. Điều này chặn deploy khi server đang "vắt chân lên cổ" mới đáp ứng được SLA.

### Đọc gì trong report?

| File | Đọc khi nào |
|---|---|
| `summary.html` | **Mặc định mở cái này** — verdict + chart + Gatling report iframe |
| `verdict.json` | Cần parse bằng script |
| `gatling-report/index.html` | Drill-down vào từng endpoint, percentile, error breakdown |
| `resource.csv` | Plot CPU/Mem theo thời gian (Excel) |
| `health.csv` | Check downtime windows |
| `gatling.log` | Debug khi Gatling chạy lỗi |

---

## 7. Workflow điển hình khi thêm test

```
1. Tạo SUT chạy → curl smoke OK
2. Clone games/silkroad/ → games/<your-game>/
3. Sửa 3 file: Endpoints, SlotRequests, bodies/*.json
4. Đổi scenario name (Section 5.5)
5. Smoke: ./gradlew :games:<your-game>:basic -Dscenario=spin -Dusers=1 -Drequests=1
6. Nếu pass → tăng dần lên soak 5 phút × 100 VU
7. Cuối cùng: chạy production gate qua run-variant.sh
```

---

## 8. Troubleshooting {#troubleshooting}

### "Connection refused" hoặc curl smoke fail
SUT chưa start. Check:
```bash
docker ps --filter name=game-silk-road-caravans
docker logs game-silk-road-caravans 2>&1 | tail -50
```
Lỗi thường gặp: MongoDB/Redis chưa ready (đợi thêm 30s).

### Test chạy nhưng 51% KO 400 (lỗi GHẾ trên hố)
Body JSON dùng sai EL syntax. **PHẢI** dùng `#{userId}`, KHÔNG phải `${userId}` (Gatling 3.7+ Java DSL).

```bash
grep -r '\${' games/<your-game>/src/gatling/resources/
# Nếu có match → sửa thành #{...}
```

### `BUILD FAILED: simulation class not found`
FQCN trong `build.gradle` không khớp package declaration trong Java file. Verify:
```bash
grep "^package" games/<your-game>/src/gatling/java/com/rgp/loadtest/<your-game>/simulations/SoakSimulation.java
# So với SIMULATIONS map trong games/<your-game>/build.gradle
```

### Gatling task không re-run (UP-TO-DATE)
Đã có `outputs.upToDateWhen { false }` trong build.gradle alias task. Nếu vẫn skip → force:
```bash
./gradlew :games:silkroad:soak --rerun-tasks -Dusers=50 -DdurationMinutes=1
```

### JDK 17 module access error (`InaccessibleObjectException`)
Đã có `--add-opens` trong `gatling.jvmArgs` của build.gradle. Nếu vẫn lỗi → check JDK đúng 17:
```bash
./gradlew --version | grep JVM
```

### Port 3000 đã bị chiếm
```bash
lsof -ti :3000 -sTCP:LISTEN | xargs kill -9
```

### `resource.csv` toàn N/A
Container không tồn tại. Verify:
```bash
docker ps --filter name=<your-container>
```
Hoặc bỏ `--container`, script tự fallback monitor host process theo `--port`.

---

## 9. Tài liệu tham khảo

- **README.md** (root) — bảng parameter ngắn gọn + clone-a-new-game checklist tiếng Anh
- **CLAUDE.md** (root) — kiến trúc, lý do bonanza diverge khỏi silkroad, gotchas
- **Gatling docs**: https://docs.gatling.io/reference/script/core/session/el/ (EL syntax)
- **Gatling Gradle plugin**: https://docs.gatling.io/reference/integrations/build-tools/gradle-plugin/
- **Gatling gRPC DSL** (cho bonanza `:grpc`): https://docs.gatling.io/reference/script/protocols/grpc/

---

## 10. Reference: tất cả biến

Mỗi command / script trong project có 1 tập biến riêng. Bảng này liệt kê đầy đủ — biết chính xác **biến nào cho command nào, ý nghĩa, default, ví dụ**.

### A. `-D…` system property cho Gradle Gatling task

Truyền vào khi chạy `./gradlew :games:<game>:<alias> -D<prop>=<value>`.

Đọc tại Java: `LoadTestConfig.java` (chung) hoặc `*SimulationBase.java` (riêng từng loại).
Forward whitelist khai báo tại `games/<game>/build.gradle:FORWARDED_PROPS` — **không có trong list thì Gatling không thấy**.

#### A.1 — Chung cho mọi simulation (đọc tại `core/.../config/LoadTestConfig.java`)

| Prop | Default | Bắt buộc? | Áp dụng | Mô tả | Ví dụ |
|---|---|---|---|---|---|
| `host` | `localhost` | ✗ | All | Hostname SUT | `-Dhost=staging.example.com` |
| `port` | `3000` | ✗ | All | Port HTTP SUT | `-Dport=8080` |
| `contextPath` | `""` (empty) | ✗ | All | URL prefix giữa host:port và request path. Bonanza dùng `/golden`. Silkroad để empty. | `-DcontextPath=/golden` |
| `users` | `1000` | ✗ | Soak, Basic | Số VU concurrent | `-Dusers=500` |
| `requests` | `10000` | ✗ | Basic | Tổng request (chia `repeatsPerUser`) | `-Drequests=1000000` |
| `durationMinutes` | `60` | ✗ | Soak, Stress, Spike | Thời gian sustain (phút) | `-DdurationMinutes=30` |
| `rampMinutes` | `5` | ✗ | Soak | Ramp-up duration (phút) | `-DrampMinutes=1` |
| `thinkTimeMin` | `1` | ✗ | Soak, Stress, Spike | Pause min giữa request (giây) | `-DthinkTimeMin=2` |
| `thinkTimeMax` | `3` | ✗ | Soak, Stress, Spike | Pause max giữa request (giây) | `-DthinkTimeMax=5` |

#### A.2 — Soak (`./gradlew :games:silkroad:soak`)

| Prop | Default | Áp dụng | Mô tả | Ví dụ |
|---|---|---|---|---|
| `parallel` | `false` | Soak | `true` = `atOnceUsers` (flash crowd) thay vì ramp | `-Dparallel=true` |

#### A.3 — Stress (`./gradlew :games:silkroad:stress`)

| Prop | Default | Mô tả | Ví dụ |
|---|---|---|---|
| `usersStart` | `500` | Injection rate đầu (users/**phút**) | `-DusersStart=100` |
| `usersEnd` | `2500` | Injection rate cuối (users/**phút**) | `-DusersEnd=5000` |

> Lưu ý: viết theo phút cho dễ hiểu, code chia 60 → per-second cho Gatling.

#### A.4 — Spike (`./gradlew :games:silkroad:spike`)

| Prop | Default | Mô tả | Ví dụ |
|---|---|---|---|
| `baseline` | `200` | VU/phút chạy nền liên tục | `-Dbaseline=100` |
| `spike` | `1500` | Số VU `atOnceUsers` mỗi burst | `-Dspike=2000` |
| `spikeDurationSec` | `30` | Cửa sổ burst (giây) | `-DspikeDurationSec=60` |
| `cycles` | `5` | Số burst sẽ phát | `-Dcycles=3` |
| `cycleIntervalMinutes` | `5` | Khoảng cách giữa 2 burst (phút) | `-DcycleIntervalMinutes=2` |

#### A.5 — Basic (`./gradlew :games:silkroad:basic`)

| Prop | Default | Mô tả | Ví dụ |
|---|---|---|---|
| `scenario` | first endpoint (`spin`) | Mode: `<endpoint>` \| `all` \| `chain` \| `burst` | `-Dscenario=chain` |
| `maxResponseTimeMs` | `0` (off) | Strict: max response ≤ N ms → fail nếu vượt | `-DmaxResponseTimeMs=2000` |

**Scenario modes** (đọc tại `BasicSimulationBase.java:45-90`):

| Mode | Pattern | Total request |
|---|---|---|
| `spin` / `last-spin` / `history-summary` | 1 endpoint × `users` VU × `repeatsPerUser` | `users × requests/users` |
| `all` | N populations song song, mỗi pop = 1 endpoint | `users × repeatsPerUser × N` |
| `chain` | 1 population, mỗi VU chạy tuần tự N endpoint | `users × chainRepeats × N` |
| `burst` | N populations × `atOnceUsers(users)` mỗi pop | `users × N` |

#### A.6 — Bonanza-only props

Chỉ có trong `games/bonanza/build.gradle:FORWARDED_PROPS`. Silkroad không đọc các prop này.

| Prop | Default | Áp dụng | Mô tả | Ví dụ |
|---|---|---|---|---|
| `grpcHost` | `localhost` | `:grpc` | gRPC WSProxy plugin host | `-DgrpcHost=staging.example.com` |
| `grpcPort` | `9091` | `:grpc` | gRPC WSProxy plugin port | `-DgrpcPort=9091` |
| `paceSec` | `5` | `:grpc` | Pause tối thiểu giữa 2 spin gRPC liên tiếp (giây) — `.pace(...)` | `-DpaceSec=2` |
| `requestRate` | `50` | `:grpc` | Throughput floor (req/giây) — assert `requestsPerSec().gt(N)` | `-DrequestRate=100` |
| `eventCount` | `100000` | `:grpc` | Volume floor — assert `successfulRequests().count().gt(N)` | `-DeventCount=500000` |

> Lưu ý: `rampMinutes` cho `:grpc` mặc định `2` (lấy từ standalone repo cũ) thay vì `5` của `LoadTestConfig.rampMinutes`. Cùng REST `SoakSimulation` để 2 gate đồng bộ.

---

### B. `-D…` cho task `verifyVariant`

Truyền vào khi chạy `./gradlew verifyVariant -D…`. Đọc tại `core/.../verify/ThresholdVerifier.java`.

| Prop | Default | Bắt buộc? | Mô tả | Ví dụ |
|---|---|---|---|---|
| `resourceCsv` | — | ✅ | Đường dẫn `resource.csv` (CPU/Mem samples) | `-DresourceCsv=target/variants/target-.../resource.csv` |
| `healthCsv` | — | ✅ | Đường dẫn `health.csv` (HTTP probe samples) | `-DhealthCsv=target/variants/target-.../health.csv` |
| `variant` | — | ✅ | `baseline` \| `target` \| `stress` \| `critical` — quyết định ceiling | `-Dvariant=target` |
| `users` | — | ✅ | VU count đã chạy (chỉ để ghi vào verdict.json) | `-Dusers=1000` |
| `durationSec` | — | ✅ | Wall-clock duration Gatling đã chạy (giây) | `-DdurationSec=3900` |
| `gatlingLog` | (none) | ✗ | Đường dẫn `gatling.log` → parse `> Global` để lấy KO% | `-DgatlingLog=target/variants/.../gatling.log` |
| `gatlingExit` | (none) | ✗ | Exit code Gatling — fallback khi không có log | `-DgatlingExit=0` |
| `httpKoCeil` | `SlaConstants.PR5_ERROR_PERCENT_MAX` (1.0) | ✗ | Max % HTTP KO chấp nhận (PR-5) | `-DhttpKoCeil=0.5` |
| `meanMsCeil` | `SlaConstants.PR4_MEAN_RESPONSE_MS_MAX` (500) | ✗ | Max mean response time ms (PR-4) | `-DmeanMsCeil=300` |

**Cách verdict được tính** (`ThresholdVerifier.java`):
```
PASS  ⟺  cpu_p95 ≤ cpu_ceil[variant]                       (variant CPU)
       ∧  mem_p95 ≤ mem_ceil[variant]                       (variant Mem)
       ∧  max_consecutive_failures < 3                      (PR-3: crash, > 6s downtime)
       ∧  mean_response_ms ≤ meanMsCeil                     (PR-4: mean response)
       ∧  (http_ko% ≤ httpKoCeil OR gatlingExit == 0)       (PR-5: error rate)
```

**5 tiêu chí pass/fail** sẽ xuất hiện trong `verdict.json` + `summary.html`:

| Field | Ceiling | Default | Nguồn | Tương ứng |
|---|---|---|---|---|
| `pr4_pass` | `mean_ms_ceil` | 500 ms | `config/sla-thresholds.yml` → `sla.pr4_mean_response_ms_max` | **PR-4** Mean response |
| `pr5_pass` | `http_ko_ceil` | 1.0 % | `config/sla-thresholds.yml` → `sla.pr5_error_percent_max` | **PR-5** Error rate |
| `cpu_pass` | `cpu_ceil` | theo variant | `config/sla-thresholds.yml` → `variants.<name>.cpu_ceil` | CPU p95 (resource) |
| `mem_pass` | `mem_ceil` | theo variant | `config/sla-thresholds.yml` → `variants.<name>.mem_ceil` | Mem p95 (resource) |
| `crash_pass` | `< N consecutive` | 3 | `config/sla-thresholds.yml` → `crash.max_consecutive_failures` | PR-3 Health probe |

### Đổi tiêu chí (không cần code)

**File chính: `config/sla-thresholds.yml`** ở repo root — áp dụng cho mọi game. Sửa số, save, chạy lại test — không cần compile.

```yaml
sla:
  pr4_mean_response_ms_max: 500      # ← đổi PR-4 mean response ceiling
  pr5_error_percent_max: 1.0          # ← đổi PR-5 error rate ceiling
crash:
  max_consecutive_failures: 3
variants:
  target:
    cpu_ceil: 70                      # ← đổi ceiling production gate
    mem_ceil: 80
```

#### Per-game override (1 game khác tiêu chí với phần còn lại)

Mỗi game có thể tự định nghĩa tiêu chí riêng tại:
```
games/<game>/src/gatling/resources/sla-thresholds.yml
```

File này **chỉ cần list field muốn đổi** — field thiếu sẽ fall back về `config/sla-thresholds.yml` (core).

Ví dụ `games/silkroad/src/gatling/resources/sla-thresholds.yml`:
```yaml
# Silk Road: response time strict hơn (state ít)
sla:
  pr4_mean_response_ms_max: 300
# pr5, crash, variants → inherited từ core
```

Sau khi merge, effective config khi chạy `:games:silkroad:soak`:

| Field | Effective | Nguồn |
|---|---|---|
| `sla.pr4_mean_response_ms_max` | **300** | Game YAML |
| `sla.pr5_error_percent_max` | 1.0 | Core (fall back) |
| `sla.max_duration_buffer_min` | 2 | Core |
| `variants.target.cpu_ceil` | 70 | Core |

**Để biết game nào đang chạy:** Gradle subproject tự inject `-DgameName=<directory_name>` vào Gatling JVM. `run-variant.sh` cũng pass `-DgameName=$GAME` cho verifyVariant. Không cần nhập tay.

#### Thứ tự priority khi load config (top wins, deep merge với layer dưới)

1. `-DslaConfig=/abs/path/file.yml` — full override, short-circuit:
   ```bash
   ./gradlew verifyVariant -DslaConfig=/tmp/strict-sla.yml ...
   ```
2. `games/<gameName>/src/gatling/resources/sla-thresholds.yml` — per-game (nếu `-DgameName` set)
3. `config/sla-thresholds.yml` — core source of truth
4. `classpath:sla-thresholds.yml` — bundled fallback trong JAR
5. `SlaConfig.defaults()` hardcoded — chỉ khi tất cả YAML lỗi/thiếu

Mỗi layer chỉ override field nó list. Mỗi `variant` trong `variants.*` cũng merge per-field (game override `target.cpu_ceil` không ảnh hưởng `target.mem_ceil` hay `baseline.*`).

**Code load 1 lần khi JVM start** (`SlaConfigLoader.load()`), cache trong static — đổi YAML lúc đang chạy không có hiệu lực.

**Verify đã apply:** chạy `./gradlew verifyVariant -DgameName=silkroad ...` → stderr có dòng:
```
[SlaConfig] layered classpath:sla-thresholds.yml
[SlaConfig] layered .../config/sla-thresholds.yml
[SlaConfig] layered .../games/silkroad/src/gatling/resources/sla-thresholds.yml
```
3 dòng = 3 layer đã được merge. Thiếu dòng game = game YAML chưa tồn tại (chỉ dùng core).

**Variant ceiling** (`ThresholdVerifier.java:28-32`):

| Variant | CPU% ceiling | Mem% ceiling | Khi dùng |
|---|---|---|---|
| `baseline` | 50 | 60 | Headroom validation |
| `target` | 70 | 80 | **Production gate** (mặc định) |
| `stress` | 85 | 90 | Saturation behaviour |
| `critical` | 95 | 95 | Pre-failure threshold |

> Lưu ý CPU%: `verify` chia `cpu_pct` cho **số core host** (vì `docker stats` aggregate đa core thành >100%). 12-core, container chiếm 600% CPU = 50% utilisation.

---

### C. Flag CLI cho `./scripts/run-variant.sh` (wrapper)

| Flag | Default | Bắt buộc? | Mô tả | Ví dụ |
|---|---|---|---|---|
| `--game` | — | ✅ | Tên game subproject (`silkroad` \| `bonanza` \| ...). **Không còn default** — quên là script fail rõ ràng. Có thể thay bằng env `GAME=...`. | `--game bonanza` |
| `--variant` | — | ✅ | Pass thẳng vào verifyVariant | `--variant target` |
| `--simulation` | — | ✅ | `Soak` \| `Stress` \| `Spike` \| `Basic` (case-insensitive, map → Gradle alias) | `--simulation Soak` |
| `--container` | — | ✅ | Tên Docker container để monitor (`docker stats`) | `--container game-silk-road-caravans` |
| `--users` | `1000` | ✗ | Forward thành `-Dusers` | `--users 500` |
| `--duration-minutes` | `60` | ✗ | Forward thành `-DdurationMinutes` | `--duration-minutes 30` |
| `--ramp-minutes` | `5` | ✗ | Forward thành `-DrampMinutes` | `--ramp-minutes 1` |
| `--port` | `3005` (bonanza) / `3000` (silkroad/khác) | ✗ | Dùng cho 3 chỗ: (1) forward thành `-Dport=...` cho Gatling, (2) build URL health probe (`http://localhost:$PORT/<path>`), (3) fallback port cho monitor-resources khi container không tồn tại. Tự suy theo `--game`. | `--port 8080` |
| `--parallel` | (off) | ✗ | Forward thành `-Dparallel=true` (Soak) | `--parallel` |
| `--requests` | (unset) | ✗ | Forward thành `-Drequests` (Basic) | `--requests 1000000` |
| `--scenario` | (unset) | ✗ | Forward thành `-Dscenario` (Basic) | `--scenario burst` |

**Env var cho run-variant.sh:**

| Env | Default | Mô tả | Ví dụ |
|---|---|---|---|
| `GAME` | — | Thay thế cho `--game` (flag thắng nếu cả hai cùng set). Không còn default; phải set 1 trong 2. | `GAME=fruit_respin_mania ./scripts/run-variant.sh ...` |

**Biến nội bộ (script tự tính, không pass từ ngoài):**

| Biến | Cách tính | Dùng cho |
|---|---|---|
| `DURATION_SEC` | `durationMinutes*60 + rampMinutes*60 + 120` | Truyền vào monitor + verifier |
| `TIMESTAMP` | `date +%Y%m%d-%H%M%S` | Tên thư mục `target/variants/<variant>-<ts>/` |
| `OUT_DIR` | `${REPO_ROOT}/target/variants/${VARIANT}-${TIMESTAMP}` | Nơi lưu CSV + log + verdict + report |
| `MONITOR_PID` / `HEALTH_PID` | `$!` của background process | Cleanup trap SIGINT/SIGTERM |
| `GATLING_EXIT` | `${PIPESTATUS[0]}` sau tee | Pass vào `-DgatlingExit` |

---

### D. Arg cho `scripts/monitor-resources.sh` (nội bộ — run-variant.sh tự gọi)

Hiếm khi gọi tay; ghi lại để debug:

| Flag | Bắt buộc? | Mô tả |
|---|---|---|
| `--container` | ✅ | Tên container theo dõi |
| `--out` | ✅ | Đường dẫn file CSV output (header: `timestamp,cpu_pct,mem_pct,mem_used,net_io,block_io`) |
| `--interval-sec` | ✗ (5) | Tần suất sample |
| `--duration-sec` | ✅ | Thời gian chạy → tự exit |
| `--fallback-port` | ✗ | Nếu container không tồn tại, monitor host process listening trên port này |

### E. Arg cho `scripts/health-poll.sh` (nội bộ)

| Flag | Bắt buộc? | Mô tả |
|---|---|---|
| `--out` | ✅ | Đường dẫn `health.csv` (header: `timestamp,http_status,total_seconds`) |
| `--interval-sec` | ✗ (2) | Tần suất probe |
| `--duration-sec` | ✅ | Thời gian chạy → tự exit |
| `--url` / `--method` | ✗ | Override probe URL (mặc định auto-detect từ candidates) |

---

### F. Env var phía SUT (Silk Road backend)

Không trong project này, nhưng cần khi start docker — ghi cho đầy đủ:

| Env | Mô tả | Ví dụ |
|---|---|---|
| `ZMQ_PUBLISHER_MOCK` | `true` → bỏ kết nối ZMQ staging, giảm overhead | `ZMQ_PUBLISHER_MOCK=true docker compose up -d` |

---

### G. Ví dụ tổng hợp: hiểu 1 lệnh đầy đủ

```bash
./scripts/run-variant.sh \
  --game silkroad \
  --variant target \
  --simulation Soak \
  --users 1000 \
  --duration-minutes 60 \
  --ramp-minutes 5 \
  --container game-silk-road-caravans
```

Script này flow như sau:

1. Parse flag CLI → set bash var (USERS=1000, DURATION_MINUTES=60, ...)
2. Compute `DURATION_SEC=60*60 + 5*60 + 120 = 3720s` (cho monitor timeout)
3. Tạo `target/variants/target-20260512-101355/`
4. Fork background: `monitor-resources.sh --container game-silk-road-caravans --out .../resource.csv --duration-sec 3720`
5. Fork background: `health-poll.sh --out .../health.csv --duration-sec 3720`
6. Gọi: `./gradlew :games:silkroad:soak -Dusers=1000 -DdurationMinutes=60 -DrampMinutes=5` → Gatling đọc `LoadTestConfig.users=1000`, build injection ramp, chạy assertion `mean ≤ 500ms` + `KO ≤ 1%`
7. Kill background, copy Gatling HTML report
8. Gọi: `./gradlew verifyVariant -DresourceCsv=.../resource.csv -DhealthCsv=.../health.csv -DgatlingLog=.../gatling.log -DgatlingExit=0 -Dvariant=target -Dusers=1000 -DdurationSec=3600` → output `verdict.json`
9. Generate `summary.html`
10. Print artifact paths, exit với mã của Gatling

---

## 11. Cheat sheet

```bash
# Setup once
java -version           # must be 17
./gradlew --version     # Gradle 9.2.1 bundled, no install needed

# Start SUT (Silk Road backend is a SIBLING repo)
cd ../be-silk-road-caravans
ZMQ_PUBLISHER_MOCK=true docker compose up -d
cd -                    # quay về load-test repo

# Smoke (1 min, 50 VU)
./gradlew :games:silkroad:soak -Dusers=50 -DdurationMinutes=1 -DrampMinutes=1

# Test 1 endpoint cụ thể (vd CHỈ spin, không chạy lastSpin/historySummary)
./gradlew :games:silkroad:basic -Dscenario=spin -Dusers=1 -Drequests=1
# Endpoint hợp lệ: spin | last-spin | history-summary
# Tra cứu game khác: games/<game>/src/gatling/java/com/rgp/loadtest/<game>/utils/Endpoints.java

# Production gate (60 min, 1000 VU, with monitor + verdict)
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 \
  --container game-silk-road-caravans

# Bonanza (stateful + closed model — port 3005/contextPath /golden đã có trong game.yml)
./gradlew :games:bonanza:basic -Dscenario=BetLevels -Dusers=1 -Drequests=1
./gradlew :games:bonanza:soak -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1
# Endpoint hợp lệ cho bonanza Basic: BetLevels | ReelStrips | CreateSession | Spin | JackpotPools | HistorySessions
# Bonanza KHÔNG có :stress / :spike (chủ ý — chờ baseline production)

# Bonanza gRPC (WSProxy plugin path, Scala simulation — KHÔNG dùng REST host/port/contextPath)
./gradlew :games:bonanza:grpc -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1 \
  -DgrpcHost=localhost -DgrpcPort=9091

# Bonanza qua wrapper (per-game SLA pr4=500ms / pr5=0.99% tự apply qua --game bonanza; --port 3005 tự suy)
./scripts/run-variant.sh --game bonanza --variant target --simulation Soak \
  --users 5 --duration-minutes 1 --ramp-minutes 1 \
  --container game-golden-boat-bonanza   # đổi tên container theo deploy thực tế

# Add new game (silkroad pattern — stateless)
cp -r games/silkroad games/<my_game>
# edit Endpoints.java, SlotRequests.java, bodies/*.json, settings.gradle
./gradlew :games:<my_game>:basic -Dscenario=spin -Dusers=1 -Drequests=1
# Nếu game cần stateful + closed model → xem games/bonanza/ làm reference (Section 5.9)
# Nếu game cần cả gRPC → clone bonanza thay vì silkroad (xem src/gatling/scala/.../BonanzaGrpcSimulation.scala)
```
