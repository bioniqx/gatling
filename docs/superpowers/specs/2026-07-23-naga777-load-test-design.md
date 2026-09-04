# Design — Thêm game NAGAS 777 vào rgp-game-load-test

> **Cập nhật 2026-09-04 — journey đã bỏ bước RegisterSession.**
> Backend `Stable_NAGAS_777` đã đổi package sang `yama.game.nagafortune.*` và gỡ hẳn
> `DebugController`: REST surface hiện chỉ còn `/health` + `/health/detail`, debug ops
> chuyển sang gRPC cmd 1900 (`SESSION_REGISTER`), mà cmd 1900 không nằm trong
> `PUBLIC_COMMANDS` nên phải Connect trước — không dùng để seed session được.
> Journey vì vậy thuần gRPC: Join (cmd 1005) → Spin loop (cmd 1500). `ConnectHandler`
> miss session Redis thì fallback sang `user.parameters` (agency/username) rồi vẫn
> đăng ký TokenRegistry, balance đến từ MockWalletAdapter. Mọi mô tả về
> `POST /api/v1/debug/session/register` bên dưới là lịch sử, không còn đúng.

**Ngày:** 2026-07-23
**Nguồn khảo sát:** `/Users/yamazaki-ethan/Documents/Projects/Stable_NAGAS_777`
**Quyết định đã chốt:** load test qua gRPC trực tiếp · kịch bản Connect + Spin loop · simulation viết bằng Scala theo pattern bonanza (PA2)

## 1. Bối cảnh & ràng buộc từ backend NAGAS 777

| Điểm | Chi tiết | Bằng chứng |
|---|---|---|
| Không có REST spin | Spin (cmd 1500) chỉ qua gRPC `PluginService` port **9096** hoặc WebSocket wsproxy 9099 | `StandaloneGrpcService.java:174-216` |
| Proto tương thích | `plugin.proto` của naga có `PluginService` giống hệt `core/src/main/proto/plugin_service.proto` — tái dùng stub + `Codec` (MessagePack) của core | so sánh 2 proto |
| REST port 3000 | Context path bị override thành `""` trong docker-compose; có `/health`, `/api/v1/config`, history, debug | `docker-compose.yml:65` |
| Session bắt buộc | Spin cần session token có sẵn trong Redis; seed qua `POST /api/v1/debug/session/register` (header `X-Debug-Token: slot-engine-debug`) → trả `sessionToken`, TTL 24h, balance 10M | `DebugController.java:236-286` |
| Một session / user | Backend giữ `lock:player:{userId}` — mỗi VU phải dùng userId riêng | `DebugController.java` |
| Bet input | Spin chỉ nhận `betLevelId` (= coinPerLine, 1..10) + `coinValueId` (= coinValue ∈ {1,5,20,50,100,200,500}); raw `betAmount` bị reject. Server tự derive `betAmount = coinValue × coinPerLine × 5` | `SpinHandler.java:100-105`, `BetWhitelist.java` |
| pluginName | Phải là `game-naga-fortune-777` (match `GameRegistry.getGameByPluginName`) | `games/game-naga-fortune-777.yaml:3` |
| Session token key | `ConnectAndCall.user.parameters` msgpack: key `token` → resolve session Redis | `ConnectHandler.java:42` |
| ⚠️ ZMQ ack | Spin qua gRPC `Call`: reply chỉ là ack, kết quả thật push qua ZMQ. Latency đo được = ack latency; tải engine vẫn là thật | `FE_CMD_GUIDE.md` cmd 1500 |
| Docker | `docker compose up -d --build` tại repo naga; container game: `stable-naga_fortune_777`; wallet mock sẵn — chạy offline được | `docker-compose.override.yml:31-46` |

## 2. Cấu trúc module

```
games/naga777/
├── build.gradle                        # copy-adapt từ games/bonanza/build.gradle:
│                                       #   gatling + scala plugin, scala-library,
│                                       #   alias duy nhất: grpc
└── src/gatling/
    ├── scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala
    └── resources/
        ├── game.yml                    # placeholder — port 3000 trùng core defaults (như silkroad)
        └── sla-thresholds.yml          # placeholder — inherit core defaults
```

(Bonanza không có `gatling.conf`/`logback-test.xml` riêng — naga777 cũng không cần.)

- `settings.gradle`: `include ':games:naga777'` (thay dòng placeholder `naga-fortune-777`; tên ngắn theo convention `silkroad`/`bonanza`).
- Chỉ có alias `grpc` — naga không có REST spin nên không có `soak`/`basic` REST.
- **Không sửa** `core/` và `games/bonanza/`.

## 3. Kịch bản mỗi VU — Naga777GrpcSimulation

Journey (mirror shape của `BonanzaGrpcSimulation.scala`, thêm bước register):

