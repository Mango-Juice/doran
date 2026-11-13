# 도란 모바일

차량에서 나눈 대화를 개별 기록과 하루 일기로 읽고 다듬는 Android·iOS 앱입니다. Google 계정으로 로그인한 뒤 차량에 표시된 일회용 코드를 입력합니다. 차량에서도 연결을 확인해야 완료됩니다.

## 실행

Flutter 3.50.0 stable, Dart 3.14, JDK 21 이상을 사용합니다. 의존성은 `pubspec.lock`에 고정합니다.

```sh
flutter pub get
flutter analyze
flutter test
flutter run --dart-define=SUPABASE_URL=https://YOUR_PROJECT.supabase.co \
  --dart-define=SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

공개 가능한 Supabase URL과 publishable key의 기본값이 포함되어 있습니다. OpenAI 비밀키·Supabase 서버 비밀키는 이 앱에서 사용하지 않습니다. 실제 로그인에는 서버의 Google OAuth 설정이 필요합니다. 설정이 없는 경우 로그인 오류를 안내하며 체험 데이터를 로그인 결과로 대신하지 않습니다.

Supabase redirect allowlist: `app.doran.mobile://auth-callback`. 시스템 브라우저 PKCE로 로그인하며 세션과 코드 검증기는 Keychain/Android Keystore 기반 저장소에 보관합니다.

Google 로그인 버튼은 [공식 브랜딩 지침](https://developers.google.com/identity/branding-guidelines)의 표준 G 아이콘, 밝은 배경, Google Sans Medium과 한국어 안내를 사용합니다. 자산 원본·해시·글꼴 라이선스는 [Google 자산 안내](assets/google/README.md)에 기록했습니다. 글꼴·아이콘을 실행 중 외부에서 내려받지 않습니다.

## 빌드

```sh
DORAN_SIGNING_PROPERTIES=/absolute/private/release-signing.properties \
  DORAN_DEBUG_SYMBOLS=/absolute/private/mobile-debug-symbols/VERSION \
  FLUTTER_BIN=/absolute/flutter/bin/flutter scripts/build-android-release.sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer FLUTTER_BIN=/absolute/flutter/bin/flutter scripts/build-ios.sh
```

서명 설정 파일은 `DORAN_KEYSTORE_PATH`, `DORAN_KEYSTORE_PASSWORD`, `DORAN_KEY_ALIAS`, `DORAN_KEY_PASSWORD` 항목을 포함합니다. 파일은 저장소 밖에 보관합니다. 설정이 없을 때 debug 키로 release를 대체하지 않습니다.

제출용 Android 빌드 스크립트는 Git의 공개 대상 소스를 `/tmp`의 중립 경로에 복사하고 난독화하며, 디버그 심볼을 저장소 밖에 보관합니다. Flutter의 생성 플러그인 URI는 난독화만으로 지워지지 않으므로 중립 경로를 사용합니다. 오류를 추적할 때 해당 APK 버전의 심볼이 필요하므로 APK와 심볼을 함께 보존하세요. 출력은 원래 프로젝트의 `build/app/outputs/flutter-apk/app-release.apk`이며 배포 전 APK 내부의 개인 경로·비밀값을 다시 검사합니다.

iOS 빌드 스크립트는 Dart native asset hook이 `DEVELOPER_DIR`를 전달하지 않는 환경에서도 선택한 Xcode를 사용하도록 프로젝트 캐시에 `xcrun` 래퍼를 만듭니다. 시스템 Xcode 선택을 변경하지 않습니다. Debug·Profile·Release 설정은 Keychain 저장소용 `Runner.entitlements`를 공통으로 사용합니다.

## 기록 관리

- 오늘과 전체 기록을 조회하며 이력은 페이지로 계속 불러올 수 있습니다.
- 개별 기록의 본문·날짜·개인화 제외 여부를 수정합니다.
- 직접 수정한 하루 일기는 자동 갱신으로 덮어쓰지 않습니다. 새 AI 일기와 비교하고 명시적으로 반영합니다.
- 개별 기록 삭제 시 하루 일기의 직접 수정본까지 삭제할지 별도로 선택합니다.
- 하루 전체 삭제는 해당 날짜의 원문·기록·일기·파생 기억을 모두 삭제하는 동작입니다.
- 네트워크가 끊겨도 해당 계정의 암호화된 캐시를 읽을 수 있습니다. 작성 중인 글은 기기에 임시 보관하며, 서버 저장 성공 전 완료로 표시하지 않습니다.
- 수정 충돌은 사용자 확인을 거칩니다. 다른 기기의 개인화 제외 설정은 본문 덮어쓰기 과정에서 몰래 변경하지 않습니다.
- 로그아웃·계정 삭제 시 해당 계정의 로컬 기록과 초안을 지웁니다.

앱 표시 중에는 서버 변경 이벤트, 화면 복귀, 당겨서 새로고침으로 기록을 갱신합니다. 변경 이벤트가 누락된 경우를 위해 전면 화면에서만 30초 간격으로 다시 조회합니다.

## 검증 범위

단위·위젯 시험 범위는 저장소의 `docs/mobile-test-plan.md`를 참조하세요. 합성 HTTP 응답으로 수행하는 위젯 시험은 실제 Google 로그인·클라우드·기기 간 동기화 성공을 뜻하지 않습니다.
