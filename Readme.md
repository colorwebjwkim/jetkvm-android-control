# JetKVM Android Control

Kotlin 기반 Android 네이티브 JetKVM 관리 앱입니다. NexaCore 공통 브랜드를 사용합니다. 현재 버전은 **0.7.0 개발 버전**이며, WebView 없이 장비와 직접 통신합니다. 도메인 `nexacore.co.kr`에 맞춘 Android 앱 ID·namespace는 **`kr.co.nexacore.jetkvm`**입니다.

## 구현 기능

- 여러 JetKVM의 이름, HTTP/HTTPS 주소, 포트를 등록·수정·삭제하고 앱 재시작 후 유지합니다. IPv4, 대괄호 IPv6, 도메인·MagicDNS 주소를 지원합니다.
- 네이티브 비밀번호 입력 → 장비 세션 인증 → WebSocket 시그널링 → WebRTC 연결. 장비별 비밀번호 암호화 저장을 선택할 수 있습니다.
- 앱이 전면에 표시되는 동안 장비 목록·대화상자·원격 화면에서 화면 켜짐을 유지합니다. 홈 이동·다른 앱 전환 시에는 시스템 화면 꺼짐 정책을 따릅니다.
- Android WebRTC 렌더러로 협상된 코덱(H.264/H.265 등)의 원격 영상 수신. 화면 비율을 유지하고 검은 여백은 터치 좌표에서 제외합니다.
- `hidrpc` v1 핸드셰이크 후 터치로 절대좌표 마우스·드래그, 우클릭, Esc/Tab/Enter/Backspace/Ctrl+Alt+Del, US ASCII 텍스트 전송(최대 256자).
- 에이전트가 없어도 영상·마우스·키보드 원격 접속을 사용합니다. 목록 표시를 위한 자동 WebRTC/전원 전용 연결은 만들지 않습니다.
- 선택적으로 `jetkvm-status-agent` HTTPS 주소와 토큰을 장비별로 설정하고 온라인 보고·수집 연결·ATX LED·DC 출력·접속 수를 조회합니다. 현재 에이전트는 조회 전용이므로 전원 명령은 제공하지 않습니다.
- Tailscale API 토큰으로 tailnet 장비 주소 목록을 조회하고 사용자가 IP 또는 MagicDNS 주소를 선택해 등록합니다. 조회된 모든 장비가 JetKVM은 아닙니다.

## jetkvm-status-agent 활용 (0.7.0, 2026-10-18)

