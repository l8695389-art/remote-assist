# Remote Assist — Ứng dụng điều khiển màn hình từ xa (chỉ dùng khi có sự đồng ý)

Ứng dụng Android hai chế độ (Host / Controller) truyền màn hình qua WebRTC và cho phép
điều khiển từ xa qua Accessibility Service, có signaling server chạy trên Cloudflare
Workers + Durable Objects.

**Chỉ dùng cho thiết bị bạn sở hữu, hoặc đã được chủ thiết bị đồng ý rõ ràng.** Xem mục
[Ràng buộc bảo mật đã cài trong code](#ràng-buộc-bảo-mật-đã-cài-trong-code) — đây không
phải là checklist "nên làm", mà là những gì code thực sự thực thi.

---

## 1. Kiến trúc tổng quan

```
Android B (Controller)  <—— WSS signaling ——>  Cloudflare Worker  <—— WSS signaling ——>  Android A (Host)
        |                                        (Durable Object                              |
        |                                         "Room" mỗi mã                                |
        |                                         ghép đôi)                                     |
        └──────────────────── WebRTC P2P: video track + DataChannel ─────────────────────────────┘
                              (SDP offer/answer & ICE trao đổi qua signaling ở trên,
                               sau đó media/data đi thẳng giữa hai máy, có TURN dự phòng)
```

- **Signaling**: WebSocket (WSS) tới Cloudflare Worker. Worker định tuyến theo mã ghép
  đôi tới đúng Durable Object `Room`, đối tượng này chỉ relay offer/answer/ICE giữa
  đúng 2 socket của phòng đó.
- **Media**: WebRTC chuẩn — `ScreenCapturerAndroid` (MediaProjection) → `VideoTrack` →
  `PeerConnection` bên Host; Controller nhận qua `SurfaceViewRenderer`.
- **Điều khiển**: `DataChannel` (order, không dùng UDP thô) mang JSON gesture
  (`tap` / `long_press` / `swipe`, tọa độ chuẩn hóa 0..1). Host nhận, kiểm tra phiên hợp
  lệ, rồi gọi `AccessibilityService.dispatchGesture()`.

## 2. Cấu trúc thư mục

```
remote-assist/
├── android/                          # Ứng dụng Android (Kotlin, Gradle Kotlin DSL)
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/remoteassist/
│           │   ├── AppConfig.kt                      # sửa URL Worker + TURN ở đây
│           │   ├── MainActivity.kt                   # chọn Host / Controller
│           │   ├── HostActivity.kt                   # màn hình bị điều khiển
│           │   ├── ControllerActivity.kt             # màn hình điều khiển
│           │   ├── service/
│           │   │   ├── ScreenCaptureService.kt        # foreground service (mediaProjection)
│           │   │   └── RemoteControlAccessibilityService.kt
│           │   ├── webrtc/WebRtcClient.kt
│           │   ├── signaling/
│           │   │   ├── SignalingClient.kt
│           │   │   └── SignalingModels.kt
│           │   ├── session/SessionManager.kt
│           │   └── util/NotificationHelper.kt
│           └── res/                                   # layout, strings, icon, xml a11y config
└── server/                            # Cloudflare Worker (TypeScript)
    ├── wrangler.toml
    ├── package.json
    ├── tsconfig.json
    └── src/
        ├── index.ts                   # route /signal, sinh mã ghép đôi
        └── room.ts                    # Durable Object: 1 phòng = 1 mã ghép đôi
```

## 3. Build APK

Yêu cầu: Android Studio (Koala trở lên) hoặc JDK 17 + Android SDK 34 dòng lệnh.

> Project này chưa kèm sẵn Gradle Wrapper (file nhị phân `gradle-wrapper.jar`) vì đây là
> file binary. Cách nhanh nhất: mở thư mục `remote-assist/android` bằng Android Studio —
> nó tự tạo wrapper và sync. Nếu build dòng lệnh, chạy `gradle wrapper --gradle-version 8.7`
> một lần trong thư mục `android/` (cần có Gradle cài sẵn), sau đó dùng `./gradlew` như
> bình thường cho các lần sau.

```bash
cd remote-assist/android
./gradlew assembleDebug
# APK ở: app/build/outputs/apk/debug/app-debug.apk
```

Mở bằng Android Studio: `File > Open` chọn thư mục `remote-assist/android`, để Gradle
sync xong rồi bấm Run trên hai máy thật (khuyến nghị dùng máy thật vì MediaProjection +
camera ảo trên emulator hay không ổn định).

**Trước khi build**, mở `app/src/main/java/com/example/remoteassist/AppConfig.kt` và:
1. Sửa `SIGNALING_SERVER_URL` thành URL Worker bạn deploy ở bước 4.
2. Thêm thông tin TURN server của bạn vào `iceServers()` (STUN công khai của Google đã có
   sẵn nhưng không đủ cho phần lớn kết nối di động thật — xem mục 4.3).

> Gói `io.github.webrtc-sdk:android` là bản mirror được duy trì tích cực của WebRTC
> Android AAR chính thức của Google (kể từ khi Google ngừng publish `org.webrtc` lên Maven
> Central/JCenter). Nếu tổ chức bạn có mirror nội bộ khác, đổi dependency trong
> `app/build.gradle.kts`, API sử dụng (`PeerConnectionFactory`, `ScreenCapturerAndroid`,
> `SurfaceViewRenderer`...) tương thích với mọi bản build chính thức gần đây.

## 4. Deploy Cloudflare Worker (signaling server)

### 4.1. Cài đặt

```bash
cd remote-assist/server
npm install
npx wrangler login
```

### 4.2. Deploy

```bash
npx wrangler deploy
```

Wrangler in ra URL dạng `https://remote-assist-signal.<subdomain>.workers.dev`. Đổi
`https://` → `wss://` và thêm `/signal`, dán vào `AppConfig.SIGNALING_SERVER_URL`, ví dụ:

```
wss://remote-assist-signal.<subdomain>.workers.dev/signal
```

Build lại app sau khi sửa hằng số này.

`wrangler.toml` đã khai báo Durable Object `Room` (SQLite-backed, migration `v1`) — không
cần tạo thêm tài nguyên gì khác trên Cloudflare. Không cần KV, không cần D1.

### 4.3. TURN server (khuyến nghị mạnh cho kết nối di động thật)

STUN công khai chỉ giải quyết được NAT loại "cone" đơn giản. Hai điện thoại ở hai mạng
di động khác nhau (carrier-grade NAT) thường **cần TURN** để kết nối được. Vài lựa chọn:

- Cloudflare Calls TURN (cùng hệ sinh thái với Worker này)
- Tự host `coturn` trên một VPS nhỏ
- Twilio Network Traversal Service

Thêm vào `AppConfig.iceServers()`:

```kotlin
PeerConnection.IceServer.builder("turn:turn.example.com:3478")
    .setUsername("turn_user")
    .setPassword("turn_password")
    .createIceServer()
```

### 4.4. Giới hạn đã biết của signaling server

- Mã ghép đôi 6 chữ số, mỗi Worker instance chọn mã ngẫu nhiên rồi giao thẳng cho
  Durable Object tương ứng (`idFromName(code)`). Nếu trùng mã với một phòng đang hoạt
  động (xác suất rất thấp với TTL 5 phút + không gian 10^6 mã), Durable Object đó trả
  `{type:"error", reason:"room_already_active"}` — client Android sẽ cần kết nối lại để
  Worker sinh mã khác. Với quy mô sử dụng cá nhân/nhóm nhỏ, mức này đủ an toàn.
- Muốn scale lớn hơn (nhiều tổ chức dùng chung Worker): thêm một Durable Object
  "Registry" duy nhất để cấp phát mã nguyên tử trước khi mở WebSocket, tránh hoàn toàn
  khả năng trùng.

## 5. Chạy thử với 2 điện thoại

1. Deploy Worker (mục 4), build APK (mục 3), cài lên **cả hai máy**.
2. **Máy A (bị điều khiển)**: mở app → **"Chia sẻ màn hình của tôi"**.
   - Nếu Accessibility service chưa bật, app hiện hộp thoại nhắc mở
     **Cài đặt > Trợ năng > Remote Assist** và bật thủ công (bắt buộc thao tác tay,
     app không tự bật được — đây là hành vi chuẩn của Android).
   - App tự kết nối signaling và hiển thị **mã ghép đôi 6 số**, đếm ngược 5 phút.
3. **Máy B (điều khiển)**: mở app → **"Điều khiển thiết bị khác"** → nhập mã 6 số từ
   máy A → **Kết nối**.
4. Máy A hiện trạng thái "Đã kết nối" và nút **"Bắt đầu chia sẻ"** được bật.
5. Trên máy A, bấm **"Bắt đầu chia sẻ"** → hệ thống hiện đúng hộp thoại xin quyền
   MediaProjection của Android ("Bắt đầu ghi hoặc truyền?") → chọn **Bắt đầu ngay bây
   giờ**. Ngay lúc này:
   - Thông báo "Màn hình đang được chia sẻ" xuất hiện trên máy A, không thể ẩn, có nút
     **Dừng chia sẻ**.
   - Máy B bắt đầu hiển thị video màn hình máy A.
6. Trên máy B, chạm/vuốt lên vùng video → thao tác được gửi qua DataChannel → máy A
   thực hiện lại đúng thao tác đó bằng Accessibility Service.
7. Dừng phiên bất cứ lúc nào:
   - Máy A: bấm **"Dừng chia sẻ"** (dừng video, giữ kết nối signaling) hoặc
     **"Ngắt kết nối"** (đóng toàn bộ phiên) hoặc bấm nút **Dừng** ngay trên thông báo hệ
     thống.
   - Máy B: bấm **"Ngắt kết nối"**.
   - Bất kỳ bên nào ngắt, bên kia lập tức nhận thông báo và phiên kết thúc — không có
     trạng thái "điều khiển ngầm" còn sót lại.

## 6. Quyền Android mà app yêu cầu — và vì sao

| Quyền / cơ chế | Khi nào được hỏi | Do người dùng cấp thủ công? |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Cấp lúc cài đặt (permission thường) | Không cần thao tác, không nhạy cảm |
| `POST_NOTIFICATIONS` (Android 13+) | Lần đầu app cần hiện thông báo | Có — hộp thoại hệ thống chuẩn |
| MediaProjection (`createScreenCaptureIntent`) | Mỗi lần bấm **"Bắt đầu chia sẻ"** | **Có, bắt buộc mỗi lần** — đây là hộp thoại hệ thống Android, app không thể bỏ qua, không thể tự động chấp nhận, không cache để dùng lại lần sau |
| `BIND_ACCESSIBILITY_SERVICE` (Accessibility) | Người dùng tự vào Cài đặt > Trợ năng | **Có, bắt buộc thủ công** — Android không cho bất kỳ app nào tự bật dịch vụ trợ năng cho chính nó; app chỉ mở đúng màn hình Cài đặt và chờ người dùng bật |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Ngay khi bắt đầu capture | Cấp tự động lúc cài (permission thường), nhưng **chỉ có hiệu lực sau khi** MediaProjection đã được cấp — hệ thống sẽ chặn nếu gọi sai thứ tự |

**Không có trong app**: không xin `SYSTEM_ALERT_WINDOW`, không xin quyền Device Admin,
không dùng bất kỳ API riêng tư/ẩn nào, không có đường tắt bật Accessibility bằng
`adb`/root, không có cơ chế vượt qua màn hình khóa.

## 7. Ràng buộc bảo mật đã cài trong code

Đối chiếu trực tiếp với từng file, không phải mô tả chung chung:

1. **Không bypass khóa màn hình** — app không đụng tới `KeyguardManager`, không có
   accessibility action nào target màn hình khóa; `dispatchGesture` chỉ chạy khi
   `SessionManager.isSessionActive()` true, tức là sau khi cả hai bước cấp quyền hợp lệ
   đã xảy ra.
2. **Không tự cấp Accessibility** — `HostActivity.isAccessibilityServiceEnabled()` chỉ
   *kiểm tra* trạng thái và mở `Settings.ACTION_ACCESSIBILITY_SETTINGS`; không có API nào
   trong Android cho phép app tự bật mục này, và code không cố lách qua đó.
3. **Không tự cấp MediaProjection** — capture chỉ bắt đầu trong
   `projectionLauncher` callback với `result.resultCode == RESULT_OK`, tức đúng luồng
   `ActivityResultContracts.StartActivityForResult` gọi hộp thoại hệ thống thật.
4. **Không ẩn app / không điều khiển ngầm** — icon launcher hiển thị bình thường,
   thông báo `NotificationHelper` là `setOngoing(true)` (không thể vuốt tắt) và luôn hiện
   trong suốt phiên; không có `Service` nào chạy mà thiếu notification tương ứng khi đang
   capture.
5. **Không ghi log dữ liệu nhạy cảm** — `RemoteControlAccessibilityService` khai báo
   `canRetrieveWindowContent="false"` (không đọc nội dung màn hình, chỉ nhận tọa độ chạm
   từ phía Controller); không ghi tọa độ, không ghi khung hình ra log hay file.
6. **Yêu cầu đồng ý trước khi điều khiển** — mọi gesture đi qua
   `SessionManager.isValidForCurrentSession()`; nếu chưa có phiên hợp lệ (nghĩa là Host
   chưa bấm "Bắt đầu chia sẻ" và tự cấp MediaProjection), gesture bị drop âm thầm.
7. **Có dấu hiệu rõ ràng khi đang chia sẻ** — thông báo ongoing bắt buộc,
   `NotificationHelper.buildSharingNotification()` được gọi ngay trước khi bắt đầu capture
   (`ScreenCaptureService.ACTION_START`).
8. **Dừng bất cứ lúc nào** — nút Dừng trên notification, nút "Dừng chia sẻ" và
   "Ngắt kết nối" trong `HostActivity`, nút "Ngắt kết nối" trong `ControllerActivity`; mọi
   nút đều gọi thẳng `stopSharingAndSelf()` / gửi `peer_left`, không có debounce hay chặn.
9. **Mã ghép đôi có hạn và hủy được** — `Room.alarm()` trong `room.ts` tự đóng phòng sau
   `PAIRING_TTL_MS` (5 phút) nếu chưa ai ghép đôi; `cancel_room` cho phép Host hủy sớm.
10. **Không lưu video nếu người dùng không yêu cầu** — pipeline chỉ là
    `ScreenCapturerAndroid → VideoSource → PeerConnection`, không ghi ra `MediaRecorder`,
    không có đường dẫn file đầu ra nào trong code.
11. **Mọi thao tác remote đi qua phiên đã xác thực** — `SessionManager` gắn `sessionId`
    (UUID) cho mỗi phiên; `Room` (server) và `RemoteControlAccessibilityService` (client)
    đều so khớp `sessionId` trước khi relay/thực thi.
12. **Không né cơ chế bảo mật Android** — toàn bộ luồng cấp quyền đi qua đúng API công
    khai (`MediaProjectionManager`, `AccessibilityService`, `Settings.ACTION_*`); không có
    reflection vào API ẩn, không root, không exploit.

## 8. Trường hợp màn hình máy A bị hỏng / không thao tác được

Ứng dụng **không** có "cửa sau" để giành quyền điều khiển khi màn hình hỏng. Việc hỗ trợ
từ xa trong tình huống này chỉ khả thi nếu:

- Accessibility service **đã được bật từ trước** (khi màn hình còn hoạt động), **và**
- Một phiên chia sẻ **đã đang chạy** (MediaProjection đã được cấp trước khi màn hình hỏng)
  — vì mỗi lần "Bắt đầu chia sẻ" mới đều bắt buộc lại hộp thoại hệ thống, và hộp thoại đó
  cần người dùng tương tác được trên máy A.

Nếu không thỏa cả hai điều kiện trên, đây là giới hạn hợp pháp của nền tảng Android, không
phải giới hạn cần "khắc phục" bằng kỹ thuật lách — ứng dụng cố tình không cung cấp đường
vòng nào cho tình huống này.

## 9. Giới hạn tương thích theo phiên bản Android

- `minSdk = 26` (Android 8.0). `ScreenCapturerAndroid` + `AccessibilityService.dispatchGesture`
  hoạt động ổn định từ mức này trở lên.
- Từ **Android 10 (API 29)**: bắt buộc khai báo `foregroundServiceType="mediaProjection"`.
- Từ **Android 13 (API 33)**: cần xin `POST_NOTIFICATIONS` runtime để hiện được thông báo
  "đang chia sẻ" — app đã khai báo quyền này trong manifest; nếu người dùng từ chối,
  notification sẽ không hiện nhưng phiên vẫn hoạt động (khuyến nghị luôn cấp để tuân thủ
  yêu cầu "phải có dấu hiệu rõ ràng").
- Từ **Android 14 (API 34)**: hệ thống chỉ cho phép promote service lên foreground với
  type `mediaProjection` **sau khi** đã có token MediaProjection hợp lệ — đây là lý do
  `ScreenCaptureService` tách riêng `ACTION_CREATE_ROOM` (chưa foreground) và `ACTION_START`
  (foreground ngay sau khi có `resultCode/data` từ hộp thoại hệ thống). Nếu bạn hạ
  `targetSdk` xuống dưới 34, có thể gộp lại đơn giản hơn nhưng nên giữ nguyên thứ tự này để
  an toàn về lâu dài.

## 10. Giao thức DataChannel (gesture)

```json
{"t":"tap","sessionId":"<uuid>","x":0.42,"y":0.87,"durationMs":100}
{"t":"long_press","sessionId":"<uuid>","x":0.5,"y":0.5,"durationMs":600}
{"t":"swipe","sessionId":"<uuid>","x":0.2,"y":0.8,"x2":0.2,"y2":0.2,"durationMs":300}
```

`x`, `y` (và `x2`, `y2` khi có) là tọa độ chuẩn hóa 0..1 theo chiều rộng/cao của
`SurfaceViewRenderer` bên Controller; Host quy đổi lại theo kích thước màn hình thật của
chính nó (`SessionManager.screenSize()`), nên hai máy khác độ phân giải vẫn ánh xạ đúng vị
trí tương đối.

## 11. Giao thức signaling (WebSocket JSON)

| type | Chiều | Ý nghĩa |
|---|---|---|
| `create_room` | Host → Server | Xin tạo phòng mới |
| `room_created` | Server → Host | Trả mã ghép đôi + sessionId + thời hạn |
| `join` | Controller → Server | Ghép đôi bằng mã |
| `joined` | Server → Controller | Xác nhận ghép đôi thành công |
| `peer_joined` | Server → Host | Báo Controller đã vào phòng |
| `offer` / `answer` | Hai chiều (relay) | SDP |
| `ice_candidate` | Hai chiều (relay) | ICE candidate |
| `cancel_room` | Host → Server | Hủy phòng chủ động |
| `peer_left` | Hai chiều | Một bên rời phiên |
| `error` | Server → bên gửi lỗi | `reason`: `invalid_code` / `room_full` / `room_not_found` / `code_expired` / `session_mismatch` / `rate_limited_or_replay` / `room_already_active` |

Mỗi message có trường `seq` tăng dần theo từng socket — server từ chối message có `seq`
nhỏ hơn hoặc bằng message trước đó (chống replay/out-of-order), và giới hạn tối đa 40
message/giây/socket (chống spam).