1. **Feeder**: `userId = "loadtest-" + UUID` (mỗi VU một userId — tránh đụng `lock:player:`).
2. **RegisterSession** (HTTP): `POST http://{host}:{port}/api/v1/debug/session/register`, header `X-Debug-Token: slot-engine-debug`, body `{"agency":"loadtest","userId":"<userId>"}` → check 200, `saveAs("sessionToken")`.
3. **Join** (gRPC `ConnectAndCall`, cmd 1005): `PluginUser.parameters` = msgpack map `{token: <sessionToken>, username, reconnect:false}` (đã đối chiếu `ConnectHandler.java:42`), `pluginName = "game-naga-fortune-777"`, zone `MiniGame`.
4. **Spin loop**: `during(durationMinutes)` → gRPC `Call` với msgpack `{cmd:1500, betLevelId:"<coinPerLine>", coinValueId:"<coinValue>"}`, `pace(paceSec)` (đã đối chiếu `SpinHandler` — raw betAmount không còn được nhận).

Defaults (`-D` override được):

| Prop | Default | Ghi chú |
|---|---|---|
| `users` / `durationMinutes` / `rampMinutes` | 1000 / 60 / 2 | như bonanza grpc |
| `paceSec` | 5 | |
| `requestRate` / `eventCount` | 50 / 100000 | floor throughput/volume |
| `grpcHost` / `grpcPort` | localhost / **9096** | khác bonanza (9091) |
| `host` / `port` | localhost / 3000 | REST register; từ `game.yml` |
| `coinValue` / `coinPerLine` | 5 / 3 | gửi lên wire dạng `coinValueId`/`betLevelId`; server derive bet = 5×3×5 = 75 |

- Injection: closed model — `rampConcurrentUsers(0→users)` trong `rampMinutes` + `constantConcurrentUsers(users)` trong `durationMinutes`; `maxDuration` cộng buffer từ `SlaConstants`.
- Protocols: HTTP (register) + gRPC trong cùng `setUp` — Gatling 3.15 hỗ trợ multi-protocol.
- Assertions (giống bonanza grpc): PR-4 mean ≤ `SlaConstants.PR4…`, PR-5 error% ≤ `PR5…`, `requestsPerSec > requestRate`, `successfulRequests > eventCount`, `details("Spin")` p95 ≤ 800ms + KO ≤ 0.5%, `details("Join")` KO ≤ 0.5%. Bước RegisterSession: KO ≤ 0.5%.

## 4. Tích hợp wrapper & config

- `scripts/run-variant.sh`: thêm case `naga777` — `PORT=3000`, `HEALTH_URL=http://localhost:3000/health`. Chạy chuẩn:
  ```bash
  ./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
    --users 1000 --duration-minutes 60 --ramp-minutes 2 \
    --container stable-naga_fortune_777
  ```
- `FORWARDED_PROPS` (build.gradle naga777): `users, durationMinutes, rampMinutes, host, port, paceSec, requestRate, eventCount, grpcHost, grpcPort, coinValue, coinPerLine`.
- Wrapper không expose `--grpc-host/--grpc-port` (giống hạn chế bonanza) — cần override thì chạy Gradle trực tiếp.
- Docs cập nhật: bảng "Games today" trong `README.md` (thêm hàng naga777: env vars, port, health URL, container, lệnh smoke) + 1 dòng alias trong `CLAUDE.md`.

## 5. Error handling

- RegisterSession fail (non-200 / thiếu `sessionToken`) → VU dừng (`exitHereIfFailed`) — không bắn spin với session hỏng, tránh nhiễu error-rate của Spin.
- gRPC status non-OK trong Spin/Join → Gatling tự đánh KO, gộp vào PR-5.
- Sai bet formula → backend reject → hiện thành KO ở Spin (phát hiện được ngay ở smoke 1 user).

## 6. Verification plan

1. `./gradlew :games:naga777:gatlingClasses` — compile sạch.
2. Docker stack naga chạy local → smoke: `./gradlew :games:naga777:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0` → 0 KO, có response Spin.
3. Wrapper smoke: `run-variant.sh --game naga777 --variant target --simulation Grpc --users 5 --duration-minutes 1 ...` → pipeline sinh đủ artifacts (`summary.html`, `resource.csv`, `health.csv` toàn 200), RegisterSession/Join/Spin 0 KO. Lưu ý: 2 assertion floor `requestRate`/`eventCount` là ngưỡng production-scale — ở smoke scale chúng fail là **expected** (wrapper không forward 2 prop này); verdict PASS đầy đủ chỉ áp dụng ở run production. Smoke trực tiếp qua Gradle (bước 2) override được floors qua `-D`.

## Ngoài phạm vi (ghi nhận, không làm đợt này)

- WebSocket path qua wsproxy 9099 (đo full round-trip) — phase sau nếu cần.
- Stress/Spike cho naga — chờ baseline như bonanza.
- Extract base gRPC simulation chung vào core — chờ "rule of three".
