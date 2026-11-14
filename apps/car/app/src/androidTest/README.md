# Pleos SDK 실행 증거

`SDKSmokeTest`는 `.debug` 패키지의 전용 foreground Activity에서 실행한다. 프로덕션 Activity·계정 연결·외부 AI·마이크 입력을 시작하지 않는다. release APK에는 이 Activity와 테스트 코드가 포함되지 않는다.

## 준비

1. 공식 Pleos AVD를 부팅하고 Android의 현재 사용자를 확인한다. 현재 계정과 연결된 debug 앱에서는 실행하지 않는다.
2. 공식 Virtual Console의 `/browser/session` 1초 heartbeat를 유지해 DDS bridge를 활성화한다. `scripts/verify_vehicle.py`와 같은 설정을 사용한다.
3. 가상 차량을 Park·0 km/h로 두고, `adb emu geo fix 127.045 37.285`로 에뮬레이터 위치를 공급한다. 이 좌표는 시험용 입력이며 실제 위치가 아니다.
4. 처음에는 위치 권한이 없는 깨끗한 debug 앱에서 실행한다. 이미 허용되어 있으면 권한 거부 단계는 `NOT_RUN_ALREADY_GRANTED`로 기록된다. 실행 중 자기 프로세스 권한을 강제 철회하지 않는다.
5. 합성 PCM은 `assets/sdk_voice/THANKS-190.pcm`, `SORRY-190.pcm`에 로컬 배치한다. 16kHz·signed16LE·mono 형식이다. 현재 로컬 파일은 기존 PoC에서 macOS Yuna로 생성한 합성 발화이며 사람의 음성 파일이 아니다. PCM은 Git에서 제외한다. 누락되면 STT는 `SKIPPED_FIXTURE_MISSING`으로 기록하고 TTS는 실행한다.

## 빌드와 실행

Gradle 작업은 다른 Android 빌드와 동시에 실행하지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

생성되는 APK:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

두 APK를 해당 에뮬레이터에 설치한 후 아래 명령을 실행한다. `10`은 예시이므로 `adb shell am get-current-user`가 반환한 실제 사용자로 바꾼다.

```sh
adb -s emulator-5554 shell am instrument --user 10 -w -r \
  -e class app.doran.car.SDKSmokeTest -e speech_rounds 1 \
  app.doran.car.debug.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5554 exec-out run-as app.doran.car.debug --user 10 \
  cat files/sdk-verification.json
```

첫 1회 시험으로 실제 연결·콜백·오류를 확인한 뒤 필요할 때 `speech_rounds`를 최대 20으로 늘린다. 테스트는 기본 1회다. synthetic STT는 `sendAudio()`만 사용하며 마이크용 `request()`를 호출하지 않는다. TTS는 앱의 실제 `PleosSpeech.speak()`를 호출한다.

## 결과 해석

- `harness_completed`는 증거 수집과 자원 정리를 완료했다는 뜻이다. 모든 SDK 기능이 성공했다는 뜻이 아니다.
- 권한 거부 → 권한 허용 후 상태를 각각 기록한다. `AVAILABLE`, `UNSUPPORTED`, `UNAVAILABLE`, `WAITING`, `PERMISSION_REQUIRED`를 합치지 않는다.
- Vehicle capability의 access type, 각 GET 콜백 결과·횟수, 실제 읽은 값과 단위를 기록한다. wrapper의 `state_observations`는 StateFlow 관측 횟수이며 원시 SDK 콜백 횟수와 같다고 주장하지 않는다.
- Navi 연결 성공, 활성 경로 관측, 도착 이벤트 관측을 구별한다. 활성 경로·실제 도착이 없으면 도착 시험 성공으로 표시하지 않는다.
- `FINAL_RECEIVED`와 `expected_match`가 모두 성립해야 합성 발화 인식 성공이다. `SDK_ON_DONE_RECEIVED`는 실제 TTS SDK 완료 콜백을 확인한 결과다. 물리 스피커의 청취·녹음 검증은 별도다.
- 사람의 마이크 발화, 클라우드 AI 응답, 실제 도로·실차 검증은 이 시험의 범위 밖이다.

## 사람 입력 없는 복구·수명주기 시험

아래 시험은 `apps/car`에서 별도로 실행한다. 기존 계정이 연결된 release 앱은 삭제하거나 데이터 초기화하지 않는다. 호스트에서 다른 VM과 무거운 빌드를 함께 실행하지 않는다. 음성 SDK 계측은 호스트 출력 음소거를 직접 확인한 경우에만 실행하며, 마이크가 필요하지 않은 부팅에서는 `-allow-host-audio`를 사용하지 않는다.

