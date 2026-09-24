# HƯỚNG DẪN SỬ DỤNG — Test Tải Game

> Tài liệu này hướng dẫn bạn **từng bước một** để chạy test tải (load test) cho game backend, không cần biết lập trình. Chỉ cần biết mở Terminal và copy-paste lệnh là làm được.

---

## 📌 Mục lục

1. [Đây là cái gì?](#1-đây-là-cái-gì)
2. [Ai nên đọc?](#2-ai-nên-đọc)
3. [Một số từ ngữ cần biết](#3-một-số-từ-ngữ-cần-biết)
4. [Chuẩn bị máy tính](#4-chuẩn-bị-máy-tính)
5. [Khởi động Game Backend (BẮT BUỘC trước khi test)](#5-khởi-động-game-backend-bắt-buộc-trước-khi-test)
6. [Chạy test đầu tiên trong 5 phút](#6-chạy-test-đầu-tiên-trong-5-phút)
7. [Đọc và hiểu kết quả](#7-đọc-và-hiểu-kết-quả)
8. [Các loại test khác nhau](#8-các-loại-test-khác-nhau)
9. [Khi gặp lỗi — Làm gì?](#9-khi-gặp-lỗi--làm-gì)
10. [Câu hỏi thường gặp](#10-câu-hỏi-thường-gặp)

---

## 1. Đây là cái gì?

Hãy tưởng tượng game của bạn giống như **một quán phở**:

- Bình thường có 10 khách ngồi ăn → phục vụ nhanh.
- Tới giờ cao điểm có 500 khách cùng vào → có thể chậm, có thể đổ vỡ.
- Bạn muốn biết: **Quán mình chịu được bao nhiêu khách cùng lúc trước khi quá tải?**

**Project này = "Đoàn người máy ảo" giúp bạn trả lời câu hỏi đó.**

Cụ thể, nó sẽ:
- ✅ Giả lập **hàng trăm tới hàng nghìn người chơi ảo** cùng vào game một lúc.
- ✅ Đo thời gian server phản hồi mỗi lần "quay" (spin).
- ✅ Đếm số lượt bị lỗi.
- ✅ Đo CPU và RAM của server.
- ✅ Xuất **báo cáo PASS / FAIL** rõ ràng để biết game có sẵn sàng lên production hay không.

Tất cả tự động, chỉ cần 1 lệnh duy nhất.

---

## 2. Ai nên đọc?

| Bạn là... | Có nên đọc tài liệu này? |
|---|---|
| **QC / Tester** | ✅ Có. Bạn sẽ chạy được test sau khi đọc. |
| **PM / Quản lý dự án** | ✅ Có. Để hiểu kết quả test nghĩa là gì. |
| **DevOps mới** | ✅ Có. Để biết quy trình. |
| **Lập trình viên muốn thêm game mới** | ❌ Nên đọc thêm `README.md` (tiếng Anh) hoặc `docs/getting-started.md` (chi tiết hơn). |

---

## 3. Một số từ ngữ cần biết

Đọc bảng này 1 lần, sau đó các phần sau sẽ dễ hiểu hơn.

| Từ | Nghĩa đơn giản |
|---|---|
| **Backend / SUT** | Máy chủ game đang được test. SUT = "System Under Test" = "Hệ thống đang bị kiểm tra". |
| **Game** | Trò chơi slot. Đã có nhiều game (xem `README.md` để biết danh sách đầy đủ); tài liệu này dùng **Silk Road Caravans** và **Golden Boat Bonanza** làm ví dụ chính. |
| **VU (Virtual User)** | "Người chơi ảo" do máy tạo ra để giả lập tải. 1000 VU = 1000 người chơi cùng lúc. |
| **Spin** | Một lượt quay slot. Test sẽ giả lập rất nhiều spin. |
| **Test / Load test** | Bài kiểm tra giả lập đông người chơi. |
| **Simulation** | "Kịch bản test". Có 5 kịch bản: Basic, Soak, Stress, Spike, Grpc (xem mục 8). Grpc có ở cả Silk Road và Bonanza (và các game gRPC-only khác); Basic/Soak/Stress/Spike chỉ có ở Silk Road. |
| **Variant** | "Mức độ kỳ vọng". 4 mức: baseline (rất dư), target (production), stress (gần đầy), critical (sát giới hạn). |
| **PASS / FAIL** | Test đạt / không đạt. Xanh = đạt, đỏ = không đạt. |
| **Terminal** | Cửa sổ đen dùng để gõ lệnh. Trên Mac: Cmd + Space → gõ "Terminal" → Enter. |
| **Docker** | Phần mềm chạy backend trên máy của bạn mà không cần cài đặt phức tạp. |

---

## 4. Chuẩn bị máy tính

### 4.1. Máy bạn cần gì?

- **Hệ điều hành**: macOS hoặc Linux. (Windows chưa thử nghiệm.)
- **RAM**: tối thiểu 8 GB. Tốt nhất 16 GB.
- **Internet**: để tải Docker images lần đầu (sau đó chạy offline được).

### 4.2. Cài 4 thứ này (1 lần duy nhất)

| # | Cần cài | Tải ở đâu | Cách kiểm tra đã cài chưa |
|---|---|---|---|
| 1 | **Java 17** | https://adoptium.net | Mở Terminal → gõ `java -version` → thấy `17.x` là OK |
| 2 | **Docker Desktop** | https://www.docker.com/products/docker-desktop | Gõ `docker --version` → thấy `20.x` trở lên là OK |
| 3 | **Python 3** | Thường đã có sẵn trên Mac/Linux | Gõ `python3 --version` → thấy `3.9` trở lên là OK |
| 4 | **Code của project này** | Đã có rồi (folder bạn đang ở) | Trong Terminal, gõ `ls` thấy file `README.md`, `HUONG-DAN.md`... là OK |

> 💡 **Mẹo**: Nếu kiểm tra thấy thiếu cái nào, hỏi đồng nghiệp dev cài giúp — không phải việc bạn phải tự xoay.

### 4.3. Mở Terminal và đi vào thư mục project

```bash
cd ~/Documents/Projects/BONANZA-LOADTEST/rgp-game-load-test
```

> Đường dẫn trên là ví dụ — thay bằng đường dẫn thật trên máy bạn. Trên Mac có thể kéo thả folder vào Terminal sau khi gõ `cd `.

Kiểm tra mình đã ở đúng chỗ:

```bash
ls
```

Phải thấy các thứ: `README.md`, `HUONG-DAN.md`, `games`, `scripts`, `gradlew`, v.v.

---

## 5. Khởi động Game Backend (BẮT BUỘC trước khi test)

> ⚠️ **Quan trọng**: Bạn KHÔNG THỂ chạy load test nếu **game backend chưa được bật**. Giống như không thể đo "quán phở chịu được bao nhiêu khách" khi quán chưa mở cửa.

### 5.1. Backend nằm ở đâu?

Backend của game **không nằm trong folder load test** này, mà ở **folder kế bên** (sibling folder):

```
BONANZA-LOADTEST/                       ← Folder cha (chứa tất cả)
├── be-silk-road-caravans/              ← Backend Silk Road
├── be-golden-boat-bonanza/             ← Backend Bonanza
└── rgp-game-load-test/                 ← Folder load test (bạn đang ở đây)
```

Quy trình chuẩn:
1. `cd` sang folder backend tương ứng.
2. Bật Docker → đợi backend khởi động.
3. Kiểm tra backend đã sống.
4. `cd` quay lại folder load test → chạy test.

> 💡 **Mở 2 cửa sổ Terminal sẽ tiện hơn**: 1 cửa sổ để chạy backend, 1 cửa sổ để chạy test. Không phải `cd` qua lại.

---

### 5.2. Khởi động Silk Road Caravans

**Bước 1 — Đi vào folder backend**

```bash
cd ../be-silk-road-caravans
```

Nếu lệnh trên báo lỗi `No such file or directory` = bạn không đang ở folder load test. Dùng đường dẫn tuyệt đối:

```bash
cd ~/Documents/Projects/BONANZA-LOADTEST/be-silk-road-caravans
```

(Đổi `~/Documents/Projects/BONANZA-LOADTEST/` thành đường dẫn thật trên máy bạn.)

**Bước 2 — Bật backend bằng Docker**

```bash
ZMQ_PUBLISHER_MOCK=true docker compose up -d
```

Giải thích lệnh:
- `ZMQ_PUBLISHER_MOCK=true` → bỏ qua phần ZMQ (không cần cho test).
- `docker compose up` → khởi động toàn bộ container (game backend + MongoDB + Redis + ...).
- `-d` → chạy ngầm (background), không chiếm Terminal.

> ⏳ **Lần đầu chạy** sẽ tải Docker images (~2-5 phút tuỳ tốc độ mạng). Các lần sau chỉ vài giây.

**Bước 3 — Đợi backend khởi động xong**

Đợi khoảng **30 giây** cho Spring Boot start.

Muốn xem tiến trình real-time?

```bash
docker logs -f game-silk-road-caravans
```

Khi thấy dòng kiểu `Started ... in X seconds` hoặc `Tomcat started on port(s): 3000` = game đã sẵn sàng. Bấm `Ctrl + C` để thoát log (game vẫn chạy nền).

**Bước 4 — Xác nhận backend đã sống**

```bash
curl -s -X POST http://localhost:3000/api/game/caravans/v1/slot/spin \
  -H 'content-type: application/json' \
  -d '{"userId":"smoke","gameId":"game-silk-road-caravans","betAmount":1.0,"isBuyFeature":false,"isCheatJackpot":false,"freeGameSplittingSymbol":"A"}'
```

✅ Thấy JSON có `winAmount`, `balance`, `matrix` → game OK, sẵn sàng test.
❌ Báo `Connection refused` → đợi thêm 30s rồi thử lại. Vẫn lỗi → xem [mục 5.5](#55-lỗi-thường-gặp-khi-khởi-động).

---

### 5.3. Khởi động Golden Boat Bonanza

**Bước 1 — Đi vào folder backend**

```bash
cd ../be-golden-boat-bonanza
```

Hoặc đường dẫn tuyệt đối:

```bash
cd ~/Documents/Projects/BONANZA-LOADTEST/be-golden-boat-bonanza
```

**Bước 2 — Bật backend bằng Docker**

```bash
docker compose up -d
```

> Bonanza **không cần** `ZMQ_PUBLISHER_MOCK=true` (khác Silk Road).

**Bước 3 — Đợi backend khởi động xong**

Đợi khoảng **30 giây**. Xem log nếu muốn:

```bash
docker logs -f game-golden-boat-bonanza
```

Tìm dòng `Started ... in X seconds`.

**Bước 4 — Xác nhận backend đã sống**

> ⚠️ Bonanza là **gRPC-only**, không có cổng HTTP để `curl` như Silk Road. Kiểm tra bằng trạng thái Docker healthcheck thay vì `curl`:

```bash
docker ps --filter name=game-golden-boat-bonanza
```

✅ Cột `STATUS` ghi `Up ... (healthy)` → game OK, sẵn sàng test.
❌ Vẫn `(health: starting)` sau 30s, hoặc `(unhealthy)` → xem [mục 5.5](#55-lỗi-thường-gặp-khi-khởi-động).

---

### 5.4. Quay lại folder load test

Sau khi backend đã chạy, quay lại folder load test để chạy test:

```bash
cd ../rgp-game-load-test
```

Hoặc dùng `cd -` (quay về folder trước đó).

Kiểm tra mình đang ở đúng nơi:

```bash
pwd     # phải thấy đường dẫn kết thúc bằng /rgp-game-load-test
ls      # phải thấy các thứ: scripts/, games/, gradlew, README.md, HUONG-DAN.md
```

---

### 5.5. Lỗi thường gặp khi khởi động

| Bạn thấy | Có thể do | Cách xử lý |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop chưa bật. | Mở app **Docker Desktop**, đợi icon ổn định (~30s), thử lại. |
| `port is already allocated` / `bind: address already in use` | Cổng 3000 (Silk Road) hoặc 9091 (Bonanza gRPC) đang bị chiếm. | `lsof -ti :3000 \| xargs kill -9` (thay 3000 bằng cổng đang lỗi). |
| `pull access denied` / `unauthorized` | Image Docker private, cần đăng nhập registry. | `docker login` (hỏi dev lấy username/password). |
| Container `Restarting` liên tục (xem `docker ps`) | Game crash lúc start (thiếu env var, sai config...). | `docker logs game-...` để xem lỗi, báo dev. |
| `no such file: docker-compose.yml` | Đang sai folder. | `pwd` để kiểm tra. Phải đang ở `be-silk-road-caravans` hoặc `be-golden-boat-bonanza`. |
| `curl: (7) Failed to connect` sau 30 giây | Backend khởi động lâu hơn bình thường (máy yếu, hoặc lỗi). | Đợi thêm 1-2 phút. Vẫn lỗi → xem `docker logs game-...`. |

---

### 5.6. Lệnh quản lý backend hữu ích

**Xem container đang chạy:**

```bash
docker ps --filter name=game-
```

Cột `STATUS` ghi `Up X minutes` = đang chạy. Không thấy gì = chưa chạy.

**Xem log 50 dòng cuối:**

```bash
docker logs game-silk-road-caravans 2>&1 | tail -50
# hoặc
docker logs game-golden-boat-bonanza 2>&1 | tail -50
```

**Restart backend (khi nghi ngờ kẹt):**

```bash
docker compose restart        # chạy trong folder backend
```

**Dừng backend khi test xong** (giải phóng RAM/CPU):

```bash
# Silk Road
cd ../be-silk-road-caravans
docker compose down

# Bonanza
cd ../be-golden-boat-bonanza
docker compose down
```

> 💡 `docker compose down` chỉ tắt container, **không xoá dữ liệu**. Lần sau bật lại là có ngay.

---

## 6. Chạy test đầu tiên trong 5 phút

> ✅ **Trước khi đọc mục này**, bạn phải đã làm xong [mục 5](#5-khởi-động-game-backend-bắt-buộc-trước-khi-test) — tức là game backend đang chạy. Nếu chưa, quay lại mục 5.

Có 2 game đang được hỗ trợ. Mục này dùng ví dụ **Silk Road Caravans** (đơn giản hơn). Cuối mục sẽ ghi chú khác biệt khi chạy Bonanza.

### Bước 1 — Đảm bảo bạn ở folder load test

```bash
pwd     # phải kết thúc bằng /rgp-game-load-test
```

Nếu không phải → `cd` về:

```bash
cd ~/Documents/Projects/BONANZA-LOADTEST/rgp-game-load-test
```

### Bước 2 — Chạy test smoke (1 phút)

Đây là test ngắn nhất, để xác nhận mọi thứ chạy được trước khi chạy test thật.

```bash
./scripts/run-variant.sh \
  --game silkroad \
  --variant target \
  --simulation Soak \
  --users 50 \
  --duration-minutes 1 \
  --ramp-minutes 0 \
  --container game-silk-road-caravans
```

**Lệnh trên có nghĩa là**:
- `--game silkroad` → chọn game subproject (bắt buộc — `silkroad` hoặc `bonanza`; trước đây mặc định silkroad gây nhầm lẫn khi muốn chạy bonanza).
- `--variant target` → so với chuẩn production.
- `--simulation Soak` → kịch bản "ngâm" (chạy đều).
- `--users 50` → tạo 50 người chơi ảo.
- `--duration-minutes 1` → chạy trong 1 phút.
- `--ramp-minutes 0` → không cần "khởi động dần", bật 50 người cùng lúc luôn.
- `--container game-silk-road-caravans` → tên container Docker đang chạy backend (đo CPU/RAM của nó).

**Trong khi chạy**, Terminal sẽ in các dòng kiểu:

```
> Global         (OK=1499  KO=0)
silk-road-session-journey: 50.0 / 50.0
```

- `OK=1499` = 1499 request thành công ✅
- `KO=0` = 0 request lỗi ✅ (đây là điều bạn muốn!)

Khoảng **80 giây** sau bạn sẽ thấy `BUILD SUCCESSFUL`.

### Bước 3 — Xem báo cáo

Cách 1: Copy dán đường dẫn file html trong terminal vào trình duyệt\
Cách 2: Lệnh dưới đây tự mở báo cáo trong trình duyệt:

```bash
open "$(ls -td target/variants/silkroad/target-* | head -1)/summary.html"
```

Bạn sẽ thấy **một trang web**:
- 🟢 Banner màu xanh ghi **PASS** = Test đạt.
- 🔴 Banner màu đỏ ghi **FAIL** = Test không đạt (xem mục 7 để biết tại sao).
- Bảng các tiêu chí pass/fail.
- Biểu đồ CPU và RAM theo thời gian.
- Báo cáo Gatling chi tiết bên dưới.

### 🎉 Xong! Bạn vừa chạy test đầu tiên.

---

### Khác biệt khi chạy game Bonanza

Bonanza là **gRPC-only** (không có REST API), nên dùng `--simulation Grpc` thay vì `Soak`, và không có cổng HTTP để healthcheck qua URL — script tự chờ Docker healthcheck của container thay thế:

```bash
# Khởi động backend Bonanza (xem mục 5.3 để biết chi tiết)
cd ../be-golden-boat-bonanza
docker compose up -d

# Quay lại folder load test
cd ../rgp-game-load-test

# Chạy test (chú ý --game bonanza và --simulation Grpc)
./scripts/run-variant.sh \
  --game bonanza \
  --variant target \
  --simulation Grpc \
  --users 50 \
  --duration-minutes 1 \
  --ramp-minutes 0 \
  --container game-golden-boat-bonanza
```

> 💡 Khác biệt chính: `--game bonanza`, `--simulation Grpc` (Bonanza không còn kịch bản REST nào), tên container là `game-golden-boat-bonanza`. Form cũ `GAME=bonanza ./scripts/run-variant.sh ...` cũng vẫn dùng được.

---

## 7. Đọc và hiểu kết quả

### 7.1. Mở file `summary.html`

Trang báo cáo gồm các phần (từ trên xuống):

| Phần | Nói cái gì? |
|---|---|
| **Verdict banner** | Tổng kết: PASS (xanh) hay FAIL (đỏ). |
| **Run Info** | Thông tin chạy: game nào, bao nhiêu user, bao lâu... |
| **Threshold Check** | 6 dòng tiêu chí. **Tất cả phải pass thì cả test mới pass.** |
| **HTTP Requests Summary** | Tổng request gửi, OK bao nhiêu, lỗi bao nhiêu. |
| **CPU % over time** | Biểu đồ CPU theo thời gian. |
| **Memory % over time** | Biểu đồ RAM theo thời gian. |
| **Gatling HTTP Report** | Báo cáo chi tiết (nhấn nút "Fullscreen" để mở rộng). |

### 7.2. 6 tiêu chí phải đạt

| Tiêu chí | Nghĩa đơn giản | Ngưỡng mặc định |
|---|---|---|
| **PR-4: Mean response** | Thời gian phản hồi trung bình phải nhanh. | ≤ 500 ms (Silkroad: 300 ms) |
| **PR-5: Error rate** | Tỉ lệ request lỗi phải thấp. | ≤ 1.0 % (Bonanza: 0.99 %) |
| **CPU p95** | 95% thời gian CPU không được quá tải. | ≤ 70% (variant target) |
| **Mem p95** | 95% thời gian RAM không được quá tải. | ≤ 80% (variant target) |
| **PR-3: Crash detection** | Server không bị sập liên tục quá ngưỡng. | < 3 lần sập liên tiếp |
| **Response time p95** | (Chỉ tham khảo, không gây fail.) | — |

### 7.3. Nếu FAIL thì sao?

| Tiêu chí fail | Nghĩa là... | Báo ai? |
|---|---|---|
| **PR-4 fail** (mean chậm) | Server phản hồi quá chậm. | Backend dev. |
| **PR-5 fail** (lỗi nhiều) | Nhiều request bị lỗi. | Backend dev. |
| **CPU fail** | CPU bị nghẹt. | Backend dev + DevOps. |
| **Mem fail** | RAM bị đầy / có thể leak. | Backend dev. |
| **Crash fail** | Server có lúc bị sập. | Backend dev + DevOps (gấp!). |

Trong file `summary.html`, kéo xuống phần **Gatling HTTP Report** → tab **Errors** để xem chi tiết lỗi.

### 7.4. Các file khác trong thư mục báo cáo

Thư mục `target/variants/silkroad/target-<thời gian>/` chứa:

| File | Khi nào mở? |
|---|---|
| `summary.html` | **Luôn mở đầu tiên.** |
| `verdict.json` | Khi muốn lấy số liệu cho dashboard / CI tự động. |
| `gatling-report/index.html` | Khi muốn xem chi tiết từng endpoint. |
| `resource.csv` | Khi muốn vẽ biểu đồ CPU/RAM trong Excel. |
| `health.csv` | Khi muốn xem server có bị sập lúc nào không. |
| `gatling.log` | Khi muốn debug lỗi Gatling. |

---

## 8. Các loại test khác nhau

Có 5 kịch bản test (gọi là **simulation**), dùng cho mục đích khác nhau:

| Kịch bản | Giống cái gì? | Khi nào dùng? | Thời gian | Áp dụng cho game nào |
|---|---|---|---|---|
| **Basic** | Bắn liên tục vào 1 cửa hàng. | Smoke test 1 API duy nhất. | 1-2 phút | Chỉ Silkroad |
| **Soak** | Quán mở cửa 1 ngày dài, đo có "hết hơi" không. | **Test chính** trước khi lên production. Kiểm tra rò rỉ bộ nhớ / chậm dần. | 60 phút | Chỉ Silkroad |
| **Stress** | Đẩy số khách tăng dần đến khi quán đổ. | Tìm ngưỡng chịu tải tối đa. | 10-30 phút | Chỉ Silkroad |
| **Spike** | Khách bình thường rồi đột ngột tăng đột biến. | Test khả năng xử lý đỉnh điểm (ví dụ: ra event). | 30-60 phút | Chỉ Silkroad |
| **Grpc** | Soak nhưng đi qua kênh gRPC (WSProxy plugin) thay vì REST API. | Đo hiệu năng đường gRPC mà FE thực sự dùng ở production. | 60 phút | Silkroad, Bonanza và các game gRPC-only khác (xem `README.md`) |

### Ví dụ: chạy test production (60 phút, 1000 user)

```bash
# Silk Road
./scripts/run-variant.sh \
  --game silkroad \
  --variant target \
  --simulation Soak \
  --users 1000 \
  --duration-minutes 60 \
  --ramp-minutes 5 \
  --container game-silk-road-caravans

# Bonanza (gRPC-only, port 9091 cố định — không có cổng HTTP)
./scripts/run-variant.sh \
  --game bonanza \
  --variant target \
  --simulation Grpc \
  --users 1000 \
  --duration-minutes 60 \
  --ramp-minutes 5 \
  --container game-golden-boat-bonanza
```

> ⚠️ Chú ý: Bonanza chỉ hỗ trợ **Grpc** (không còn REST nữa, nên không có Basic / Soak / Stress / Spike).
>
> 💡 Ví dụ trên test đường gRPC của Bonanza qua wrapper (mặc định `localhost:9091`). Nếu cần đổi gRPC host/port khác `localhost:9091` → phải gọi Gradle trực tiếp (wrapper script chưa có flag riêng cho gRPC endpoint):
> ```bash
> ./gradlew :games:bonanza:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
>   -DgrpcHost=staging.example.com -DgrpcPort=9091
> ```

### Ví dụ: chỉ test 1 endpoint duy nhất

```bash
# Silk Road — test endpoint spin với 500 người chơi, 5000 lượt
./scripts/run-variant.sh \
  --game silkroad \
  --variant target \
  --simulation Basic \
  --scenario spin \
  --users 500 \
  --requests 5000 \
  --duration-minutes 1 \
  --ramp-minutes 0 \
  --container game-silk-road-caravans
```

Bảng các endpoint hợp lệ:

| Game | Endpoint có thể chọn ở `--scenario` |
|---|---|
| **silkroad** | `spin`, `last-spin`, `history-summary` |

> Chỉ Silk Road có `--simulation Basic` (endpoint REST riêng lẻ). Bonanza không còn REST nên không áp dụng mục này.

---

## 9. Khi gặp lỗi — Làm gì?

| Bạn thấy | Có thể do | Làm gì |
|---|---|---|
| `Connection refused` khi gõ `curl` | Game backend chưa khởi động xong. | Đợi 30 giây rồi thử lại. Nếu vẫn lỗi: `docker ps` xem container có chạy không. |
| `--game required` / `--container required` / `--variant required` / `--simulation required` | Quên truyền tham số bắt buộc. | Kiểm tra lại lệnh, phải có đủ `--game`, `--variant`, `--simulation`, `--container`. (`--game` có thể thay bằng env var `GAME=...`) |
| `Port 3000 / 9091 already in use` | Có chương trình khác đang chiếm cổng. | `lsof -ti :3000 \| xargs kill -9` (thay 3000 bằng cổng đang lỗi). |
| Test chạy nhưng ~50% request HTTP 400 | Lỗi cấu hình body JSON (chuyện của dev). | Báo dev kiểm tra cú pháp `#{userId}` vs `${userId}`. |
| `resource.csv` toàn `N/A` | Sai tên container truyền vào `--container`. | `docker ps` để xem tên container thật. |
| `summary.html` không xuất hiện sau khi chạy | Python 3 chưa cài hoặc lỗi script. | Cài Python 3, hoặc xem `verdict.json` để biết kết quả. |
| `BUILD FAILED: simulation class not found` | Lỗi cấu hình (chuyện của dev). | Báo dev xem lại file `build.gradle`. |
| `InaccessibleObjectException` lúc init | Đang dùng Java cũ hơn 17. | `java -version` để kiểm tra, cài Java 17 nếu thiếu. |

### 9.1. Cách kiểm tra game backend còn sống

```bash
docker ps --filter name=game-
```

Phải thấy container `game-silk-road-caravans` hoặc `game-golden-boat-bonanza` đang chạy (cột `STATUS` ghi `Up`).

### 9.2. Xem log backend khi nghi ngờ có lỗi

```bash
docker logs game-silk-road-caravans 2>&1 | tail -50
```

Đọc 50 dòng cuối log để tìm chữ `ERROR` hoặc `Exception`.

### 9.3. Dừng backend khi xong việc

```bash
cd ../be-silk-road-caravans
docker compose down
```

(Hoặc thay đường dẫn cho Bonanza.)

---

## 10. Câu hỏi thường gặp

### ❓ "Test bao lâu thì xong?"

| Loại test | Thời gian |
|---|---|
| Smoke (kiểm tra nhanh) | ~2 phút |
| Test production chính thức | ~70 phút (60 phút chạy + 10 phút setup/báo cáo) |
| Stress test | ~15-30 phút |

### ❓ "Mỗi lần test tốn nhiều RAM/CPU máy mình không?"

- Test chạy trên máy bạn nên có ngốn tài nguyên.
- Với 1000 user ảo: khoảng 2-4 GB RAM, CPU dùng nhiều.
- Tốt nhất **đóng các app nặng khác** (Chrome nhiều tab, Slack...) khi chạy test production.

### ❓ "Tôi có thể chạy test trên máy người khác hoặc trên server riêng không?"

Có. Project có cấu hình **Gatling Enterprise**, nhưng cần dev setup file `.gatling/package.conf` trước.

### ❓ "Kết quả test ở đâu để chia sẻ?"

Mở folder:
```bash
open target/variants/silkroad/
```

Mỗi lần chạy tạo 1 thư mục con với timestamp. Bạn có thể:
- Nén lại (`zip`) gửi cho team qua chat / email.
- Hoặc chỉ gửi `summary.html` + `verdict.json` (đã đủ thông tin).

### ❓ "Test PASS nhưng tôi vẫn lo. Làm sao chắc chắn hơn?"

Chạy thêm:
1. **Variant `stress`** thay vì `target` → kiểm tra cận biên.
2. Tăng `--users` lên cao hơn (1500, 2000) → xem server còn chịu được không.
3. Chạy **Stress simulation** (chỉ Silk Road) để biết điểm đổ vỡ thật sự.

### ❓ "Tôi muốn đổi ngưỡng pass/fail. Có cần đụng vào code không?"

**KHÔNG cần code.** Mở file `config/sla-thresholds.yml` bằng bất kỳ trình soạn thảo nào (TextEdit, VS Code, Notepad). Đổi số, lưu lại, chạy lại test.

Ví dụ: muốn nới response time tối đa từ 500ms lên 700ms:

```yaml
sla:
  pr4_mean_response_ms_max: 700   # đổi từ 500 thành 700
```

### ❓ "Nếu báo cáo có `FAIL` thì có phải đẩy lại deploy không?"

Quy ước nội bộ:
- ✅ **PASS** ở `target` = OK cho production.
- ❌ **FAIL** ở `target` = KHÔNG được lên production. Báo backend dev fix trước.
- Sau khi fix, chạy lại test → phải PASS mới được deploy.

### ❓ "Bonanza và Silkroad khác nhau gì lớn?"

| Khía cạnh | Silkroad | Bonanza |
|---|---|---|
| Cổng HTTP | 3000 (REST) | Không có — gRPC-only |
| Cổng gRPC | 9093 | 9091 |
| Đường dẫn | `/api/game/caravans/...` | Không có (không còn REST) |
| Loại test có sẵn | Basic, Soak, Stress, Spike, **Grpc** | Chỉ **Grpc** |
| Healthcheck khi khởi động | Docker healthcheck (không còn HTTP health) | Docker healthcheck (không có HTTP) |
| Ngưỡng response | Strict (300ms) | Nới hơn (500ms) |

Tất cả khác biệt này script đã tự xử lý — bạn chỉ cần truyền đúng `--game bonanza` và `--simulation Grpc` (Bonanza không có cổng HTTP nên không cần truyền `--port`). Form cũ `GAME=bonanza ./scripts/run-variant.sh ...` cũng vẫn hoạt động.

---

## 📚 Đọc thêm

- 📖 `README.md` — Tài liệu tiếng Anh đầy đủ (chi tiết hơn).
- 📖 `docs/getting-started.md` — Hướng dẫn tiếng Việt chi tiết cho cả dev (dài 42KB).
---

## 🆘 Liên hệ khi bí

Nếu đã thử mục [9 — Khi gặp lỗi](#9-khi-gặp-lỗi--làm-gì) mà vẫn không xử lý được:

1. Chụp màn hình terminal (cả phần lỗi).
2. Gửi kèm file `gatling.log` từ thư mục `target/variants/...` mới nhất.
3. Hỏi dev hoặc DevOps phụ trách project.

---

**Chúc bạn test vui vẻ! 🚀**
