# Design — Thêm game Zero Day vào rgp-game-load-test

**Ngày:** 2026-09-21
**Nguồn khảo sát:** `/Users/yamazaki-ethan/Documents/Projects/be-zero-day`
**Quyết định đã chốt:** gRPC trực tiếp · journey Join + Spin loop · Scala copy-adapt từ mutantmerge (PA-A, ack-only) · SUT local docker

## 1. Bối cảnh & ràng buộc từ backend Zero Day

| Điểm | Chi tiết | Bằng chứng |
|---|---|---|
| Không có REST spin | Spin chỉ qua gRPC `PluginService` port **9103**; REST (`:3000`, context `/api/game/zeroday`) chỉ debug/actuator | `docker-compose.yml:20-22`, `application.yml:1-5,82` |
| Proto tương thích | `app/src/main/proto/plugin_service.proto` cùng `PluginService` (`ConnectAndCall`, `Call`, msgpack `parameters`/`data`) → tái dùng stub + `Codec` của core | `plugin_service.proto:21` |
| pluginName | `yama_01023` | `PluginServiceHandler.java:96`, `application.yml:94` |
| Session | `ConnectAndCall` tạo session Redis từ `user.parameters`; bắt buộc chỉ `agency` + `userId`; token không kiểm tra | `PluginServiceHandler.java:319-324` |
| `Call` cần Join trước | `Call` tra session theo `request.username` (username hoặc sessionId), miss → `SESSION_NOT_FOUND` — không fallback | `PluginServiceHandler.java:661-665` |
| Một session / player | Lock theo `stableGameUserId`, session cũ bị `FORCE_LOGOUT` → mỗi VU một userId riêng | `PluginServiceHandler.java:345-377,1013-1033` |
| Bet input | `bet` (string/number, alias `bet_amount`/`betAmount`), ladder 25 bậc `0.20 … 100.00`; ngoài ladder → `INVALID_BET_AMOUNT` | `SpinHandler.java:346-368`, `SlotConstants.java:160-163` |
| ⚠️ Response = ack | `Call` chạy handler **đồng bộ**, publish kết quả qua ZMQ rồi trả `PluginResponse` **rỗng** → latency đo được = latency xử lý spin thật, nhưng **không đọc được body** | `PluginServiceHandler.java:630-643,728-731` |
| ⚠️ Lỗi nghiệp vụ vô hình | Business error → envelope `{c, message}` đi qua ZMQ, gRPC vẫn OK. Chỉ `UNAVAILABLE` khi chính envelope lỗi không publish được | `MessagePackHelper.java:199-204`, `PluginServiceHandler.java:638-639` |
| Jackpot pending chặn spin | Core Hack trigger → claim pending; 1500/1501 bị từ chối `c=1362` đến khi reveal 1509 hoặc TTL auto-pay ~60s | `docs/FE_CLIENT_API_GUIDE.md` §6.4-6.5 |
| Free spins | Tiếp tục bằng chính cmd 1500 (debit 0) — không cần cmd riêng | `FE_CLIENT_API_GUIDE.md` §4.1 |
| Wallet | `LUIGI_WALLET_ENABLED=false` + profile không phải `trial` → `MockWalletAdapter`, default balance 100000/user | `MockWalletAdapter.java:16-18`, README |
| Health | `GET http://localhost:3000/api/game/zeroday/actuator/health` | `application.yml:33-41` |

## 2. Cấu trúc module

```
games/zeroday/
├── build.gradle                 # copy-adapt games/mutantmerge/build.gradle: java+scala, gatlingRt 3.9.5,
│                                #   JavaExec alias duy nhất `grpc`, FORWARDED_PROPS riêng
└── src/gatling/
    ├── scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala
    └── resources/
        ├── game.yml             # health path /api/game/zeroday/actuator/health
        ├── sla-thresholds.yml   # placeholder — inherit core defaults
        └── logback-test.xml     # copy từ mutantmerge
```

- `settings.gradle`: thêm `include ':games:zeroday'`.
- **Không sửa** `core/`, các game khác, backend.

## 3. Kịch bản mỗi VU — ZeroDayGrpcSimulation

1. **Startup check:** `bet` phải thuộc ladder 25 bậc (so sánh ±0.001) — sai thì throw ngay khi load simulation. Vì lỗi `INVALID_BET_AMOUNT` không hiện thành KO (ack-only), đây là chốt chặn duy nhất.
2. **Feeder:** `userId = "loadtest-" + UUID`.
3. **Join** (gRPC `ConnectAndCall`, cmd 1005): zone `MiniGame`, pluginName `yama_01023`, `user.parameters` msgpack `{agency: "loadtest", userId: <userId>, memberId: <userId>, username: <userId>, reconnect: false}`. `exitHereIfFailed`.
4. **Spin loop** `during(durationMinutes)` + `pace(paceSec)`: gRPC `Call`, `username = <userId>`, data msgpack `{cmd: 1500, bet: "<bet>"}`.
5. **Check:** chỉ gRPC status OK (không có body để kiểm).

Defaults (`-D` override):

| Prop | Default | Ghi chú |
|---|---|---|
| `users` / `durationMinutes` / `rampMinutes` | 1000 / 60 / 2 | như mutantmerge |
| `paceSec` | 5 | |
| `requestRate` / `eventCount` | 50 / 100000 | floor throughput/volume |
| `grpcHost` / `grpcPort` | localhost / **9103** | |
| `bet` | `1.00` | phải thuộc ladder |