| 시험 | 실제로 사용하는 경로 | 합성 또는 제외한 경로 |
| --- | --- | --- |
| `ConversationLifecycleTest` 기본 7개 | Activity 배경 전환, 실제 ViewModel, Room·Keystore, 오류·재개·개인정보 노출 제어 | SpeechEngine과 API는 debug 전용 대역. 실제 음성·네트워크·서버 검증 아님 |
| 별도 Drive/Park probe | Virtual Console 신호, 실제 CarUx, Activity·화면 상태 | 대화 입력과 서버 응답은 합성. 실도로 주행 아님 |
| `SocketReplayTest` | 실제 DoranApi, TCP 연결 종료·재연결, 서로 다른 Android 프로세스, 원본 암호문 복원 | 서버는 로컬 SQLite·SSE fixture. 실제 Supabase의 멱등성 증거 아님 |
| TTS 취소·포커스 probe | Pleos TTS SDK, AudioManager 포커스, stop 확인 순서 | 고정 시험 문장. 실제 통화·내비 음성·사람 청취·음질 검증 아님 |

기본 수명주기 시험은 새 UUID별 저장소를 생성하고 자기 DB·설정·키만 정리한다. 자동 `onStop` 이후 늦은 콜백 차단, 보류 중인 서버 pause와 재개 순서, TTS/SSE 오류의 outbox 보존, 이전 종료 정보 복구, 과거 응답의 재노출 방지를 검사한다. 현재의 새 발화에 대한 응답은 정상 허용되는지도 확인한다.

```sh
adb -s emulator-5554 shell am instrument --user 10 -w -r \
  -e class app.doran.car.ConversationLifecycleTest \
  -e run_synthetic_lifecycle true \
  app.doran.car.debug.test/androidx.test.runner.AndroidJUnitRunner
```

별도 probe는 opt-in하지 않으면 건너뛴다. 기본 시험 결과는 `files/synthetic-lifecycle/<실행 UUID>/report.json`이다. Android 현재 사용자 번호를 모든 `am instrument`와 `run-as`에 똑같이 적용한다. JUnit 종료와 `passed`, `cleanup_succeeded`를 함께 확인한다.

### 실제 Drive/Park 제한

`activeSyntheticListeningSurvivesActualDriveAndParkRestrictions` 메서드만 지정하고 `run_active_driving_probe=true`, `driving_probe_run=<새 canonical UUID>`를 전달한다. 기본 시험용 `run_synthetic_lifecycle`은 필요하지 않다.

- `files/active-driving-probe/<UUID>/phase.json`의 `awaiting_drive`를 확인한 뒤 공식 Console에서 D·20 km/h로 전환한다.
- `awaiting_park`를 확인한 뒤 Park·0 km/h로 복원한다.
- 전체 대기는 90초다. `completed_observations`만으로 통과를 선언하지 않고 연결된 최종 report와 JUnit·정리 결과까지 확인한다.
- Drive에서는 합성 LISTENING을 유지하며 긴 본문·설정을 숨기고, Park에서는 해당 제한이 복원되는지 검사한다. 마지막 Park의 상태 관측과 실제 화면 캡처는 구분한다.

### TCP 단절과 프로세스 재시작

새 실행 UUID와 사용하지 않는 포트를 선택한다. 기존 `adb reverse --list` 항목과 호스트 포트를 덮어쓰지 않는다.

```sh
python3 scripts/qa_socket_server.py --run-id <UUID> --port 18991 \
  --database app/build/verification/qa-socket-replay/<UUID>/server.sqlite3

adb -s emulator-5554 reverse tcp:18991 tcp:18991
```

`SocketReplayTest`의 공통 계측 인자는 `run_socket_replay=true`, `qa_run=<같은 UUID>`, `qa_port=18991`, `qa_phase=<아래 단계>`다. 단계별로 하나의 계측만 실행한다.

1. 서버 ON에서 `persist_disconnect`: 서버가 가상 원문을 저장하고 done 없이 TCP 연결을 닫는다. 클라이언트는 실패를 인식하고 outbox를 유지한다.
2. 해당 fixture 서버를 정상 종료하고 **debug 패키지만** `am force-stop --user 10 app.doran.car.debug`한 뒤 `offline_after_restart`: 새 PID에서 같은 암호문을 복원하고 서버 연결 실패를 확인한다.
3. 같은 UUID·SQLite 파일로 서버를 재시작하고 debug 패키지만 다시 force-stop한 뒤 `replay_after_restart`: 같은 요청을 재전송하고 done 1회 후에만 outbox를 삭제한다. fixture 행은 1개, 수신 시도는 2회여야 한다.
4. 증거를 수집한 뒤 `cleanup`으로 해당 UUID의 Android DB·설정·키를 정리하고, 자신이 만든 reverse 항목만 제거한다. 호스트 SQLite·비식별 보고서는 증거로 보존한다.

실제 API 클라이언트의 주입된 시험용 OkHttpClient가 모든 요청을 loopback으로 강제하고 실제 인증 헤더를 전달하지 않는다. debug 네트워크 설정에서만 `127.0.0.1`의 HTTP를 허용한다. release의 HTTPS 정책은 그대로다. 서버의 멱등성·시도 세대 차단은 별도 실제 SQL 시험으로 검증한다.

### 실제 TTS 취소와 포커스

호스트 음소거 확인 후 `AudioFocusInterruptionTest`에서 필요한 메서드 하나만 지정한다.

