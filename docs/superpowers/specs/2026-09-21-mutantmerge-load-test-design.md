# Design — Thêm game Mutant Merge vào rgp-game-load-test

**Ngày:** 2026-09-21
**Nguồn khảo sát:** `/Users/yamazaki-ethan/Documents/Projects/Stable_Mutant_Merge/be-mutant-merge`
**Quyết định đã chốt:** gRPC trực tiếp · journey Join + Spin loop (super bet tuỳ chọn) · Scala copy-adapt từ naga777 (PA-A) · SUT local docker

## 1. Bối cảnh & ràng buộc từ backend Mutant Merge

| Điểm | Chi tiết | Bằng chứng |
|---|---|---|
| Không có REST spin | Spin chỉ qua gRPC `PluginService` port **9104**; REST chỉ còn health | `application.yml:56`, `docker-compose.yml` (`9104:9104`) |
| Proto tương thích wire | `plugin.proto` cùng không có package, cùng service/method/field với `core/src/main/proto/plugin_service.proto` (core chỉ thêm `InteropType` — additive) → tái dùng stub + `Codec` của core | diff 2 file proto |
| pluginName | `yama_01024` (match `GameRegistry.getGameByPluginName`) | `games/yama_01024.yaml:3` |
| Session | `ConnectHandler` miss session Redis → fallback `user.parameters` (`agency`, `username`, `memberId`) và vẫn đăng ký `TokenRegistry` — không cần seed | `ConnectHandler.resolveIdentity/registerConnection` |
| Một session / player | `SingleSessionEnforcer` theo `agency:stableId` → mỗi VU một userId riêng | `ConnectHandler.registerConnection` |
| Bet input | `betLevelId` **1-based** index vào ladder `[0.25, 0.75, 1.00, …, 10.00]` ($); ngoài ladder → `INVALID_BET` | `BetLadder.amountForIndex`, `yama_01024.yaml:352-354` |
| Super bet | `superBet: true` trong payload → debit × 1.2 | `SpinHandler.resolveDebit`, `yama_01024.yaml:275-278` |
| Response đồng bộ | `Call` trả **toàn bộ kết quả spin** trong `PluginResponse.result` (msgpack), ZMQ push chỉ là bản sao → latency đo được là latency spin thật (khác naga777 chỉ đo ack) | `StandaloneGrpcService.java:518`, `SpinHandler.runSpinAndRespond` |
| Lỗi nghiệp vụ | gRPC status vẫn OK; lỗi nằm trong body: `c != 0` + `message`/`error` | `ErrorEnvelope.isError/writeError` |
| Wallet | `WALLET_GATEWAY=mock` → `MockWalletAdapter` Redis, default balance 100000/user | `MockWalletAdapter.java:32` |
| Health | `GET http://localhost:3000/api/game/mutant-merge/health` (context path mặc định) | `.env.dev`, `CLAUDE.md` backend |
| Jackpot Bio-Vault | Trigger 0.2%, luồng open/pick/collect (1508/1509/1514) **không chặn spin** → ngoài phạm vi | grep orchestrator không có guard |

## 2. Cấu trúc module

```
games/mutantmerge/
├── build.gradle                 # copy-adapt games/naga777/build.gradle: java+scala, gatlingRt 3.9.5,
│                                #   JavaExec alias duy nhất `grpc`, FORWARDED_PROPS riêng
└── src/gatling/
    ├── scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala
    └── resources/
        ├── game.yml             # port 3000 + contextPath /api/game/mutant-merge (health probe)
        ├── sla-thresholds.yml   # placeholder — inherit core defaults
        └── logback-test.xml     # copy từ naga777
```

- `settings.gradle`: thêm `include ':games:mutantmerge'`.
- **Không sửa** `core/`, `games/naga777/`, `games/bonanza/`, backend.

## 3. Kịch bản mỗi VU — MutantMergeGrpcSimulation