- Injection closed model, `maxDuration` + buffer `SlaConstants` — y như mutantmerge.
- Assertions giống mutantmerge **trừ body check**: PR-4 mean, PR-5 error%, `requestsPerSec > requestRate`, `successfulRequests > eventCount`, `details("Spin")` p95 ≤ 800ms + KO ≤ 0.5%, `details("Join")` KO ≤ 0.5%.
- `FORWARDED_PROPS`: `users, durationMinutes, rampMinutes, host, port, paceSec, requestRate, eventCount, grpcHost, grpcPort, bet`.

## 4. Tích hợp wrapper & docs

- `scripts/run-variant.sh`: thêm `zeroday` vào usage/error message, `PORT=3000`, `HEALTH_URL=http://localhost:${PORT}/api/game/zeroday/actuator/health`. Chạy chuẩn:
  ```bash
  ./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
    --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-zero-day
  ```
- `CLAUDE.md`: thêm zeroday vào lệnh compile, lệnh chạy, danh sách alias, mục gRPC runtimes.
- `README.md`: thêm hàng zeroday vào bảng games + setup SUT.

## 5. SUT setup (local)

`be-zero-day/docker-compose.yml` service `game-zero-day` (container `game-zero-day`, ports 3000/9103) cần override cho local:
- `LUIGI_WALLET_ENABLED=false` (`.env.staging` đang bật Luigi); profile bất kỳ khác `trial` (vd `staging`) → mock wallet.
- `CHEAT_ENABLED=false`.
- Mongo / Redis / RabbitMQ trỏ về stack local.
- Logging driver `loki` → đổi sang `json-file` để `docker logs` dùng được cho bước kiểm log (§7).
- `ZMQ_PUBLISHER_ADDRESS` trỏ tới địa chỉ không có subscriber → PUB drop message, không ảnh hưởng spin.

## 6. Error handling

- Join lỗi gRPC → VU dừng, không bắn spin nhiễu.
- Spin: chỉ gRPC non-OK (`UNAVAILABLE`, timeout, connection) → KO.
- Mọi lỗi nghiệp vụ (`SESSION_NOT_FOUND`, `INVALID_BET_AMOUNT`, hết tiền, `1362` jackpot pending) → **gRPC OK, không đếm KO**. Bù bằng: startup check bet (§3.1) + kiểm log backend sau run (§7).

## 7. Verification plan

1. `./gradlew :games:zeroday:gatlingClasses` — compile sạch.
2. Smoke: `./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -DrequestRate=0 -DeventCount=0` → Join/Spin 0 KO.
3. **Log check** (bắt buộc vì ack-only): `docker logs game-zero-day 2>&1 | grep -E "\[gRPC\] (ConnectAndCall|Call) (business )?error" | grep -vc "c=1362"` → 0. Bắt cả lỗi nghiệp vụ lẫn lỗi hệ thống (`[gRPC] Call error` — exception không có chữ "business") của Join và Spin; `c=1362` (jackpot pending) là bình thường nên bị loại.
4. Negative check: `-Dbet=0.33` → simulation fail ngay lúc startup (chứng minh ladder check).
5. Wrapper smoke: `run-variant.sh --game zeroday … --users 5 --duration-minutes 1` → đủ artifacts, `health.csv` toàn 200. Floors `requestRate`/`eventCount` fail ở smoke scale là expected.

## Rủi ro backend load test sẽ lộ / giới hạn đo (ghi nhận, không sửa)

- **Lỗi nghiệp vụ vô hình với Gatling** — KO% chỉ phản ánh lỗi transport; phải đọc log backend để kết luận.
- **Jackpot pending `c=1362`** chặn spin của VU đó ~60s: các spin bị từ chối rẻ hơn spin thật → throughput hơi phồng, latency hơi thấp. Tần suất phụ thuộc tỉ lệ trigger Core Hack.
- `MockWalletAdapter.userBalances` là `ConcurrentHashMap` không bao giờ evict → mỗi VU UUID thêm một entry; không đáng kể ở 1000 VU nhưng tăng theo số run nếu container không restart.

## Assumptions

- SUT local docker, wallet mock, cheat tắt; không chạy staging đợt này.
- Tên module `zeroday`, container `game-zero-day`.
- Journey chỉ spin base (free spins tự chạy qua 1500); buy feature & jackpot reveal để phase sau.
- Chấp nhận ack-only như naga777.

## B (tuỳ chọn, ngoài phạm vi đợt này) — ZMQ SUB error counter

Backend PUB **connect** tới `ZMQ_PUBLISHER_ADDRESS` (subscriber bind). Simulation có thể bind một SUB socket (jeromq) trên host, SUT set `ZMQ_PUBLISHER_ADDRESS=tcp://host.docker.internal:<port>`, rồi đếm envelope theo `c` → assertion trên tỉ lệ `c != 0` và (xa hơn) đọc `progressiveJackpot.winId` để gọi 1509. Đổi lại: thêm dependency jeromq, thread nền trong sim, override env SUT, correlate theo topic `urn:ws:z:{zone}:s:{sessionId}`. Làm khi cần số liệu lỗi nghiệp vụ chính xác.

## Ngoài phạm vi

- Buy feature (cmd 1501), jackpot reveal 1509, ZMQ SUB (mục B), WebSocket qua wsproxy, Stress/Spike, extract base gRPC sim vào core.
