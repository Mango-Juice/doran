# 차량 앱 구현과 검증

## 구현 범위

Kotlin·Jetpack Compose 차량 앱은 실서버 설정을 사용하며, AI나 SDK 응답을 고정 문장으로 대체하지 않는다. Supabase 익명 인증은 차량 기기 식별용이고, 모바일 Google 계정은 5분 유효 연결 코드를 통해 별도로 결합한다. 소유자가 변경되면 이전 대화 ID, 인증 범위 내 임시 기록, 로컬 장소 기억을 제거한다.

진행 중인 연결 요청의 id·코드·만료 시각은 bootstrap 기기 id·소유자와 함께 Keystore로 암호화해 보관한다. 앱을 다시 열면 서버의 최신 상태와 만료를 확인한 뒤 남은 시간 동안 같은 코드를 복원하며, 휴대폰이 이미 승인한 요청도 차량에서 이어 확인할 수 있다. 서버와 연결하지 못하면 캐시는 보존하되 코드를 표시하지 않는다. 만료·완료·연결 해제·계정/기기 변경 시 삭제하고, 새 코드 요청과 백그라운드 전환은 이전 복원·조회 응답을 무효화한다. 복원으로 5분 기한을 연장하지 않는다.

음성은 요청마다 별도 SDK 인스턴스와 리스너를 사용한다. 취소·완료된 요청의 늦은 결과를 새 요청으로 수용하지 않는다. STT·TTS·Audio Focus 자원도 요청 소유자를 확인하고 정리해 빠른 중지·재개 때 이전 정리가 새 요청을 끊지 않게 한다. 녹음 파일은 생성하지 않는다.

마이크 요청 전에는 준비 상태를 표시하고, 현재 요청의 `onReady` 또는 `onStartedRecognition`을 수신한 뒤에만 ‘듣고 있어요’로 전환한다. 중복 준비 이벤트는 한 번만 처리하며 취소·완료된 요청의 이벤트는 화면을 바꾸지 않는다. 준비 중에도 일시정지와, 세션이 이미 있으면 기록 종료를 사용할 수 있다. 콜백의 준비·시작 의미는 [공식 ResultListener 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)를 따른다. 실제 마이크의 첫 음절 보존은 별도 사람 발화 시험 대상이다.

플랫폼 제한을 확인하기 전에는 연결 설정과 대화를 시작하지 않는다. DO 선언은 주행 화면의 최소 제어를 위한 것이며 `CarUxRestrictions`를 무시하지 않는다. 주행 중 원문·코드·설정은 숨긴다. 완전히 백그라운드로 전환하면 음성을 중단하고 차량 구독을 해제한다.

## UI

짙은 중성 배경, 따뜻한 강조색, 한국어 시스템 서체와 80dp 이상 기본 조작부를 사용한다. 정차한 넓은 화면에서는 현재 대화를 별도 영역에 보여 주고, 좁은 화면에서는 주요 조작을 유지하면서 표시량을 줄인다. 반복 장식 애니메이션은 사용하지 않는다.

