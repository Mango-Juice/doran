# 도란 차량 앱

Pleos Connect에서 음성으로 대화하고 개인 계정에 기록을 남기는 Kotlin 앱입니다. Android 휴대전화용 앱은 `../mobile`에 있습니다.

## 빌드

JDK 21과 Android SDK 35를 사용합니다. `local.properties.example`을 `local.properties`로 복사하고 SDK 위치를 지정합니다. Supabase URL과 publishable key는 공개 클라이언트 설정이며 인증 토큰이나 서버 비밀키가 아닙니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleRelease
```

release 서명은 환경 변수, Gradle 속성 또는 저장소 밖 `~/.config/doran/release-signing.properties`에서 아래 이름으로 전달합니다. 값과 키 파일은 커밋하지 않습니다.

```properties
DORAN_KEYSTORE_PATH=/secure/path/doran-release.jks
DORAN_KEYSTORE_PASSWORD=local-only
DORAN_KEY_ALIAS=doran
DORAN_KEY_PASSWORD=local-only
```

서명 설정이 없으면 release 빌드는 unsigned APK를 생성합니다. 제출 전 `apksigner verify`를 사용해 서명을 확인해야 합니다.

debug 패키지는 `app.doran.car.debug`로 분리되어 release 설치와 차량 연결을 덮어쓰지 않습니다. SDK 계측 시험은 `androidTest` APK에만 포함되며 release APK에는 포함되지 않습니다. Gradle 의존성은 `app/gradle.lockfile`로 고정합니다.

## 사용

1. 주차 상태에서 차량 앱을 엽니다.
2. 모바일 앱에서 Google 로그인과 데이터 처리 동의를 완료합니다.
3. 모바일 설정의 차량 연결에 차량 화면의 일회용 코드를 입력합니다.
4. 차량에서 연결할 휴대폰을 확인합니다.
5. 대화를 시작하고 ‘잠시 멈춤’ 또는 ‘여기까지 기록’을 사용합니다. ‘대화 끝내줘’라고 말해 마무리할 수도 있습니다.
6. 저장된 기록과 하루 일기를 모바일에서 확인합니다.

주행 중에는 연결 설정과 대화 원문을 표시하지 않습니다. `지금 혼자 대화해요`는 정차 상태에서 직접 선택하며, 좌석 점유가 확인되면 이 선택보다 센서 결과를 우선합니다. 앱을 벗어나면 선택과 음성 대기를 해제합니다.

## 구성

- `domain`: 대화 화면 상태, 비동기 요청 세대 관리, 종료 문구 판정
- `data`: Supabase 차량 익명 인증, 토큰 갱신, API/SSE, Keystore 암호화, Room 발화 대기열
- `speech`: Pleos ON_DEVICE STT/TTS와 assistant 오디오 포커스
- `vehicle`: AAOS 주행 제한 및 동의 기반 Pleos 차량 정보
- `ui`: 차량용 Compose 화면과 디자인 토큰

음성 원본과 인식 텍스트를 로그에 남기지 않습니다. 연결이 끊겨 서버에 보내지 못한 발화는 암호화해 저장하고, 같은 요청 식별자로 재시도합니다. 미완료 발화는 대화 종료 요청에도 포함해 AI 응답 실패와 기록 저장을 분리합니다.

실제 플랫폼 지원 범위와 검증 상태는 저장소의 `docs/car.md` 및 `docs/car-sdk.md`를 확인하세요.