1. **Feeder:** `userId = "loadtest-" + UUID`.
2. **Join** (gRPC `ConnectAndCall`, cmd 1005): zone `MiniGame`, pluginName `yama_01024`, `user.parameters` msgpack `{agency: "loadtest", username: <userId>, memberId: <userId>, reconnect: false}`. `exitHereIfFailed`.
3. **Spin loop** `during(durationMinutes)` + `pace(paceSec)`: gRPC `Call`, data msgpack `{cmd: 1500, betLevelId: "<betLevelId>"}` (+ `superBet: true` khi `-DsuperBet=true`).
4. **Check body** (Join không có body lỗi — trả `ZmqResponse`): Spin decode `result` bằng `Codec.decodeToMap`, **KO khi `c != 0`** với message = `message` field. Không có check này, INVALID_BET / hết tiền sẽ bị đếm là OK.

Defaults (`-D` override):

| Prop | Default | Ghi chú |
|---|---|---|
| `users` / `durationMinutes` / `rampMinutes` | 1000 / 60 / 2 | như naga777 |
| `paceSec` | 5 | |
| `requestRate` / `eventCount` | 50 / 100000 | floor throughput/volume |
| `grpcHost` / `grpcPort` | localhost / **9104** | |
| `betLevelId` | 3 | = $1.00 |
| `superBet` | false | |

- Injection closed model, `maxDuration` + buffer `SlaConstants` — y như naga777.
- Assertions giống naga777: PR-4 mean, PR-5 error%, `requestsPerSec > requestRate`, `successfulRequests > eventCount`, `details("Spin")` p95 ≤ 800ms + KO ≤ 0.5%, `details("Join")` KO ≤ 0.5%.
- `FORWARDED_PROPS`: `users, durationMinutes, rampMinutes, host, port, paceSec, requestRate, eventCount, grpcHost, grpcPort, betLevelId, superBet`.

## 4. Tích hợp wrapper & docs

- `scripts/run-variant.sh`: thêm case `mutantmerge` → `HEALTH_URL=http://localhost:${PORT}/api/game/mutant-merge/health`. Chạy chuẩn:
  ```bash
  ./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc \
    --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-mutant-merge
  ```
- `CLAUDE.md`: thêm mutantmerge vào lệnh compile, lệnh chạy, danh sách alias, mục gRPC runtimes.
- `README.md`: thêm hàng mutantmerge vào bảng games + setup SUT.

## 5. SUT setup (local)

Backend repo: build image + chạy với Mongo/Redis (`make up-full` / `docker-compose.full.yml`), env `WALLET_GATEWAY=mock`, `CHEAT_ENABLED=false`, `GRPC_PORT=9104`. ZMQ không có subscriber → PUB drop message, không ảnh hưởng. Container `game-mutant-merge`, `mem_limit 640m`.

## 6. Error handling

- Join lỗi gRPC → VU dừng, không bắn spin nhiễu.
- Spin: gRPC non-OK hoặc `c != 0` → KO, gộp vào PR-5; `message` hiện trong Gatling error table để chẩn đoán.
- `betLevelId` ngoài 1..14 → `INVALID_BET` → KO 100% ở smoke 1 user (phát hiện ngay).

## 7. Verification plan

1. `./gradlew :games:mutantmerge:gatlingClasses` — compile sạch.
2. Smoke: `./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -DrequestRate=0 -DeventCount=0` → Join/Spin 0 KO.
3. Negative check: `-DbetLevelId=99` → Spin KO với `INVALID_BET` (chứng minh body check hoạt động).
4. Wrapper smoke: `run-variant.sh --game mutantmerge … --users 5 --duration-minutes 1` → đủ artifacts, `health.csv` toàn 200. Floors `requestRate`/`eventCount` fail ở smoke scale là expected (như naga777).

## Rủi ro backend load test sẽ lộ (ghi nhận, không sửa)

- `SpinRequestQueue` tạo một single-thread executor mỗi `agency:userId` và không remove khi disconnect → 1000 VU UUID khác nhau = ~1000 thread trong container 640 MB (`-Xss512k`). Theo dõi mem/thread ở run `target`.

## Assumptions

- SUT local docker, wallet mock, cheat tắt; không chạy staging đợt này.
- Tên module `mutantmerge`, container `game-mutant-merge`.
- Journey chỉ spin (+ super bet tuỳ chọn); buy feature & jackpot flow để phase sau.

## Ngoài phạm vi

- Buy feature (cmd 1501, 100× bet), Bio-Vault jackpot flow, WebSocket qua wsproxy, Stress/Spike, extract base gRPC sim vào core (đã có 3 game gRPC — cân nhắc ở đợt riêng).