관련 프로젝트: [jetkvm-status-agent](https://github.com/colorwebjwkim/jetkvm-status-agent). JetKVM 내부에서 수집한 상태를 인증된 API로 제공하고, 이 앱은 영상 세션을 열지 않고 상태를 표시하는 역할을 맡습니다. 에이전트는 선택 사항입니다.

| 장비 구성 | 앱 동작 |
|---|---|
| 에이전트 없음 / 앱 연동 미설정 | 장비 등록·수정·삭제와 사용자가 누르는 원격 영상·입력 접속. 목록에서 자동 로그인·WebRTC 접속 없음 |
| 에이전트 설치 + 주소·토큰 설정 | 기존 원격 접속과 함께 상태 API 조회·표시 |
| 에이전트 연결 실패 / 인증 실패 / 미지원 응답 | 상태 미확인 또는 인증 실패 안내. 원격 접속은 계속 사용 가능 |
| 현재 조회 전용 API | 목록의 켜기·끄기·재부팅 버튼 숨김, 원격 화면 전원 메뉴 비활성. 상태 조회 성공으로 전원 명령을 허용하지 않음 |

### 앱에서 설정하기

1. JetKVM에 에이전트를 별도로 설치·실행하고, 대상 JetKVM의 상태를 수집하도록 구성합니다. 설치 및 MQTT/TLS 서버 설정은 에이전트 프로젝트의 안내를 따릅니다. 앱이 에이전트를 자동 설치하거나 JetKVM의 MQTT 목적지를 변경하지는 않습니다.
2. 장비 카드의 `⋮ → 상태 에이전트 설정`에서 **에이전트 자체 HTTPS 주소와 포트**, **Bearer 토큰**을 저장합니다. 예: `https://agent.example.ts.net:8443`. JetKVM 영상 접속 주소와 별도 설정이며 고정 포트를 추측하거나 자동 탐색하지 않습니다.
3. 토큰은 Android Keystore AES-GCM으로 암호화 저장합니다. 입력창을 비우면 같은 에이전트 주소의 기존 토큰을 유지합니다. 에이전트 주소를 바꾸면 토큰을 다시 입력해야 합니다. `연동 해제`는 앱의 주소와 저장 토큰을 삭제하며 에이전트 서비스는 계속 실행됩니다. 장비 삭제·JetKVM 접속 주소 변경 시에도 연동 정보를 삭제합니다.
4. 목록이 전면에 있으면 즉시 조회하고 이후 5초 간격으로 갱신합니다. `⋮ → 에이전트 상태 새로고침`으로 수동 조회할 수 있습니다. 각 요청은 최대 6초, 최대 64 KiB이며 동시 작업은 최대 4개입니다. 한 장비의 오류는 다른 장비의 결과를 지우지 않습니다.
5. 상태 문구를 누르면 전체 정보를 확인합니다. 영상 연결 중에는 목록 조회를 중단하고 영상 종료 후 다시 조회합니다. 앱이 백그라운드로 이동하면 요청을 취소하고 이전 상태를 폐기합니다. 돌아오면 새 응답으로 확인합니다. 조회 취소는 현재 요청을 취소하며 주기 조회를 영구 해제하려면 연동 해제를 사용합니다.

에이전트에는 Android가 신뢰하는 인증서가 필요합니다. 자체 서명 인증서 무조건 신뢰나 HTTP 토큰 전송은 지원하지 않습니다. 리다이렉트·JetKVM 로그인 쿠키 공유 없이 `GET /api/v1/status`에만 Bearer 토큰을 보냅니다. 토큰은 일반 로그·장비 목록 JSON에 기록하지 않습니다.

### 현재 지원하는 상태 응답

연동 기준은 로컬 `jetkvm-status-agent/internal/agent/http.go`, `state.go`의 **구현된 schemaVersion 1**입니다. 에이전트 README의 초기 제안 예시(`power.on`, `sessions.active`)와 달리 현재 구현은 아래처럼 `Reading` 객체를 사용합니다. API가 바뀌면 양쪽 계약을 함께 갱신해야 합니다.

- `agent.version`, `agent.collectorConnected`, `agent.lastError` 유무: 버전·수집 연결·수집 오류 안내.
- `device.online`: `value`가 온라인 여부인 Reading. API 응답 성공과 장비 온라인 보고는 별개입니다.
- `device.extension`: 에이전트가 보고하는 확장 종류. 현재 구현의 `unknown`을 ATX/DC로 추정하지 않습니다.
- `power.atx`, `power.dc`: LED·출력 Reading. 물리 확장 설치, PC OS 실행 여부를 증명하지 않습니다.
- `sessions`: `value`가 접속 수인 Reading. 미수신 `null`을 0으로 바꾸지 않습니다.
- 각 Reading의 `freshness`, `retained`, `receivedAt`: 최신·오래됨·미확인, 보관 메시지, 에이전트 수신 시각을 표시합니다. 수신 시각을 원래 측정 시각으로 취급하지 않습니다. `value`의 타입이 잘못되면 미확인으로 표시하고 미지원 schemaVersion·잘못된 JSON은 조회 실패로 처리합니다.

현재 에이전트의 `extensionVerified=false`, `usableForControl=false` 정책을 유지하며 **조회 전용**으로 사용합니다. 앱에서 설정할 수 있는 범위는 에이전트 접속 주소·인증 토큰·연동 해제입니다. 에이전트의 수집 설정을 수정하는 API나 전원 명령 API는 현재 제공되지 않습니다. 기존 Windows `jetkvm-monitor-switcher` 연동은 별도 장비 설정으로 유지되며, MQTT 목적지 변경 시 기존 수신·중계 정책은 에이전트 측에서 따로 검증해야 합니다.

아래 0.6.1 이하의 자동 전원 연결·전원 버튼 기록은 이전 버전 이력입니다. 현재 동작은 이 절의 정책을 우선합니다.

## 0.7.0 검증 결과 (2026-10-18)

- versionName `0.7.0`, versionCode `24`. Microsoft JDK 17.0.20으로 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 성공. 단위 테스트 **30개 통과**, Lint 오류 0개·경고 56개(기존 항목과 전원 UI 제거 후 미사용 문자열·리소스 경고 포함). 한국어·영어·일본어 177개 문구 및 포맷 검사 통과.
- 신규 JVM 검사 7개: 실제 schemaVersion 1 Reading 해석, 미확인/보관/오래됨 구분, 잘못된 JSON·버전·타입, HTTPS 주소 검증, 로컬 TLS 서버 Bearer 인증·조회 경로·리다이렉트 차단, 401·64 KiB 초과 응답·종료된 클라이언트, 진행 중인 요청 취소, 미신뢰 인증서 거부. TLS 모의 서버 인증서는 테스트에서만 명시적으로 신뢰하며 앱의 시스템 인증서 검증 정책을 완화하지 않습니다.
- 삼성 테스트 태블릿에 앱·테스트 APK 설치 후 전체 instrumentation PASS (`INSTRUMENTATION_CODE: -1`). 에이전트 미설정 목록의 JetKVM HTTP/WebRTC 요청 0회, 연결 버튼 사용 가능·전원 버튼 숨김, 실제 설정 대화상자의 HTTP 거부·HTTPS 주소/토큰 저장, 암호화 토큰 재조회, 에이전트 접속 실패 시 상태 미확인, pause/stop 시 요청·스냅샷 제거와 재시작을 확인했습니다. 기존 다국어·입력창·암호화·모니터 신호·툴바·네이티브 WebRTC 검사도 통과했습니다.
- 기존 `PowerControlSmoke`는 앱 화면에 연결되지 않은 클라이언트의 로컬 모의 RPC 회귀 검사입니다. 해당 검사 통과는 0.7.0의 전원 조작 제공을 의미하지 않습니다. 실제 전원 명령은 실행하지 않았습니다.
- 실제 JetKVM의 에이전트 설치·TLS 인증서·MQTT 수신·앱의 실제 상태 표시, 실장비 영상·입력의 이번 버전 회귀 시험은 미검증입니다. 에이전트 저장소의 구현 계약은 개발 중이며 README 초안과 차이가 있으므로 위 지원 응답 절을 기준으로 맞춥니다.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. 증거: `verification/0.7.0-build.log`, `verification/0.7.0-instrumentation.txt`, `verification/0.7.0-summary.json`. 설치 후 앱을 재실행했으며 저장된 사용자 장비·비밀번호는 유지했습니다.

## 이전 버전 전원 제어 계약

`getActiveExtension`은 **설정된 확장 종류**를 반환합니다. 확인한 공식 소스의 `getATXState`는 전원/HDD LED 캐시를 반환하므로, 조회 성공만으로 물리적 연결이나 보드 응답을 증명할 수 없습니다. 상태 줄에는 “ATX · 전원 LED” 또는 “DC · 출력”으로 표시합니다. 전원 LED 배선이 없는 장비에서는 표시가 실제 PC 전원과 다를 수 있습니다.

- ATX: `setATXPowerAction`의 `power-short`(200ms), `power-long`(5초), `reset` 사용. 짧은 버튼 누름은 PC 전원 버튼 동작이며 OS의 전원 설정에 따라 종료·절전 등이 달라집니다.
- DC: `setDCPowerState(enabled)` 사용. PC의 정상 종료를 요청하는 기능이 아니라 전원 출력을 제어합니다.
- 확장 미설정·미지원, 상태 불명, 조회 실패, 연결 종료, 12초 넘은 상태에서는 전원 제어를 비활성화합니다. 실행 직전에 확장 종류와 상태를 다시 조회합니다.
- RPC는 응답 ID로 연결하며 8초 이후 타임아웃으로 처리합니다(검사 주기 5초로 실제 표시는 최대 약 13초). 전원 명령은 **자동 재시도하지 않습니다**. 응답 유실 시 실행 여부를 알 수 없음을 표시하고 상태를 다시 조회합니다.
- 물리적 보드 응답 여부를 별도로 제공하는 펌웨어의 기능은 아직 통합하지 않았습니다. 실제 전원 명령의 성공은 장비 응답과 실물 상태로 검증해야 합니다.

## 사용

1. 장비 추가에서 이름과 주소(예: `192.168.1.20`, `https://jetkvm.example.ts.net`), 선택 포트를 저장합니다. IPv6는 `[fd7a:115c:a1e0::1]`처럼 대괄호를 사용합니다.
2. 연결을 누르고 **장비 로컬 비밀번호**를 입력합니다. **이 장비 비밀번호 암호화 저장**을 체크하고 연결하면 다음 연결 시 자동으로 채웁니다. 체크를 해제하고 연결하면 저장값을 삭제합니다. 비밀번호가 없는 모드는 비워둡니다. 초기 설정이 완료된 장비를 대상으로 하며, 최초 설치·비밀번호 설정은 이 앱에서 제공하지 않습니다.
3. 원격 영상을 터치해 마우스를 조작합니다. 상단 우측 기타 기능(···)에서 Esc/Tab/Enter/Backspace/Ctrl+Alt+Del, 우클릭, 텍스트 전송을 선택합니다. 텍스트 전송은 원격 OS의 US 키보드 배열을 기준으로 하며 한국어 IME 입력·클립보드 붙여넣기는 아직 지원하지 않습니다.
4. 선택적으로 장비 카드 `⋮ → 상태 에이전트 설정`에서 에이전트 주소·토큰을 저장합니다. 에이전트가 없으면 원격 접속을 그대로 사용합니다. 현재 전원 조작은 제공하지 않습니다.
5. Tailscale 가져오기에서 tailnet(기본 `-`)과 API 토큰을 입력하고 주소를 선택합니다. 장비 이름과 포트를 확인한 뒤 저장합니다. **VPN은 별도 Tailscale Android 앱에서 연결해야 합니다.** 주소 목록 조회 성공은 장비 연결 성공을 의미하지 않습니다.
6. 장비 목록으로 돌아가거나 앱이 백그라운드로 이동하면 세션·입력을 종료합니다. 돌아온 후에는 다시 연결 버튼을 누릅니다. 연결 실패 시에도 수동 재연결합니다.

## 통신과 자격 증명

- `GET /device`로 비밀번호 없는 모드 또는 인증 필요 여부를 확인합니다. 인증이 필요한 경우 `POST /auth/login-local`에 JSON 비밀번호를 전달하고 다시 `/device`로 세션을 검증합니다.
- 장비별 세션 전용 메모리 쿠키 저장소를 사용합니다. 쿠키·Tailscale 토큰은 앱 저장소에 저장하지 않습니다. 비밀번호는 사용자가 저장을 선택한 경우에만 Android Keystore의 내보낼 수 없는 AES-256 키와 AES/GCM/NoPadding으로 암호화한 후 저장합니다. 매 저장마다 새로운 IV를 생성하고 장비 ID와 URL을 인증 데이터(AAD)로 묶습니다. 평문 비밀번호와 키는 SharedPreferences에 저장하지 않으며 입력 필드의 View 상태 저장도 비활성화했습니다.
- 저장 비밀번호는 해당 앱 설치/기기에 종속되며 백업·복원용이 아닙니다. 저장 해제 후 연결·장비 삭제·접속 주소 변경 시 암호문을 삭제합니다. 복호화 실패 시 입력을 요청하며, 저장/삭제 실패 시 오류를 표시하고 연결을 진행하지 않습니다. 암호화 및 복호화는 작업 스레드에서 수행합니다.
- HTTP 리다이렉트를 따르지 않고, 쿠키는 같은 scheme/host/port에만 전송합니다. 시스템 TLS 검증을 사용하며 자체 서명 인증서를 무조건 신뢰하는 코드는 없습니다.
- HTTP는 암호화되지 않으므로 신뢰하는 LAN 또는 Tailscale 경로에서 사용합니다. HTTPS 장비는 Android에서 신뢰하는 인증서가 필요합니다.
- WebSocket `/webrtc/signaling/client`의 `device-metadata` 수신 후 offer를 생성합니다. SDP는 base64 JSON, ICE는 `new-ice-candidate`로 교환하며 offer/answer 전후의 ICE 후보를 버퍼링합니다.
- WebRTC `rpc` 채널은 JSON-RPC, `hidrpc` 채널은 바이너리 v1 프로토콜입니다. 영상은 수신 전용이며 카메라·마이크 권한을 요청하지 않습니다.
- Tailscale 앱의 비공개 데이터는 읽지 않습니다. Tailscale API 인증 정보는 HTTPS 목록 조회에만 사용합니다.
- 장비 주소 목록은 SharedPreferences JSON에 저장하며 Android 백업은 비활성화했습니다.

## 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 장비 관리·로그인·Tailscale 가져오기 |
| `ControlToolbar.kt` | 상태에 따라 활성화되는 공통 상단 아이콘 메뉴 |
| `DeviceActionRow.kt` | 장비 카드의 연결·수정·삭제 아이콘 줄 |
| `PowerPolicy.kt` | 최신 ATX/DC 상태에 따른 전원 작업 허용·명령 매핑 |
| `NativeSessionView.kt` | 영상·터치·키보드·전원 네이티브 화면 |
| `VideoViewport.kt` | 폰·태블릿의 남은 영역에 영상 비율을 유지해 배치 |
| `NativeJetKvmClient.kt` | WebRTC·시그널링·세션 수명·입력. 현재 앱 화면에서는 전원 RPC 비활성 |
| `StatusAgentClient.kt` | HTTPS 상태 API·Bearer 인증·schemaVersion 1 해석·조회 전용 표시 |
| `PasswordStore.kt` | Android Keystore AES-256-GCM 장비별 비밀번호 저장 |
| `JetKvmHttp.kt` | 인증·세션별 메모리 쿠키·HTTP 클라이언트 |
| `HidProtocol.kt` | HID 바이너리 형식·ASCII 매핑·전원 상태 유효성 |
| `RpcTracker.kt` | RPC 응답 연결·타임아웃·종료 시 대기 요청 폐기 |
| `DeviceAddress.kt` | 주소·포트 검증 |

로고는 `app/src/main/res/drawable/nexacore_logo.png`, 공통 색상 기준은 `branding/profile.json`에 프로젝트 내부 복사했습니다.

2026-10-18 사용자가 새로 제공한 `Logo.png`로 로고를 교체했습니다. 상단 바와 앱 아이콘은 같은 프로젝트 리소스를 사용합니다. 제공된 PNG는 색상·비율·투명도를 변경하지 않고 그대로 복사하며, 실행 시 바탕화면의 원본 경로에 의존하지 않습니다. 버전 `0.3.2`(versionCode `10`)에 포함됩니다.

## 빌드 및 설치

개발·테스트 기준 환경은 IntelliJ에 열린 이 프로젝트와 연결된 LDPlayer입니다. 소스는 이 프로젝트에서 수정하고, 연결된 LDPlayer에 빌드·설치·실행한 뒤 실제 동작 및 Logcat을 확인합니다. 단위 테스트 통과와 LDPlayer 동작 확인은 구분해 기록합니다. 여러 ADB 기기가 연결되면 대상 LDPlayer를 지정합니다.

JDK 17 이상, Android SDK 36, 인터넷 연결이 필요합니다. minSdk는 26, targetSdk는 36입니다. WebRTC Android SDK `144.7559.12`와 OkHttp `4.12.0`을 사용합니다.

```powershell
$env:JAVA_HOME = 'JDK 설치 경로'
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
./gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서는 이 폴더를 열고 Gradle Sync 후 실행합니다. APK는 개발용 debug 서명이며 배포용 release 서명 설정은 별도입니다.

## 이전 0.2.x 검증 결과 (2026-10-18)

- `assembleDebug testDebugUnitTest lintDebug` 성공. 단위/로컬 모의 HTTP 테스트 **15개 통과**, Lint 오류 0개·경고 12개.
- 테스트: 주소 검증 3개, HID 패킷·ASCII 매핑·전원 상태 유효성 4개, 실제 OkHttp + MockWebServer 인증/쿠키/리다이렉트/429/세션 검증 5개, RPC 순서·중복·이벤트·응답 유실·종료 처리 3개.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. 테스트 보고서: `app/build/reports/tests/testDebugUnitTest/index.html`. Lint 보고서: `app/build/reports/lint-results-debug.html`.
- 0.2.0 검증 시 연결 기기가 없었습니다. 0.2.1에서는 Android 9(API 28) 에뮬레이터에 설치하고 WebRTC 네이티브 초기화·SDP·ICE 시작을 검증했습니다. 실제 JetKVM 영상·입력·ATX/DC 전원 동작·Tailscale 경로는 **미검증**입니다. 모의 HTTP 및 에뮬레이터 테스트는 실장비 호환성 증거가 아닙니다.
- 프로토콜 확인 기준: 공식 `jetkvm/kvm` 커밋 `8f6c3001b1064eb32b3f66afd04efbe536bbaf87`. 실제 장비의 버전에 따라 호환성이 달라질 수 있습니다. JetKVM Cloud 계정 연결은 지원하지 않고 등록한 주소로 직접 연결합니다.

실장비 확인 순서: 비밀번호/무비밀번호 로그인 → 영상 → 검은 여백과 화면 가장자리 터치 → 키 입력/US ASCII → 확장 미설정·ATX·DC 상태 → 전원 명령(테스트 PC) → VPN 끊김·Wi-Fi 전환·다른 클라이언트 접속 → 백그라운드 전환·재연결. 전원 작업은 테스트 PC에서 검증하세요.

## 0.2.1 연결 시 강제 종료 수정 (2026-10-18)

- 사용자 로그: WebRTC `NetworkMonitorAutoDetect`의 `ConnectivityManager.getActiveNetworkInfo`에서 `ACCESS_NETWORK_STATE` 누락으로 `SecurityException` 발생, 이어서 네이티브 `network_thread`의 SIGABRT.
- `AndroidManifest.xml`에 `android.permission.ACCESS_NETWORK_STATE`를 추가했습니다. Android가 설치 시 부여하는 일반 권한이므로 별도 런타임 권한 요청은 필요하지 않습니다. 기존 설치 위에 수정 APK를 업데이트하면 됩니다.
- versionName `0.2.1`, versionCode `3`. 빌드·기존 테스트 15개·Lint 통과. `aapt dump permissions`로 최종 APK에 INTERNET 및 ACCESS_NETWORK_STATE 권한이 포함됐음을 확인했습니다.
- Android 9(API 28) x86_64 에뮬레이터에서 수정 APK 및 테스트 APK 설치 성공. `WebRtcSmokeInstrumentation`으로 설치된 권한, 실제 네이티브 PeerConnection 생성, video/rpc/hidrpc offer 및 local SDP 적용·ICE 시작·자원 해제를 검증했습니다. 결과 PASS, instrumentation code `-1`(RESULT_OK). 실장비와 전원 명령은 이 테스트에서 사용하지 않습니다.

재검증 명령:

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w kr.co.nexacore.jetkvm.test/kr.co.nexacore.jetkvm.WebRtcSmokeInstrumentation
```

## 0.2.2 입력창 Enter 처리 수정 (2026-10-18)

기존 입력창에는 명시적인 키보드 완료 동작이 없어 Enter 이후 포커스가 대화상자 버튼으로 이동할 수 있었습니다. 모든 입력창에 `EditorActions`를 적용했습니다.

- 장비 추가·수정: 이름 → 주소 → 포트 순서로 이동하고, 마지막 Enter는 저장을 실행합니다. 검증 오류가 있으면 입력 폼을 유지합니다.
- 장비 비밀번호: Enter 또는 키보드 이동(GO)은 연결을 실행합니다.
- Tailscale: Tailnet → API 토큰으로 이동한 뒤 Enter로 조회합니다. 조회 중에는 중복 요청하지 않습니다.
- 원격 텍스트: Enter 또는 키보드 전송(SEND)은 입력한 텍스트를 전송합니다. 원격 PC에 Enter 키 자체를 보내려면 별도 Enter 버튼을 사용합니다.
- 일반 Enter·숫자패드 Enter의 down/up을 입력창에서 소비해 취소/닫기 버튼으로 전달하지 않습니다. 취소는 취소 버튼 또는 뒤로가기로 수행합니다.
- versionName `0.2.2`, versionCode `4`. Android 9 에뮬레이터의 실제 AlertDialog에서 하드웨어 Enter/숫자패드 Enter, IME Next/Done/Go/Send, 확인 1회 실행, 검증 시 폼 유지, 비활성 확인 버튼의 중복 실행 차단을 검증했습니다. 네이티브 WebRTC 스모크 테스트도 통과했습니다.

## 0.2.3 LDPlayer + Tailscale 진단 결과

- 설치된 앱의 INTERNET 및 ACCESS_NETWORK_STATE 권한은 모두 허용 상태입니다. HTTP 허용 설정도 유지했습니다. 추가 권한은 넣지 않았습니다.
- VPN 감지 시 WebRTC factory의 disableNetworkMonitor 옵션을 사용해 개별 Wi-Fi 바인딩을 피하고 OS 라우팅을 사용하는 호환 처리를 추가했습니다. VPN이 없으면 기존 동작을 유지합니다. VPN 상태 변경 후에는 다시 연결합니다.
- 연결 단계와 ICE 후보 개수는 JetKvmConnection 태그로 기록합니다. 해당 태그에는 비밀번호, 쿠키, 주소, SDP 및 ICE 원문을 기록하지 않습니다. 타임아웃 화면에 마지막 단계와 후보 개수를 표시합니다.
- versionName 0.2.3, versionCode 5. 빌드 및 기존 테스트 15개 통과. Tailscale 연결 상태의 LDPlayer(API 28)에서 네이티브 검사 PASS: vpn=true, candidates=10, vpnCandidate=true. 로컬 VPN ICE 후보 생성 성공이며 원격 연결 성공과는 구분합니다.
- 저장된 장비에 HTTP 요청이 도달했고 인증이 필요한 상태임을 확인했습니다. 로그인은 시도하지 않았습니다. 실제 인증 후 영상과 입력은 사용자 재연결로 확인해야 합니다.
- 선택적 라이브 검사(probe=true)는 인증이 필요하면 중단합니다. 비밀번호 없는 장비만 세션을 시험하므로 기존 클라이언트 연결을 대체할 수 있습니다. 기본 테스트에서는 실행하지 않습니다.
- 참고: https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnectionFactory.java

## 0.2.3 실제 세션 확인 (2026-10-18 15:12 KST)

연결된 LDPlayer의 현재 앱 로그에서 AUTH_OK → VPN_OS_ROUTING → WS_OPEN → OFFER_SENT → ANSWER_APPLIED → ICE_CONNECTED → RPC_OPEN → HID_READY를 확인했습니다. 렌더러는 4초 동안 240프레임 수신·렌더링, 드롭 0, 약 60fps를 기록했습니다. 이 장비 세션에서는 H.265가 협상되어 Android HEVC 디코더로 재생됐습니다. 따라서 영상은 H.264로 고정되지 않고 장비와 디코더가 지원하는 코덱으로 협상됩니다.

사용자가 전달한 15:08/PID 8672의 프레임 0 로그와 15:12/PID 9397의 정상 세션을 구분해야 합니다. 실제 입력 이벤트의 원격 반영과 ATX/DC 전원 동작은 이번 로그 확인만으로 검증하지 않았습니다.

## 0.2.4 화면 켜짐 유지 및 암호화 비밀번호 저장 (2026-10-18)

- versionName `0.2.4`, versionCode `6`. Activity에 `FLAG_KEEP_SCREEN_ON`을 적용해 앱이 전면에 있을 때 화면 켜짐을 유지합니다. 백그라운드 상시 실행 기능은 아닙니다.
- 연결 대화상자에서 장비별 비밀번호 저장 여부를 선택하고 기존 저장값을 자동으로 채웁니다. 저장 시 AES-256-GCM, Android Keystore 키, 매번 새 IV, 장비 ID/URL AAD를 사용합니다. 저장 해제 후 연결·장비 삭제·주소 변경 시 기존 암호문을 삭제합니다. 복호화 실패 시 재입력을 안내합니다.
- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 성공. 기존 단위 테스트 15개 통과, Lint 오류 0개.
- 연결된 삼성 SM-X916N(Android 16/API 36)에 앱과 테스트 APK 업데이트 설치 성공. Keystore 암호화·저장소 인스턴스를 새로 만든 후 복호화·IV 무작위성·주소/장비 ID 불일치 거부·암호문 변조 거부·빈 비밀번호·삭제 검사 PASS. 테스트용 임시 항목은 검사 후 삭제합니다.
- 실제 Activity의 화면 유지 플래그와 기존 대화상자 Enter/Next/Done/Go/Send 검사 PASS. 네이티브 WebRTC offer/ICE 스모크 검사 PASS, instrumentation code `-1`(RESULT_OK). 이 버전에서 실제 JetKVM 로그인·영상·입력·전원은 재검증하지 않았으며, 화면 자동 꺼짐 제한 시간을 넘기는 장시간 실물 관찰은 수행하지 않았습니다.
- 기존 LDPlayer 대신 이번에 연결된 삼성 태블릿에서 검증했습니다. 테스트는 실제 장비 비밀번호와 전원 명령을 사용하지 않습니다.
- 구현 기준: [Android 화면 켜짐 유지](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [권장 암호화 알고리즘](https://developer.android.com/privacy-and-security/cryptography).

## 0.2.5 연결 후 검은 화면 수정 (2026-10-18)

- versionName `0.2.5`, versionCode `7`. `SurfaceViewRenderer`에 지정한 불투명 검정 View 배경을 제거했습니다. [SurfaceView](https://developer.android.com/reference/android/view/SurfaceView)는 별도의 영상 표면을 앱 창 뒤에 합성하므로, 앱 View의 불투명 배경으로 영상이 가려질 수 있습니다.
- 수정 전 삼성 SM-X916N의 현재 세션(PID 30715)에서 AUTH_OK, ICE_CONNECTED, RPC_OPEN, HID_READY와 H.265 1920×1080 디코딩, 약 58~59fps 렌더링을 확인했습니다. 렌더러가 프레임을 처리한다는 로그만으로 사용자 화면에 영상이 보이는 것은 아닙니다.
- 같은 기기에서 실제 `NativeSessionView`에 빨간 YUV 테스트 프레임을 넣고, 합성된 화면 중앙 픽셀을 검사했습니다. 수정 전 R=0/G=0/B=0으로 실패했고, 배경 제거 후 빨간색 표시 검사를 통과했습니다. 실제 원격 화면이나 비밀번호를 캡처 파일로 저장하지 않습니다.
- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 성공. 기존 단위 테스트 15개와 암호화 저장·입력창 Enter·네이티브 WebRTC 스모크 검사 통과. 앱/테스트 APK를 연결된 삼성 태블릿에 업데이트 설치했습니다.
- 수정 후 합성 영상의 화면 표시를 검증했으며, 실제 JetKVM 세션은 사용자가 다시 연결해 확인해야 합니다. 전원 명령은 실행하지 않았습니다.

화면 표시 회귀 검사 명령(원격 장비 접속 없이 테스트 색상 출력):

```powershell
adb shell am instrument --user 0 -w -e videoSurface true kr.co.nexacore.jetkvm.test/kr.co.nexacore.jetkvm.WebRtcSmokeInstrumentation
```

## 0.3.0 폰·태블릿 공통 UI (2026-10-18)

- versionName `0.3.0`, versionCode `8`. 상단 큰 제목과 하단 190dp 제어 패널을 제거했습니다. 상단 왼쪽은 장비 목록 → 다시 연결 → 연결 종료, 오른쪽은 전원 → 기타 기능 순서입니다. NexaCore 로고는 작게 유지합니다.
- 장비 목록에서는 연결 관련 아이콘이 비활성화됩니다. 연결 시도 중에는 중복 재연결을 막고 연결 종료는 허용합니다. 연결 완료·종료 후에는 다시 연결할 수 있고, 연결 종료 후에는 종료 아이콘을 비활성화합니다. 일시적인 네트워크 중단 중에는 다시 연결과 종료가 모두 가능합니다.
- 전원은 연결 완료와 유효한 최신 확장 상태가 모두 확인돼야 활성화합니다. ATX/DC 메뉴는 해당 확장에 맞는 항목만 표시하고 모든 명령은 확인 대화상자를 거칩니다. 메뉴 선택·확인 시점에도 상태를 다시 검사하며, 기존 클라이언트의 실행 전 상태 재조회와 자동 재시도 금지 계약을 유지합니다.
- 기타 기능에는 텍스트 입력, Esc/Tab/Enter/Backspace/Ctrl+Alt+Del, 우클릭, 연결 상태가 있습니다. 원격 입력은 HID 준비가 끝나야 활성화되며 메뉴가 열린 뒤 연결이 종료돼도 명령을 보내지 않습니다. 연결 상태는 언제든 확인할 수 있습니다.
- 56dp 상단 바와 48dp 아이콘 터치 영역, 한국어 접근성 설명과 길게 누르기 도움말을 적용했습니다. 상태는 작은 두 줄 이내로 표시합니다. 시스템 바·카메라 영역을 피하고 장비 목록은 최대 840dp 폭의 중앙 카드로 정리했습니다. 입력 대화상자는 최대 480dp 폭과 스크롤을 사용합니다.
- `VideoViewport`가 실제 영상 SurfaceView를 남은 공간 안에 원본 비율로 맞춥니다. 검은 여백은 부모 뷰에 두고 SurfaceView 자체는 투명하게 유지합니다. 연결 종료 시에는 잔여 영상을 지웁니다.
- 빌드·기존 단위 테스트 15개·Lint 오류 0개. 삼성 SM-X916N(API 36)에서 툴바 상태별 동작, 홈 화면 비활성 상태와 시스템 바 회피, 320/360/600/840dp 툴바 경계·터치 영역, 암호화 저장·입력창 Enter·네이티브 WebRTC 스모크 검증을 모두 통과했습니다.
- 영상 표시 검사는 태블릿 전체 영역과 320×640dp, 360×720dp, 640×320dp 영역에서 테스트 YUV 영상의 실제 화면 픽셀, 영상 뷰 16:9 비율, 표시 영역 내 영상 경계, 검은 여백 및 툴바 경계를 모두 통과했습니다. 크기 변경 시 이전 크기로 한 번 더 배치되는 문제를 막기 위해 영상과 장비 목록의 크기를 측정 단계에서 계산합니다. 휴대폰 크기는 태블릿 내 제한된 영역으로 검사하며 **휴대폰 실기기 검증과 구분**합니다. 실제 JetKVM 입력·전원 명령은 자동 검사에서 실행하지 않습니다.
- 테스트용 화면 캡처는 `verification/ui-tablet.png`, `verification/ui-phone320.png`, `verification/ui-landscape640.png`이며 빨간 영역은 원격 화면 대신 사용한 합성 테스트 영상입니다.

## 0.3.1 원격 해상도·비율에 따른 전체 영상 맞춤 (2026-10-18)

- versionName `0.3.1`, versionCode `9`. 영상 크기와 터치 좌표의 기준을 렌더러의 실제 프레임 해상도·회전 알림으로 통일했습니다. 별도 트랙 크기 sink를 제거하고 원격 해상도가 바뀌면 표시 영역을 다시 계산합니다.
- 표시 배율은 `min(표시 영역 너비 / 회전 적용 영상 너비, 표시 영역 높이 / 회전 적용 영상 높이)`입니다. 확대 잘라내기를 하지 않고 중앙에 전체 영상을 배치하며 남는 부분은 검은 여백으로 유지합니다. 폰·태블릿, 가로·세로 화면 모두 같은 계산을 사용합니다.
- `VideoViewport`는 일반 ViewGroup에서 영상 SurfaceView를 한 번만 측정합니다. 전체 영역 측정 후 다시 축소 측정하는 과정을 제거하고, 하드웨어 고정 표면 크기 조절도 명시적으로 비활성화했습니다.
- 기존 단색 검사만으로는 영상 내부 가장자리 잘림을 판별할 수 없으므로, 네 모서리의 빨강·초록·파랑·흰색 표식을 실제 합성 화면에서 검사하도록 변경했습니다. 검사 중 수동으로 영상 크기를 지정하지 않고 실제 렌더러의 해상도 알림을 사용합니다.
- 삼성 SM-X916N(API 36)에서 태블릿 전체 영역·320×640dp·360×720dp·640×320dp와 9개 영상 형식 조합 **36건 통과**. 16:9, 4:3, 16:10, 21:9, 9:16, 90/180/270도 회전, 1280×720 프레임을 연속 전환하며 네 모서리 픽셀·원본 비율·영상 경계·검은 여백을 검사했습니다. 빌드·단위 테스트 15개·Lint 오류 0개. 폰 크기는 태블릿 내 제한된 영역으로 검증했으며 실제 원격 JetKVM 세션의 화면은 재검증하지 않았습니다.
- 테스트 표식 화면: `verification/fit-tablet-4x3.png`, `verification/fit-phone-ultrawide.png`. 원격 화면을 캡처한 자료가 아닙니다.
- 구현 참고: [WebRTC SurfaceViewRenderer의 해상도·회전 알림과 크기 조절](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/SurfaceViewRenderer.java).

## 0.4.0 장비 목록 아이콘 및 확장 전원 관리 (2026-10-18)

- versionName `0.4.0`, versionCode `11`. 각 장비 카드의 **연결 → 수정 → 삭제 → 파워 켜기 → 파워 끄기 → 재부팅**을 한 줄 아이콘으로 배치했습니다. 별도의 하단 버튼 패널은 추가하지 않습니다. 각 아이콘은 48dp 터치 영역·한국어 접근성 설명·길게 누르기 설명을 갖습니다. 320dp 화면에서는 카드 좌우 여백을 아이콘 줄에서 활용하며 더 좁은 영역은 가로 스크롤합니다.
- 카드의 **전원 확장 확인 · 탭하여 연결 ↻**를 누르면 기존 비밀번호 대화상자를 거쳐 RPC 전용 WebRTC 연결로 상태를 조회합니다. 영상·HID 채널을 만들지 않습니다. 목록을 열기만 해서는 장비에 자동 연결하지 않습니다. 이 연결은 기존 원격 클라이언트를 대체할 수 있으므로 입력 대화상자에 안내합니다. 한 번에 한 장비를 관리하며 상단 연결 종료로 끝낼 수 있습니다.
- ATX 전원 LED가 꺼짐이면 켜기, 켜짐이면 끄기·재부팅을 활성화합니다. 켜기/끄기는 PC 전원 버튼의 짧은 누름이며 실제 종료·절전 동작은 OS 설정과 LED 배선에 좌우됩니다. 재부팅은 ATX 리셋이므로 저장하지 않은 데이터가 손실될 수 있습니다. 기존 강제 종료(5초 누름)는 목록의 끄기 아이콘에 연결하지 않습니다.
- DC는 현재 출력에 따라 켜기/끄기를 활성화하며 재부팅은 비활성화합니다. DC 출력 변경은 OS 정상 종료 요청이 아닙니다. 미설정·미지원·알 수 없는 상태·연결 실패·상태 유효기간 초과 시 세 전원 아이콘을 비활성화합니다. 목록은 1초마다 상태 유효기간을 반영하고 연결 중에는 기존 5초 주기로 장비 상태를 갱신합니다.
- 모든 전원 작업은 확인 대화상자를 거칩니다. 확인 시점과 실제 RPC 실행 직전에 상태를 재검증합니다. 켜기 요청 때 이미 켜져 있거나 끄기 요청 때 이미 꺼져 있으면 ATX 버튼을 누르지 않습니다. 명령은 자동 재시도하지 않습니다. 앱이 백그라운드로 이동하면 RPC 연결과 상태를 폐기합니다.
- `getActiveExtension`과 LED/출력 상태는 설정·캐시 정보이며 물리적 확장 케이블 연결을 별도로 증명하지 않습니다. 이전 버전과 같은 전원 제어 계약을 유지합니다.
- 빌드·단위 테스트 **18개**·Lint 오류 0개. 삼성 SM-X916N(API 36)에서 카드 폭 288/328/568/808dp의 여섯 아이콘 한 줄 배치·48dp 터치 영역, ATX/DC/오래된 상태 활성화, 기존 툴바·암호화 저장·입력창·네이티브 WebRTC 검사 통과.
- 실제 HTTP 인증 → WebSocket 시그널링 → 네이티브 WebRTC data-only RPC를 로컬 모의 장비와 연결해 확장 상태 조회·ATX 켜기/끄기/리셋·상태 변경 시 명령 차단 검사를 통과했습니다. **실제 JetKVM 보드 전원 출력·새 RPC 전용 세션의 실장비 호환성은 미검증**이며 자동 테스트에서 실장비 전원 명령을 보내지 않습니다.
- 프로토콜 근거: [인증/시그널링 라우트](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/web.go), [확장 상태 및 전원 RPC](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/jsonrpc.go).

## 0.4.1 도메인 기반 Android 패키지 변경 (2026-10-18)

- versionName `0.4.1`, versionCode `12`. applicationId·namespace·Kotlin 소스·단위 테스트·instrumentation 패키지를 `kr.co.nexacore.jetkvm`으로 통일했습니다. 소스 경로는 `app/src/{main,test,androidTest}/java/kr/co/nexacore/jetkvm/`입니다. 테스트 APK ID는 `kr.co.nexacore.jetkvm.test`이며 README 실행 명령도 새 패키지를 사용합니다.
- 이전 앱 `com.nexacore.jetkvm`과는 Android에서 서로 다른 앱입니다. APK 업데이트로 기존 앱의 설정이나 Keystore가 자동 이전되지 않습니다. 이전 앱은 삭제하지 않으며 저장 비밀번호를 새 앱에서 다시 입력해야 합니다. 암호문을 복사하거나 평문으로 내보내지 않습니다.
- 디버그 기기에서 등록된 장비 목록만 옮길 때에는 이전 앱의 `shared_prefs/devices.xml`을 새 앱의 비어 있는 저장소에 복사합니다. 기존 새 앱 목록이 있으면 덮어쓰지 않습니다. 복사 대상은 이름·주소·장비 ID뿐이며 암호화 비밀번호 저장소와 Keystore 키는 제외합니다. 일반 배포 환경에서의 자동 마이그레이션 기능은 아닙니다.
- 연결된 삼성 SM-X916N(API 36)에 새 앱·테스트 APK를 설치하고 장비 1개의 목록을 이전·재읽기 검증했습니다. 새 앱을 실행했으며 이전 앱과 데이터는 유지합니다. 빌드·단위 테스트 18개·Lint 오류 0개, 새 패키지의 암호화 저장·전원 RPC·툴바·입력·WebRTC 및 영상 비율/모서리 36개 조합 검사를 모두 통과했습니다. 최종 APK의 package/launchable activity도 `kr.co.nexacore.jetkvm` 기준으로 확인했습니다.
- 과거 버전의 `verification/build-summary.json`에 남은 기존 패키지명은 해당 시점의 검증 기록으로 보존합니다. 실행 소스와 현재 Gradle 설정에는 기존 패키지명이 남지 않도록 검사합니다.

## 0.4.2 장비 카드의 기타 메뉴와 전원 상태 안내 (2026-10-18)

- versionName `0.4.2`, versionCode `13`. 장비 이름 오른쪽에 48dp **기타(⋮)** 버튼을 추가했습니다. **전원 확장 확인 / 새로고침**, **전원 관리 연결 종료**를 이 메뉴에서 실행합니다. 연결 종료는 해당 장비에 전원 관리 연결이 있을 때만 활성화됩니다.
- 조회 전에는 **전원 확인 필요 · 기타(⋮)에서 조회**를 골드색 상태 영역으로 표시합니다. 상태 조회에 성공하면 ATX/DC 상태를 그린색으로 표시합니다. 기존 작은 상태 문구를 눌러야 했던 숨은 동작은 제거했습니다.
- 연결·수정·삭제·켜기·끄기·재부팅 여섯 아이콘은 기존 한 줄 배치를 유지합니다. 기타 버튼은 카드 제목 줄에 배치해 폰에서도 일곱 아이콘이 한 줄에 몰리지 않게 했습니다. 기존 전원 명령 확인과 상태 검증 계약은 유지합니다.
- 빌드·단위 테스트 18개·Lint 오류 0개, 태블릿에서 기존 암호화·전원 모의 RPC·툴바·입력·WebRTC 검사 통과. 설치된 앱의 카드 기타 버튼을 열어 두 메뉴 항목이 실제로 표시되는 것을 확인했습니다. 메뉴 표시 확인 중 인증 연결이나 실장비 전원 명령은 실행하지 않았습니다.

## 0.4.3 확인된 확장 정보 유지 (2026-10-18)

- versionName `0.4.3`, versionCode `14`. 정상 조회가 완료된 ATX/DC 종류를 장비 ID·접속 URL과 함께 저장합니다. 연결 종료·백그라운드 전환·앱 재시작 후에는 골드색 ‘전원 확인 필요’ 대신 중립색 ‘ATX/DC 전원 확장 · 마지막 확인 정보’를 표시합니다. 현재 연결에서 정상 조회된 LED/출력 상태는 기존 그린색으로 표시합니다.
- 저장하는 것은 확장 종류뿐입니다. 과거 전원 켜짐/꺼짐과 조회 시각을 복구하거나 전원 아이콘 활성화에 사용하지 않습니다. 버튼은 현재 연결의 최신 상태를 기준으로만 활성화하고 명령 직전 재검증합니다. 장비 삭제·URL 변경 시 마지막 확인 정보도 삭제합니다.
- 이전 버전에는 조회 이력을 저장하지 않았으므로 업데이트 후 최초로 정상 조회한 시점부터 이 정보가 유지됩니다.
- 빌드·기존 단위 테스트 18개·Lint 오류 0개 및 태블릿의 암호화 저장·전원 모의 RPC·툴바·입력·WebRTC 회귀 검사 통과. 0.4.3 업데이트 설치·재실행 완료. 실장비 전원 명령은 실행하지 않았습니다.

## 0.4.4 전원 관리 연결 자동 복원 (2026-10-18)

- versionName `0.4.4`, versionCode `15`. 마지막으로 정상 조회한 장비를 기억하고, 목록 화면에 돌아오거나 앱을 다시 실행하면 저장된 암호화 비밀번호로 전원 관리 연결을 자동 복원합니다. 조회 성공 시 실제 LED/출력 상태와 켜기·끄기·재부팅 버튼이 갱신됩니다. 과거 전원 상태로 버튼을 활성화하지 않습니다.
- 이전 버전의 조회 이력만 있고 마지막 장비 정보가 없으면, 확인된 장비가 정확히 하나일 때만 복원 대상으로 사용합니다. 마지막 장비가 삭제됐거나 URL이 변경된 경우 다른 장비로 대체하지 않습니다. 여러 장비가 있고 선택 이력이 없으면 기타 메뉴에서 장비를 직접 선택해 확인합니다.
- 자동 연결에는 비밀번호 암호화 저장이 필요합니다(비밀번호 없는 모드는 빈 값을 저장). 저장값이 없거나 복호화가 실패하면 입력 안내를 표시합니다. 목록을 방문한 동안 자동 복원은 한 번만 시도하며 전원 명령은 자동 실행·재시도하지 않습니다.
- ‘전원 관리 연결 종료’를 직접 누르면 이후 자동 복원도 중지합니다. 기타 메뉴에서 다시 수동 연결하면 복원을 재개합니다. 일시적인 pause에서는 연결을 유지하고 화면이 완전히 숨겨지는 stop 시 연결을 종료한 후 복귀할 때 다시 연결합니다.
- 빌드·단위 테스트 21개·Lint 오류 0개 및 로컬 모의 전원 RPC·암호화 저장·툴바·입력·네이티브 WebRTC 회귀 검사 통과. 자동 복원 선택 테스트에는 마지막 장비 선택·삭제된 장비 대체 금지·하나의 기존 이력만 채택하는 조건을 포함합니다. 테스트 실행 중에는 실제 장비 자동 연결을 메모리에서만 억제하며 사용자 저장 설정을 변경하지 않습니다.
- 연결된 태블릿에서 등록 장비/저장 비밀번호가 없어 실제 계정으로 앱 재시작 후 자동 복원은 미검증입니다. 장비 등록과 비밀번호 저장 후 최초 정상 조회가 필요합니다. 실장비 전원 명령은 실행하지 않았습니다.

## 0.4.5 연결 종료 시 상단 메뉴 비활성화 (2026-10-18)

- versionName `0.4.5`, versionCode `16`. 연결이 완전히 종료되면 상단 왼쪽 목록·재접속·접속 끊기를 모두 비활성화합니다. 목록 복귀는 Android 뒤로 가기로 가능합니다.
- 연결 중에는 재접속을 비활성화하고, 일시적인 통신 중단 상태에서는 복구를 위한 재접속을 유지합니다.
- 빌드·단위 테스트 21개·Lint 및 태블릿의 상태별 툴바·암호화 저장·로컬 모의 전원 RPC·입력·네이티브 WebRTC 검사 통과. 업데이트 설치 완료. 실장비 연결 단절은 별도 미검증입니다.

## 0.4.6 장비 목록 너비 확장 및 아이콘 정리 (2026-10-18)

- versionName `0.4.6`, versionCode `17`. 목록의 840dp 최대 너비 제한을 없애고 시스템 안전 영역 안에서 좌우 8dp만 남겨 화면 너비를 사용합니다.
- 목록 헤더의 장비 추가와 Tailscale 가져오기를 48dp 아이콘으로 제공합니다. 연결·수정·삭제·켜기·끄기·재부팅 아이콘은 한 줄을 유지합니다.
- 전원 안내 영역을 아이콘과 짧은 상태(ATX/DC·켜짐/꺼짐, 이전 확인, 확인 중)로 줄였습니다. 누르면 기존 상세 안내를 확인할 수 있습니다. 아이콘에는 접근성 설명과 길게 누르기 설명을 제공합니다. 장비명·주소·상태 및 확인 대화상자의 텍스트는 유지합니다.
- 빌드·단위 테스트 21개·Lint와 태블릿 회귀 검사 통과. 가용 너비 전체 사용 검사를 갱신했습니다. 0.4.6 설치·재실행 후 실제 장비 카드의 전체 너비와 상태/아이콘 배치를 확인했습니다. 실장비 전원 명령은 실행하지 않았습니다.
## 0.5.0 한국어·영어·일본어 (2026-10-18)

- versionName `0.5.0`, versionCode `18`. 장비 목록과 원격 화면의 오른쪽 기타(…) 메뉴에 **언어 설정**을 추가했습니다. 한국어·English·日本語 중 선택하고 적용하면 화면을 다시 구성합니다. 취소하면 기존 선택을 유지합니다.
- 기본 언어는 기기의 시스템 언어와 관계없이 **한국어**입니다. 선택한 언어는 앱 전용 설정에 저장하며 앱 재실행 후에도 유지합니다. 시스템 전체 언어 설정은 변경하지 않습니다.
- 메뉴·입력창·버튼·확인창·전원/연결 상태·앱 오류·아이콘 접근성 설명과 툴팁 등 **147개 문구**를 `app/src/main/res/values/strings.xml`(한국어 기본), `values-en/strings.xml`, `values-ja/strings.xml`로 분리했습니다. 사용자 장비 이름·주소와 키 이름, 제품명은 유지합니다. 원격 텍스트 전송은 기존 US ASCII 제한을 유지합니다.
- 원격 연결 중 언어를 바꾸면 현재 연결이 종료되고 장비 목록으로 돌아가므로 언어 설정창에 이를 안내합니다. 전원 관리 자동 복원은 기존 정책을 사용하며 언어 변경으로 전원 명령을 보내지 않습니다.
- `LanguageSettings.kt`가 Activity의 언어 구성과 저장을 담당하고, Application에서 준비한 `AppText`가 백그라운드 연결 메시지도 같은 언어로 표시합니다. 순수 JVM 주소/HTTP 검사에서는 한국어 대체 문구를 사용합니다. App Bundle의 언어별 분할을 비활성화해 세 언어를 설치 파일에 모두 포함합니다.
- `python tools/check_localizations.py`로 세 언어의 키·빈 번역·서식 인자를 검사합니다. 147개 모두 통과했습니다. 빌드·단위 테스트 21개·Lint 오류 0개 및 기존 태블릿 회귀 검사 통과.
- 태블릿에서 실제 설정창을 통한 한국어→영어→일본어→한국어 적용, Activity 재생성, 앱 화면 재실행 후 유지, 기본 한국어, 취소, 입력 오류·아이콘 설명 번역을 검증했습니다. 테스트는 기존 언어 설정을 복원하고 실장비 자동 연결을 억제했습니다. 실장비 전원 명령은 실행하지 않았습니다.
## 0.5.1 목록 상단 버튼과 전원 연결 상태 분리 (2026-10-18)

- versionName `0.5.1`, versionCode `19`. 장비 목록의 상단 재접속·접속 해제가 전원 관리 자동 복원 상태에 따라 활성화되던 문제를 수정했습니다.
- 목록의 상단 목록·재접속·접속 해제는 항상 비활성화합니다. 이 버튼의 원격 세션 콜백도 제거했습니다. 장비별 전원 연결 확인·종료는 카드의 기타(⋮) 메뉴를 사용합니다.
- 공통 툴바에 원격 세션 조작 사용 여부를 명시하고, 목록 화면에서는 모든 연결 단계에서 왼쪽 세 버튼이 비활성화되는 실제 Activity 회귀 검사를 추가했습니다. 원격 화면의 기존 상태별 동작은 유지합니다.
- 빌드·단위 테스트 21개·Lint 및 태블릿 회귀 검사 통과. 업데이트 설치·재실행 후 실제 ATX 상태가 켜짐으로 조회되고 카드 전원 조작은 활성화된 상태에서도 상단 왼쪽 세 버튼은 회색으로 비활성화됨을 화면에서 확인했습니다. 실장비 전원 명령은 실행하지 않았습니다.
## 0.5.2 수동 연결 종료 후 목록 복귀 (2026-10-18)

- versionName `0.5.2`, versionCode `20`. 원격 화면에서 상단 접속 끊기를 누르면 입력·세션을 종료하고 장비 목록으로 자동 이동합니다. 반복 종료 호출은 기존 종료 가드로 차단합니다.
- 네트워크 오류나 백그라운드 전환 시의 기존 처리는 유지합니다. 목록 복귀 후 전원 관리 연결 자동 복원은 기존 정책을 사용합니다.
- 빌드·단위 테스트 21개·Lint 및 태블릿의 기존 다국어·전원 모의 RPC·암호화·툴바·입력·WebRTC 회귀 검사 통과. 설치 후 재실행 완료. 실장비 원격 접속 종료 후 자동 이동 흐름은 별도 미검증입니다.
## 0.5.3 접속 끊기 후 전원 연결 재접속 차단 (2026-10-18)

- versionName `0.5.3`, versionCode `21`. 이전 버전은 원격 화면을 종료하고 목록으로 돌아오면 전원 관리용 WebRTC가 자동 복원됐습니다. 이 연결도 장비의 접속자로 집계될 수 있어 PC 모니터 설정 프로그램이 접속 종료를 감지하지 못하는 문제가 있었습니다.
- 원격 화면의 접속 끊기 콜백을 목록 이동과 분리했습니다. 명시적인 접속 끊기는 전원 연결도 종료하고 `powerRestoreEnabled=false`를 저장한 뒤 목록으로 돌아갑니다. 앱 재실행·복귀 시에도 자동 재접속하지 않습니다. 장비별 기타(⋮)에서 전원 확장 확인을 수동으로 실행하면 기존 자동 복원을 다시 활성화합니다.
- 종료 설정은 비동기 `apply()` 대신 동기 `commit()`으로 저장을 마칩니다. 종료 직후 프로세스를 끝내는 검사에서 비동기 저장은 이전 값이 남아 다시 연결되는 현상이 재현됐으며, 수정 후 종료 직후 디스크와 앱 강제 종료·재실행 후 모두 `false`를 확인했습니다.
- 장비 목록으로 이동하는 동작과 명시적 전체 접속 종료는 구분합니다. 장비 이름·URL·암호화 비밀번호·확인 이력은 유지하며 PC 모니터 프로그램의 설정은 변경하지 않습니다.
- 빌드·단위 테스트 21개·Lint 및 태블릿 회귀 검사 통과. 종료 버튼→목록 복귀→자동 복원 금지·중복 종료 검사와 로컬 모의 서버에서 시그널링 소켓이 실제 닫히는 검사를 포함합니다. 전원 모의 검사도 현재 언어의 문구를 기준으로 판정하도록 수정했습니다.
- 현재 태블릿에는 종료 동작을 적용하고 0.5.3을 재실행했습니다. PC 프로그램의 상태 파일에서 `ActiveSessions=0`, `Outside=false`, 전환 오류 없음과 최신 상태 수신을 확인했습니다. 기존에는 접속 1명이었습니다. 실장비 전원 명령은 실행하지 않았으며 원격 영상 연결부터 수동 종료까지의 실장비 전체 흐름은 별도 미검증입니다.
## 0.6.0 전원 관리와 원격 영상의 PC 모니터 감지 분리 (2026-10-18)

- versionName `0.6.0`, versionCode `22`. **기본 JetKVM 펌웨어를 유지**하면서 Android 앱의 영상 연결 상태를 Windows `jetkvm-monitor-switcher`에 직접 알립니다. 장비 기타(⋮) → **PC 모니터 연동**에서 PC의 연결 코드를 저장·해제합니다. 현재 태블릿과 PC에는 연동을 설정했습니다.
- PC 트레이 → **Android 앱 원격 화면 감지…**에서 감지 방식을 선택하고 연결 코드를 복사합니다. 앱 감지 모드에서는 이 앱의 실제 영상 연결만 모니터 전환 기준으로 사용합니다. 브라우저 등 다른 클라이언트의 접속은 감지하지 않습니다. 기존 MQTT 전체 접속 수 방식으로 되돌릴 수 있으며 자동으로 혼용하지 않습니다.
- 영상 WebRTC가 연결되면 보기 시작, 수동 종료·오류로 종료되면 보기 종료를 전송합니다. 전원 전용 WebRTC는 보기 시작 신호를 보내지 않습니다. 목록 화면은 비시청 상태를 알리므로 전원 연결이 유지돼도 PC는 집 모드를 유지합니다. 영상 접속은 외부 모드, 종료는 기존 10초 유예 후 집 모드로 전환합니다.
- 연결 코드는 사용자 인증용 비밀 키를 포함합니다. Android에서는 기존 Keystore AES-256-GCM 저장소에 장비 ID·URL에 묶어 암호화하며, 장비 삭제·URL 변경·연동 해제 시 삭제합니다. PC 키는 Windows DPAPI CurrentUser로 보호합니다. 코드·키·장비 비밀번호를 로그/검증 결과에 기록하지 않습니다.
- 기본 TCP 1884로 HMAC-SHA256 서명된 상태 스냅샷을 전송합니다. 키나 장비 비밀번호는 전송하지 않으며, 패킷에는 시청 여부·송신자·시각·프로세스 세대·순번만 포함합니다. PC는 대상 장비·서명·시각·재전송·순서 역전을 검사합니다. 종료 상태는 같은 순번으로 재전송할 수 있지만 PC는 상태를 반복 적용하지 않습니다. 암호화된 전송 채널은 아니므로 신뢰하는 LAN/Tailscale에서 사용합니다.
- 시청 중 신호가 30초 이상 끊기면 PC는 상태 미확인으로 기존 모드를 유지합니다. 단절을 임의로 접속 0명으로 바꾸거나 MQTT 전체 수로 대체하지 않습니다. 다음 정상 상태 알림으로 복구합니다. 앱/PC의 시각 차이가 2분을 넘으면 알림을 거절합니다.
- Android 빌드·단위 테스트 21개·Lint·153개 다국어 문구 검사와 태블릿의 기존 회귀 검사 통과. 추가 검사: 실제 TCP 서명 신호(비시청→시청→종료), 순번/송신자, 암호화 연결 코드 저장. PC Release 빌드·자체 검사·DisplayConfig 읽기/검증 통과.
- 실제 저장 계정은 앱 내부의 암호화 저장소를 통해서만 사용해 실장비를 검증했습니다. 전원 전용 연결 성공 중 PC `Source=android-viewer, ActiveSessions=0, Outside=false`; 영상/HID 연결 성공 중 `ActiveSessions=1, Outside=true`; 종료 ACK 후 10초 유예가 지나면 `ActiveSessions=0, Outside=false`, 전환 오류 없음 확인. 전원/HID 입력 명령은 보내지 않았습니다. 기존 방화벽의 해당 실행 파일 수신 허용 규칙을 사용했고 새 규칙은 추가하지 않았습니다.
## 0.6.1 전체 장비 상태와 앱 수명 관리 (2026-10-18)

- versionName `0.6.1`, versionCode `23`. 목록에서 마지막 장비 한 대를 고르던 자동 복원을 모든 등록 장비의 개별 전원 연결로 변경했습니다. 과거 확장 조회 이력이나 기존 `powerRestoreEnabled=false` 설정에 관계없이 새로 앱이 시작되거나 백그라운드에서 복귀하면 각 장비의 현재 상태를 한 번씩 확인합니다. 동일한 전면 실행 구간에서 목록 재구성·단순 resume으로 중복 연결하거나 실패한 장비에 자동 재시도하지 않습니다. 추가·수정한 장비는 다시 확인합니다.
- 성공한 전원 연결은 목록이 표시되는 동안 기존 5초 주기 상태 조회를 유지합니다. 장비별 연결·스냅샷·오류를 분리해 한 장비의 실패나 수동 종료가 다른 장비의 버튼을 비활성화하지 않습니다. 상태 안내에서 인증·통신 오류를 직접 표시합니다. 원격 영상으로 이동할 때 전원 연결은 모두 종료하며, 목록 복귀 시 다시 확인합니다.
- 최신 ATX/DC 상태가 확인돼야 버튼이 활성화됩니다. 꺼짐에서는 켜기만, 켜짐에서는 끄기와 ATX 재부팅만 허용합니다. DC 재부팅, 확장 미설정·미지원·상태 불명·연결 실패·중단·오래된 상태는 비활성화합니다. 실행 전 재조회와 사용자 확인, 명령 자동 재시도 금지 정책은 유지합니다. 기본 펌웨어의 설정된 확장 종류와 LED/출력 조회를 사용하며 물리 확장 연결 자체를 증명하지는 않습니다.
- 저장된 암호화 비밀번호가 있으면 사용합니다. 저장값이 없으면 `/device`로 비밀번호 없는 모드인지 확인하며, 인증이 필요한 장비에는 빈 비밀번호 로그인을 보내지 않고 입력·저장을 안내합니다. 복호화 실패도 해당 카드에 표시합니다.
- Activity `onStop`에서 영상과 모든 전원 연결을 종료하고 스냅샷을 폐기합니다. 대기 중이던 자격 증명 읽기와 이전 연결 콜백은 세대·연결 객체 검사로 새 연결을 만들거나 상태를 덮어쓰지 못하게 합니다. 다시 시작하면 현재 상태를 새로 조회합니다. Android가 프로세스를 강제 종료하는 경우 수명 콜백 실행은 보장되지 않으며 OS가 프로세스 소켓을 정리합니다.
- 수동 원격 접속 끊기는 현재 전면 실행 구간의 자동 재연결을 차단합니다. 앱을 중단하고 다시 시작하면 이번 요구사항에 따라 전체 장비 확인을 재개합니다. 이 동작은 0.5.3의 재실행 후에도 자동 복원 금지 정책을 대체합니다. 개별 카드의 전원 연결 종료는 그 장비만 종료하며 다음 앱 시작 또는 수동 조회 전까지 재연결하지 않습니다.
- Microsoft JDK `17.0.20`으로 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 성공, 단위 테스트 **23개** 통과, Lint 오류 0개·경고 19개. `python tools/check_localizations.py`: 세 언어 153개 문구 통과. 최초 선택한 IntelliJ JBR 25는 현재 Gradle/Kotlin 조합과 호환되지 않아 설치된 JDK 17로 빌드했습니다.
- 삼성 테스트 태블릿에 앱·테스트 APK 업데이트 설치. 전체 instrumentation PASS (`INSTRUMENTATION_CODE: -1`). 신규 검사는 로컬 모의 장비 4개의 각 시작당 1회 조회, 저장 비밀번호 없는 인증 장비의 로그인 생략, 독립 ATX/DC/미설정 버튼, stop 시 시그널링 소켓 3개 종료 및 스냅샷·버튼 초기화, 재시작 후 1회 재확인을 검증합니다. 버튼 상태 분기에는 주입한 스냅샷을 사용하고, 실제 WebRTC 전원 RPC는 기존 로컬 모의 검사로 별도 검증했습니다. 기존 암호화·다국어·툴바·입력·수동 종료·PC 상태 알림·네이티브 WebRTC 검사도 통과했습니다.
- 0.6.1 설치 후 앱을 강제 종료·재실행했습니다. 현재 저장된 실장비에서 `AUTH_OK → ICE_CONNECTED → RPC_OPEN`과 화면의 `ATX · 켜짐`, 켜기 비활성·끄기 활성 상태를 확인했습니다. 여러 실장비 동시 조회와 실물 전원 전환은 별도 미검증입니다.
- 증거: `verification/0.6.1-build.log`, `verification/0.6.1-instrumentation.txt`. 실장비 전원 명령은 실행하지 않았습니다.

## WebRTC 없는 MQTT/API 상태 조회 시험 (2026-10-18)

전원 명령이나 장비 설정 변경 없이 조회 가능 범위를 시험했습니다. 자동 WebRTC 접속을 방지하기 위해 태블릿의 앱을 강제 종료했고 시험 후에도 종료 상태로 두었습니다. 앱 기능을 MQTT로 전환한 것은 아닙니다.

- **실장비 HTTP:** 인증 없는 `GET /device/status`가 200, `isSetup=true`를 반환했습니다. `GET /device`는 401로 인증 필요를 확인했습니다. `GET /metrics`는 200이며 설치 버전 0.5.9(dev)를 확인했지만, 현재 노출 지표에는 ATX/DC 전원 상태가 없었습니다. 이 결과로는 장비 HTTP 응답 여부만 판단할 수 있습니다.
- **실장비 MQTT:** PC 설정의 기존 JetKVM/수신 주소와 포트로 임시 MQTTnet 4.3.7 수신기를 띄워 45초 대기했지만 장비 연결·발행을 받지 못했습니다. 기존 방화벽에서 허용된 Python 경로를 사용하는 장비 IP 한정 TCP 릴레이로도 다시 확인했으나 수신하지 못했습니다. 기존 전송 대상이 현재 PC 설정과 다른지, MQTT가 꺼져 있는지, 네트워크/방화벽 문제인지는 이 시험만으로 확정할 수 없습니다. 실제 MQTT 연결 성공이나 실제 ATX 상태 수신으로 기록하지 않습니다.
- **로컬 MQTT 통합:** loopback 모의 발행자가 `status`, `atx/state`, `sessions/state` 세 메시지를 QoS 1·retained로 발행하고 구독자가 실시간으로 수신했습니다. 구독자 재접속 후 같은 보관 메시지 세 개를 다시 받았으며 전원 켜짐·세션 0 상태를 읽었습니다. 결과 `PASS_LOCAL_FIXTURE_ONLY`; 실장비·Android 앱 통합 증거가 아닙니다.
- 임시 수신기는 상태 토픽만 중계하고 장비의 명령 구독을 거절했습니다. 전원 명령 전송 0회, WebRTC 연결 0회, 장비·방화벽·PC 모니터 설정 변경 없음. 종료 후 임시 1883/1885/1886 listener가 없음을 확인했습니다. PC는 기존 Android 영상 감지 모드로 유지됩니다.
- 재현 도구: `tools/mqtt-status-probe/`. 실제 수신 시험은 `dotnet run --project tools/mqtt-status-probe/MqttStatusProbe.csproj -- verification/mqtt-live-probe.json`, 모의 검사는 마지막에 `--fixture`를 추가합니다. 기존 수신 포트가 사용 중이면 실장비 수신 시험을 실행하지 않습니다. `--local-relay` 시험은 별도 1885 수신기와 `relay.py`를 함께 사용합니다. 도구는 PC의 기존 설정 파일에서 주소를 읽으며 비밀번호·쿠키를 기록하지 않습니다.
- 증거: `verification/mqtt-api-http-probe.json`, `mqtt-live-probe.json`, `mqtt-live-probe-relay.json`, `mqtt-fixture-probe.json` 및 각 로그. 실장비 MQTT 구독을 완결하려면 장비의 MQTT 활성화·broker 주소/포트·토픽과 경로를 확인한 뒤 다시 시험해야 합니다.

## 후속 범위

한국어 IME·키보드 배열 선택, 물리 키보드·휠·멀티터치, 오디오, 자동 재연결, 최초 설정·펌웨어 설정 UI, 물리 확장 응답 감지 지원, 암호화 설정 백업·복원, Tailscale OAuth 인증, 물리 키보드와 화면 구성의 추가 개선.

## 근거 및 의존성

- [JetKVM 웹 API와 인증](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/web.go)
- [공식 클라이언트 시그널링](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/ui/src/webrtc/signaling/useWebSocketSignaling.ts)
- [JSON-RPC와 확장·전원 제어](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/jsonrpc.go)
- [HID wire format](https://github.com/jetkvm/kvm/blob/8f6c3001b1064eb32b3f66afd04efbe536bbaf87/internal/hidrpc/message.go)
- [WebRTC Android SDK](https://github.com/webrtc-sdk/android) · [OkHttp](https://square.github.io/okhttp/)
- [Tailscale API](https://tailscale.com/api) · [API 인증](https://tailscale.com/docs/reference/tailscale-api)



## GitHub 소스 관리

저장소: [colorwebjwkim/jetkvm-android-control](https://github.com/colorwebjwkim/jetkvm-android-control).

소스·테스트·Gradle Wrapper·프로젝트 내부 브랜드 자산을 관리합니다. `local.properties`, IDE 설정, 빌드 결과, APK, 서명 키·자격 증명, `verification/`의 로컬 검증 로그·화면·기기 정보는 `.gitignore`로 제외합니다. 이 문서의 `verification/` 경로는 개발 환경에서 생성한 증거를 가리키며 저장소에 포함하지 않습니다. 검증은 위 빌드·instrumentation 명령으로 재실행할 수 있습니다.
