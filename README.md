# 도란

차량에서 나눈 이야기를 개인 기록으로 이어가는 음성 대화 앱입니다.

## 사용 흐름

주차 상태에서 차량 앱을 열고 모바일에서 Google 계정으로 로그인합니다. 휴대폰에 차량의 일회용 코드를 입력한 뒤 차량에서도 연결을 확인합니다. 차량에서 대화하고 마무리하면 개별 기록과 하루 일기를 모바일에서 확인할 수 있습니다.

직접 고친 하루 일기는 새 AI 결과가 생겨도 유지됩니다. 모바일에서 두 글을 비교하고 반영 여부를 선택합니다. 기록의 개인화 사용 제외, 삭제, 차량 연결 해제도 모바일에서 관리합니다.

## 구성

- `apps/car`: Kotlin·Compose 기반 Pleos Connect 차량 앱
- `apps/mobile`: Flutter 기반 Android·iOS 기록 앱
- `supabase`: 인증된 API, PostgreSQL 데이터 정책, AI 처리 작업
- `contracts/api.md`: 앱과 서버가 공유하는 API 계약

대화는 인증된 서버의 Responses API `gpt-6.1-terra`로 처리하고, 기록·일기·기억 생성은 Supabase Queues의 작업으로 분리합니다. 요청 식별자, 데이터 버전, 사용자 소유권을 확인해 재시도와 늦은 응답이 다른 기록을 덮어쓰지 않도록 합니다.

## 구현 및 검증 상태

현재 통합 검증 중이며 제출 완료본은 아닙니다. Android 차량·모바일 release APK와 iOS 시뮬레이터 빌드를 생성했고 Google OAuth 및 서버 OpenAI 키를 설정했습니다. Android 실제 Google 로그인과 모바일·차량 양쪽 승인 연결을 완료했습니다. 차량 `1271d0d1…`의 깨끗한 설치와 연결 완료 후 대화 시작 전 화면의 가상 주행·분할 대응도 확인했습니다. iOS도 첫 인증 오류 후 재로그인해 같은 차량을 확인했습니다. 첫 사람 발화 4턴은 서버 응답과 종료 후 기록·일기 생성까지 진행했으나 긴 지연·잡음·TTS 재생 실패가 발생했습니다. 정상 TTS 완료 처리를 수정한 새 차량 APK의 재시험 3주기에서는 TTS 완료 6회·실패 0회였지만 잡음·지연은 남아 있습니다. iOS에서는 기록·일기 본문과 편집 화면까지 열어 봤습니다. 수정 저장·보호·비교·삭제, 20턴 연속 대화와 최종 녹화는 미완료입니다.

로그인 런처 수정을 포함한 `183dd4c`는 CI와 65개 모바일 시험, 두 플랫폼 재빌드를 통과했습니다. 새 Android APK `d4dba0f6…`의 깨끗한 설치·재로그인에 이어, 로그아웃·같은 계정 SSO와 연결 해제 후 차량 재실행·재연결을 확인했습니다. 동의하지 않은 상태의 시작 차단을 확인한 뒤 처리 동의를 켜고 위 예비 음성 시험을 진행했습니다. 현재 차량 소스 `4cbe3aa`는 CI·49개 JVM 시험을 통과했고 APK는 `b73fbd34…`입니다. 제한된 재시험 결과를 전체 음질이나 20턴 성공으로 확대하지 않습니다.

[검증 기록](docs/verification.md)은 단위·계약 시험, 실제 클라우드 호출, SDK 실행, 사람 발화를 구분합니다. APK 파일명·영상 구성·버전 고정 절차는 [제출 안내](docs/submission.md)를 따릅니다. 미확인 동작은 완료로 표시하지 않습니다.

실제 설치 앱 화면: [Google 계정 연결을 마친 차량](docs/images/car-google-connected-idle.png) · [모바일에서 차량 승인 대기](docs/images/android-pairing-await-confirmation.png) · [새 Android 오늘 탭](docs/images/android-d4db-empty-today.png) · [새 Android의 연결 차량](docs/images/android-d4db-linked-vehicle.png) · [iOS 로그인 후 오늘 탭](docs/images/ios-google-authenticated-20261114.png) · [iOS의 연결 차량](docs/images/ios-google-linked-20261114.png). 차량 캡처는 Pleos 에뮬레이터의 가상 차량 상태이며, 연결된 계정과 기기 상태는 서버에서도 확인했습니다. 위 캡처는 로그인·연결 단계에 촬영했습니다.

## 개인정보

차량 음성 인식과 출력에는 Pleos SDK를 사용합니다. 음성 원본을 외부 AI로 전송하지 않습니다. 대화 텍스트와 사용자가 허용한 맥락은 서버와 AI에서 처리합니다. 계정·키·서명 정보, 참가신청서와 팀 내부 자료는 이 저장소에 포함하지 않습니다.

## 개발 환경

차량 시험 기준은 Pleos Connect 2.1.0 / Android 14, ARM64 에뮬레이터의 1920×1080 화면 및 분할 화면입니다. 모바일 iOS 시험은 시뮬레이터 기준입니다. 실차·실도로 또는 App Market 승인에 대한 검증을 의미하지 않습니다.

JDK 21, Android SDK 35(차량), Flutter 3.50.0 / Dart 3.14, Deno 2.5.6을 사용합니다. 정확한 Flutter 커밋은 [toolchain.json](toolchain.json)에 고정합니다. iOS 빌드에는 Xcode와 해당 시뮬레이터 런타임이 필요합니다.

```sh
# 차량: 예시 파일의 sdk.dir을 현재 컴퓨터의 Android SDK 경로로 바꿉니다.
cp apps/car/local.properties.example apps/car/local.properties
(cd apps/car && ./gradlew ktlintCheck testDebugUnitTest lintRelease assembleDebug)

# 모바일: 저장소 루트에서 실행합니다.
(cd apps/mobile && flutter pub get --enforce-lockfile)
(cd apps/mobile && flutter analyze && flutter test)
(cd apps/mobile && flutter build apk --debug)

# 서버: 저장소 루트에서 실행합니다.
deno task --config supabase/deno.json verify
```

명령은 저장소 루트에서 실행합니다. 서명된 release 빌드와 서버 설정은 [개발·운영 설정](docs/setup.md), [차량 빌드](apps/car/README.md), [모바일 빌드](apps/mobile/README.md)에 설명합니다. 비밀키와 서명키는 저장소 밖에 보관합니다.

## 설계와 확인 자료

- [API 계약](contracts/api.md) · [서버와 데이터 보호](docs/backend.md)
- [차량 구현](docs/car.md) · [Pleos SDK 지원 범위](docs/car-sdk.md)
- [모바일 구현](docs/mobile.md) · [모바일 시험 범위](docs/mobile-test-plan.md)
- [개인정보 처리](docs/privacy.md) · [의존성 라이선스](docs/dependencies.md)
- [1차 평가항목과 구현 근거](docs/evaluation.md)
- [실제 발화 20턴과 영상 리허설](docs/demo-rehearsal.md)

## 공개 범위

공모전 평가와 개발 내용 확인을 위한 소스 공개입니다. 자체 코드에 별도 오픈소스 이용허락을 부여하지 않으며, 의존하는 제3자 소프트웨어의 이용 조건은 각 라이선스를 따릅니다.