간격·색·정보 위계 검토에는 Damir Sokolovsky의 [Unslop](https://heydamir.design/unslop) 지침을 참고했다. 해당 지침은 CC BY 4.0이다. UI 코드는 이 프로젝트에서 작성했다.

## 검증 구분

빌드와 단위 시험은 실제 SDK 실행이나 마이크 입력의 증거가 아니다. 배포 APK에서 별도로 확인할 항목은 다음과 같다.

- Pleos Connect 2.1.0 / AAOS 14, 1920×1080 및 분할 화면 설치·실행
- 실제 차량 연결, SDK 음성 입력·출력, 대화 종료 후 모바일 기록 조회
- 주차→주행→주차, 통화·내비 포커스, 앱 전환, 권한 거부와 복귀
- 네트워크 단절, 중단 후 재개, 요청 중복, 계정 연결 해제
- 실제 사람의 발화와 합성 입력을 분리한 연속 턴 시험

위 체크 항목은 검증 범위이며, 항목 자체는 통과 증거가 아니다. 실제 결과는 아래와 별도의 SDK 문서에서 확인한다.

## 2026-11-14 release 빌드 검증

- 버전: `1.0.0` / versionCode `1`, 패키지 `app.doran.car`
- 산출물: `apps/car/app/build/outputs/apk/release/app-release.apk`, 17,220,506 bytes
- SHA-256: `b73fbd34a10735e428b57c93d15f1674cacf4b73302360934b23f13cfb49a386`, 소스 `4cbe3aa12d66256487eccc1238faf7cc12d61be1`
- `ktlintCheck`, JVM 단위 시험 49개, `lintRelease`, release 빌드와 해당 커밋의 CI가 통과했다. 시험에는 요청 세대·음성 종료 문구, 계정/세션 작업 소유권, 개별 UX 제한, 차량 정보 유효성, 반복 방문·도착 기준값 정책과 Navi 상태 회귀 8개가 포함된다. Navi 회귀는 기록한 실제 콜백의 재생 및 정책 경계 시험이며 실제 목적지 도착 검증이 아니다.
- APK v2 서명을 검증했다. release는 디버그 가능하지 않으며, `assets/licenses.txt`는 배포 고지 원문과 일치한다.
- `SdkVerificationActivity`, `SDKSmokeTest`와 합성 PCM 시험 자료는 release APK에 포함되지 않는다. 계측 시험용 debug는 `app.doran.car.debug`로 설치해 release의 차량 연결 상태를 보존한다.
- Gradle 의존성 잠금 파일과 배포판 SHA-256을 고정했다. formatter 실행과 검사·빌드는 순차 수행한다.

주행 여부 외에도 `NO_SETUP`, `LIMIT_CONTENT`, `LIMIT_STRING_LENGTH`를 별도로 검사한다. 주차 상태에서도 설정 제한은 코드·설정을 숨기고, 내용·문자열 제한은 긴 대화문까지 숨긴다. `DoranStartup` 로그는 모델 준비와 첫 내용 draw의 소요 시간만 기록하며 사용자 대화·계정 정보를 기록하지 않는다. 실제 화면에 나타난 시점은 OEM 합성과 전환 시간을 포함하므로 이 내부 시점과 별도로 측정한다.

정상 TTS 완료 뒤 비동기 stop이 다음 요청의 콜백에 영향을 줄 수 있는 경로를 확인해, 정상 완료에서만 불필요한 stop을 생략했다. 리스너 해제·SDK release 및 실패·취소의 stop은 유지한다. 새 APK의 사람 재시험 3주기에서 TTS 완료 6회·실패 0회를 관측했지만 사용자 잡음·지연은 해결되지 않았고 20턴 시험도 통과하지 않았다. 별도 scrcpy 출력 경로도 개선 증거를 얻지 못했다. 이전 `1271d0d1`의 실패·저장 관측과 [후속 재시험](verification/first-human-trial-20261114.json)을 구분한다.

## 재실행 복원의 실제 검증

[B4 release의 복원 결과](../apps/car/verification/pairing-b4fdac87.json)는 앱 프로세스를 완전히 종료한 뒤 새 프로세스에서 같은 연결 코드를 복원한 관측이다. 서버에서도 같은 challenge id·생성/만료 시각·waiting 상태를 확인했고 새 행은 생성되지 않았다. 명시적 새 코드 버튼은 별도로 신규 요청을 한 개 생성했다. 과거 요청의 expired 상태는 원래 만료 시각 이후에 조회했으므로 발급 즉시의 상태를 직접 관측했다고 해석하지 않는다.

이 B4 시험은 모바일 계정이 아직 승인하지 않은 요청의 복원이며 실제 Google 로그인과 양쪽 승인은 포함하지 않는다. 이후 `1271d0d1`에서 확인한 실제 계정 연결은 [별도 관측](verification/car-account-20261114.json)으로 구분한다. B4는 위 최신 APK의 마이크 준비·좌석 신뢰 정책 보완 이전 빌드이며, 해당 정책과 최신 APK의 설치 검증을 대체하지 않는다.

## 깨끗한 설치와 OEM 창 크기 관측

Navi 상태 수정 전 `cdfc0e8c` release는 서버에서 미연결·사용자 기록 없음이 확인된 시험 기기에서 차량 앱 패키지만 제거한 뒤 다시 설치했다. 기존 AVD 데이터와 다른 앱은 유지했다. 실제 Park→Drive 20km/h→Park 전환에서 코드·설정 숨김과 정차 후 복귀를 확인했다. 이 결과는 해당 해시의 관측이며 후속 APK의 설치 결과와 구분한다.

물리 디스플레이는 1920×1080이고 처음 관측된 도란 앱 영역은 1256×850이었다. 실제 화면의 OEM 윈도 바를 한 번 드래그한 뒤 앱 영역이 628×850으로 바뀐 것을 task bounds와 화면에서 확인했다. 연결 화면의 제목·코드·버튼·안내 문장은 두 영역에서 잘리지 않았다. 드래그 방향을 공식 제스처 계약으로 일반화하지 않으며, 이미지 크기 변경이나 물리 DPI 조작으로 화면을 흉내 내지 않았다. 이후 `1271d0d1`에서 연결 완료 후 대화 시작 전 화면의 분할 대응도 확인했다. 대화 실행 중 두 영역 대응은 아직 별도 확인해야 한다.

원본 화면·task bounds·설치 결과는 로컬 `apps/car/app/build/verification/cdfc0e8c-clean-install/`에 보존한다. 실제 내비 callback 관측과 이후 JVM 회귀는 [SDK 검증 문서](car-sdk.md)에 별도로 기록한다.

Navi 수정 뒤 `d81d030d` release도 미연결·사용자 기록 없음이 확인된 시험 기기에서 깨끗하게 설치했다. 실제 연결 첫 화면, 설치 해시 일치와 앱 생존을 확인했으며 시작부터 내용 관측까지 약 4.61초였다. 이 값에는 화면 폴링 시간이 포함된다. 원본은 로컬 `app/build/verification/d81d030d-clean-install/`에 보존한다. `1271d0d1`의 최초 계측은 기본 저장소·키 이름을 유지하는 주입점 추가 후 **업데이트 설치**로 수행했다. 이후 같은 APK를 깨끗하게 설치해 해시 일치를 확인했으며, 후속 CUA에서 실제 Android Google 계정과 모바일·차량 양쪽 승인을 완료했다. [계정 연결·차량 화면 관측](verification/car-account-20261114.json)에 두 단계를 구분해 기록한다. 연결 완료 후 대화 시작 전 상태에서 가상 Drive 20km/h→Park의 UI 제한·복원, 실제 HMI 바 조작으로 628×850 분할과 기본 레이아웃 복귀를 확인했다. 마이크·대화 시작·AI 요청은 없었다.

## 로그인 없이 수행한 추가 계측

단독 Pleos Connect 2.1.0·RAM 4096MB·CPU 2개에서 세 가지 계측을 수행했다. release는 `1271d0d1`, 별도 debug 앱은 `0bfdc9c7` 해시의 APK를 사용했다. 원본·APK 사본은 로컬 `app/build/verification/1271d0d1-focus-outbox-feedback/` 및 `1271d0d1-feedback-retry/`에 보존한다.

- **Room·Keystore:** UUID로 분리한 시험용 DB·설정·키에서 실제 AES-GCM 암호화, 같은 request의 중복 저장 방지, DB/키 핸들 재열기, 특정 세션만 삭제하는 영속성 및 다른 시험 키의 보존을 확인했다. 실제 프로세스 종료·서버 재전송 시험은 아니다. 제품의 기본 `doran_secure`·`doran.v1` 값은 유지한다.
- **오디오 포커스:** 서로 다른 listener를 사용하는 같은 UID의 실제 요청으로 확인했다. NAVIGATION 요청은 플랫폼에서 거절됐고 대화 TTS는 중단 없이 끝났다. VOICE_COMMUNICATION 요청은 허용됐으며 production `PleosSpeech`의 중단 콜백 1회와 취소, 로컬 자원 해제, 정확히 식별한 현재 AAOS 포커스 항목 1→0을 관측했다. 이후 새 TTS 중 이전 listener의 `onDone`을 합성으로 한 번 전달했으나 수락되지 않았고, 새 SDK의 실제 완료만 수락됐다. 실제 통화·내비 음성 재생·사람의 청취·마이크 검증은 아니다.
- **조작 피드백:** 실제 release 연결 화면에 합성 탭을 한 번 주입했다. 수동으로 확인한 첫 일치 프레임은 연결 확인 문구와 회색 비활성 표시를 함께 담았고, 캡처 반환 시각은 DOWN 이후 185ms, UP 이후 100ms였다. 이번 단일 guest 화면 관측의 상한이 250ms 목표 이내였다는 의미이며, 물리 디스플레이 표시 시각이나 일반적인 백분위 성능을 보장하지 않는다.

처음 Feedback 실행은 하네스의 APK 해시 조회에서 중단돼 탭 0회·프레임 0개였다. 해당 실패를 보존하고 명령 인용부호만 수정한 뒤 Feedback만 한 번 다시 수행했다. 앞의 두 계측은 instrumentation `ca95abc1` APK, 성공한 Feedback은 `8498741f` APK로 실행했다. 생산 APK는 동일하다. 테스트·디버그 패키지를 제거하고 에뮬레이터를 정상 종료했으며 release 데이터는 보존했다.

NAVIGATION 거절은 [Pleos의 오디오 포커스 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)에서 현재 VOICE_COMMAND에 대한 incoming NAVIGATION 상호작용과 일치한다. 이를 내비 안내에 성공적으로 양보한 결과라고 해석하지 않는다. 앱의 사용 목적에 맞는 `USAGE_ASSISTANT` 속성을 유지하며 정책을 우회하지 않는다.

## 플랫폼 UX 제한과 화면 대응

[Android CarUxRestrictions 공식 정의](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)에 따라 DO 필요 여부와 아래 flag를 함께 사용한다. 플랫폼 상태를 알 수 없으면 연결·설정·본문을 숨기고 음성 대화를 시작하지 않는다.

| 플랫폼 flag | 차량 앱의 대응 |
|---|---|
| `NO_DIALPAD` | 전화 다이얼 입력 UI가 없다. 연결 코드는 표시만 하며 입력은 모바일에서 한다. |
| `NO_FILTERING` | 목록 필터·검색 UI가 없다. |
| `NO_KEYBOARD` | 텍스트 입력 UI가 없다. 기록 편집과 코드 입력은 모바일에서 한다. |
| `NO_VIDEO` | 영상과 반복 상태 애니메이션을 제공하지 않는다. 준비·저장 상태는 즉시 바뀌는 짧은 문장으로 표시한다. |
| `NO_SETUP` | 연결 코드·연결 확인·혼자 대화 선택·위치 권한 요청을 숨기고 실행 경로에서도 차단한다. |
| `LIMIT_CONTENT` | 대화 원문·AI 본문 및 연결·설정을 숨긴다. 기록 목록·스크롤 탐색을 제공하지 않는다. |
| `LIMIT_STRING_LENGTH` | 본문·연결·설정을 숨기고, 남는 상태·버튼 문구에도 플랫폼의 `maxRestrictedStringLength`를 적용한다. |
| `NO_TEXT_MESSAGE` | 대화 원문·AI 본문을 숨긴다. 연결·설정도 보수적으로 숨긴다. |
| `NO_VOICE_TRANSCRIPTION` | 실시간·완료된 음성 인식 문구와 AI 본문을 숨긴다. 음성 입력·출력 자체는 이 표시 제한으로 중단하지 않는다. |

차량 안에는 라이선스 긴 본문 뷰어가 없다. 라이선스는 APK의 `assets/licenses.txt`와 저장소 문서로 제공한다. 모든 차량 화면 텍스트는 `CarText`를 거쳐 길이 제한을 적용하며, 원문·AI 본문·연결 코드의 숨김은 Compose와 접근성 트리 모두에 반영한다. 위 개별 flag 경계는 JVM 정책 시험으로 확인하고, 에뮬레이터에서 실제로 발생한 주행 flag 검증과 구분한다.

## 실제 음성 지연 측정

앱의 `DoranSpeech` 로그는 요청 번호·단계·요청 이후 경과 시간만 포함한다. STT의 `listen_requested`→`sdk_initialized`→`microphone_request_dispatched`→`callback_ready/started/ended/final`, TTS의 `speak_requested`→`sdk_initialized`→`preroll_started/done`→`sdk_speak_dispatched`→`callback_started/done`을 구분한다. SDK가 제공하지 않는 콜백은 관측되지 않은 것으로 남긴다. `callback_final/done`과 `accepted_final/done`의 차이는 SDK 콜백을 Main에서 처리할 때의 대기 시간을 보여 준다. 늦은 콜백은 기록되어도 현재 요청으로 채택하지 않는다.

사람 발화를 시험할 때는 아래처럼 앱의 비식별 계측 태그만 수집한다. SDK 자체의 전체 로그는 발화 원문을 포함할 수 있으므로 공개 산출물에 넣지 않는다.

```sh
adb -s emulator-5554 logcat -v threadtime DoranSpeech:I DoranStartup:I '*:S'
```

이 계측은 실제 스피커 음량·첫 음절·발화 길이·음질을 증명하지 않는다. 해당 항목은 실제 청취와 별도로 확인한다.
