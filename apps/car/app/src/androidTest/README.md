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