- `cancelAndImmediatelyStartAfterTheRealStopAcknowledgement`: `run_cancel_probe=true`, `host_output_muted=true`. 이전 요청의 stop 확인 전에 새 SDK 등록이 실제로 대기하고, 확인 후 새 요청의 실제 done까지 완료되는지 검사한다. 합성 콜백 주입은 없다.
- `capturePlatformFocusLossAndRejectStaleDone`: `run_focus_probe=true`, `host_output_muted=true`. NAVIGATION·VOICE_COMMUNICATION 용도의 실제 경쟁 포커스 요청과 해제를 관측한다. 이전 done 콜백 재주입 부분은 합성 시험으로 따로 기록한다.

보고서는 각각 `files/audio-cancel-verification.json`, `files/audio-focus-verification.json`이다. Pleos 정책상 NAVIGATION 요청 거절은 중단 성공이 아니다. SDK stop에는 요청 ID가 없으므로, 관측한 로컬 취소→즉시 재개 순서의 성공을 모든 외부 stop·프로세스 간 경합 해결로 확대하지 않는다. 출력 음소거 상태의 완료 콜백은 실제 청취나 음질의 증거가 아니다.

### 분할 준비 후 합성 대화

이 시험은 대화 중 분할 메뉴를 열어 정상적인 background 중단이 섞이는 것을 피하기 위해, READY 상태에서 실제 OEM 창 크기를 먼저 준비한다. 물리 해상도·DPI를 바꾸지 않는다.

```sh
adb -s emulator-5554 shell am instrument --user 10 -w -r \
  -e class app.doran.car.ConversationLifecycleTest#preparedSplitStartsAndFinishesSyntheticConversation \
  -e run_prepared_split_probe true -e split_probe_run <새 canonical UUID> \
  app.doran.car.debug.test/androidx.test.runner.AndroidJUnitRunner
```

전체 180초 안에 다음 순서로 진행한다. `files/prepared-split-probe/<UUID>/phase.json`과 단계별 JSON을 보존한다. 새 실행에는 새 UUID를 쓰고 이전 디렉터리를 재사용하지 않는다.

1. `awaiting_split_ready`: 실제 QA 창이 READY·1256×850인지 확인한다. 보이는 OEM 바를 조작해 앱 영역을 628×850으로 준비한다. 준비 중 정상적인 `onStop/onStart`는 허용한다. 앱 아이콘으로 별도의 MainActivity를 열거나 대화 시작 버튼을 먼저 누르지 않는다.
2. 같은 디렉터리의 `start.control.tmp`에 정확한 `start <UUID>`를 쓴 뒤 `start.control`로 rename한다. 부분 파일을 먼저 노출하지 않는다. 신호는 1회 소비되며 최대 100초 안에 필요하다.
3. `awaiting_split_finish`: 합성 LISTENING·실제 628×850 영역·중단 없는 lifecycle을 계측한다. 화면에서 고대비 합성 표시, 제목, `잠시 멈춤`과 `여기까지 기록` 조작부를 직접 확인한다. 계측만으로 픽셀 가독성이나 실제 음성 성공을 판정하지 않는다.
4. 관측 후 정확한 `finish <UUID>`를 `finish.control.tmp`에 완성하고 `finish.control`로 rename한다. 이 신호는 LISTENING 관측 뒤 최대 60초 안에 필요하다. 시험은 합성 대화를 종료하고 SAVED·세션 정리 상태를 검사한다.
5. `saved_observed`는 상태 관측이며 해당 UI 프레임의 직접 캡처를 뜻하지 않는다. `completed`, JUnit 성공, 연결된 `files/synthetic-lifecycle/<qa_run_id>/report.json`의 `passed`·`cleanup_succeeded`를 모두 확인한다. 실제 시각 관측의 범위는 별도로 기록한다.

분할 시작 전에는 STARTED 이상·보이는 창·READY·정차 제한·정확한 bounds가 필요하다. 창 포커스나 RESUMED를 강제하지 않아, 전면에서 보이는 분할 앱이라는 제품의 lifecycle 기준을 유지한다.

## 디자인 상태 미리보기

`DesignPreviewActivity`는 debug 소스에만 있는 화면 fixture다. 실제 ViewModel·API·DB·Speech SDK를 만들지 않으며 상단에 합성 화면임을 표시한다. `screen`은 `idle`, `listening`, `paused`, `error`, `pairing`, `saved`, boolean extra는 `driving`, `no_setup`을 지원한다. 앱 영역 크기와 주행 제한 표시는 실제 SDK 동작을 증명하지 않는다.

상태 extra는 `onCreate`에서 읽으므로 시나리오를 바꿀 때는 debug 앱만 종료한 뒤 새로 실행한다. 동일 Activity를 재사용하는 `am start`의 결과를 새로운 fixture 관측으로 기록하지 않는다. release 패키지의 데이터·인증은 건드리지 않는다.

```sh
adb -s emulator-5554 shell am force-stop app.doran.car.debug
adb -s emulator-5554 shell am start -n app.doran.car.debug/app.doran.car.qa.DesignPreviewActivity \
  --es screen listening --ez driving false --ez no_setup false
```
