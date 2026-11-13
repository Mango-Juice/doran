# 도란 모바일 구현

## 구조

`lib/domain`의 기록·하루 일기 모델과 KST 날짜 함수, `lib/data`의 HTTP 계약 및 보안 저장소, `lib/ui`의 화면을 분리했습니다. Riverpod은 인증 상태와 저장소 생명주기를 관리합니다. 사용자가 바뀌면 화면 트리를 새로 만들고 진행 중 요청의 응답은 소유자를 다시 검사합니다.

Flutter 3.50.0부터 deprecated된 `flutter/material.dart` 대신 공식 `material_ui` 패키지를 직접 사용합니다. 밝은 중성 바탕, 짙은 글자, 갈색 강조색을 사용하고 앱 아이콘은 대화에서 기록으로 이어지는 말풍선·세 줄로 구성했습니다. Unslop의 간격·색·타이포그래피 원칙을 참고했습니다 (Damir Sokolovsky, CC BY 4.0, https://heydamir.design/unslop).

## 로그인과 데이터

Google 로그인은 시스템 브라우저 PKCE를 사용합니다. redirect는 `app.doran.mobile://auth-callback`입니다. URL과 publishable key만 앱에 포함하며 비밀키는 서버에 둡니다. 로그인 세션과 PKCE verifier, 계정별 캐시·초안은 Flutter Secure Storage로 보관합니다. 저장소 쓰기는 순서대로 처리해 늦은 초안 저장이 최신 글을 덮어쓰지 않도록 했습니다.

서버 HTTP 401/403 등 접근 거절에는 오프라인 캐시로 대신 성공하지 않습니다. 네트워크 오류일 때만 저장된 기록을 읽고 화면에 오프라인 상태를 표시합니다. 모든 변경은 서버 확인 후 완료로 표시합니다. 기록 충돌 시 최신 서버 본문을 보여주고 명시적으로 덮어쓰도록 합니다. 날짜와 개인화 제외는 사용자가 실제로 바꾼 경우에만 PATCH합니다.

## 실제 환경 확인 필요

Google OAuth 설정과 실제 사용자 계정이 준비되어야 회원 로그인, 차량 승인, 클라우드 기록 생성, Android·iOS 간 동기화를 끝까지 검증할 수 있습니다. 위젯 시험의 성공을 실제 운영 성공으로 기록하지 않습니다. iOS는 시뮬레이터 범위이며 App Store 배포나 실제 iPhone 설치를 포함하지 않습니다.

## 2026-11-14 확인 결과

- Flutter 3.50.0 / Dart 3.14.0, 공식 Flutter commit `ab2912035e160d9cad6306df307576da6128ebdb`.
- 계정·갱신 경계 수정 후 Android release APK 최종 빌드 성공. `app.doran.mobile`, versionName `1.0.0`, versionCode `1`, minSdk 26, targetSdk 36. 별도 보관한 release 키로 APK Signature Scheme v2 검증 통과. 중립 경로에서 난독화 빌드해 개인 홈 경로를 제거했으며 공개 검사에서 APK 발견 항목 없음.
- Xcode 27.0의 iOS 27.0 simulator 대상 최종 소스·Keychain entitlement 빌드 성공. 프로젝트 빌드 스크립트로 Xcode 선택 문제를 해결했고 전역 `xcode-select`는 변경하지 않음. Android에서 iOS로 전환할 때 플러그인 연결을 재생성하도록 기본 pub 단계를 유지함.
- 정적 검사 문제 없음. 전체 49개 및 추가 자동 갱신 3개 단위·위젯 시험 통과. 계정 변경 시 Navigator 경로 폐기와 소유자에 고정된 저장소의 지연 저장 차단, 선택 날짜·이력 페이지 유지, 인증 만료 시 이전 내용 제거를 포함함.
- iPhone 17 Pro simulator의 최초 OS 마이그레이션·앱 기동이 지연됨. 설치와 launch PID 반환은 확인했으나 앱 화면이 실제로 표시된 것을 확인하지 못했으므로 iOS 실행·화면 검증은 완료로 판정하지 않음. 자원 경합을 분리하기 위해 simulator를 종료함.
- Android 설치·실제 화면, 실제 Google 로그인 및 계정 간 동기화는 통합 검증 단계에 남음.
추가 검토에서 계정 변경 시 편집 경로가 남는 문제를 수정했습니다. MaterialApp/Navigator 전체를 계정별로 교체하고, 저장소 인스턴스를 생성 당시 소유자에 고정합니다. 이전 편집기의 비동기 저장이 재개되어도 새 계정 토큰으로 요청하지 않습니다. 자동 갱신은 선택 날짜와 이미 불러온 이력 페이지를 유지합니다. 설정 화면에서 오픈소스 라이선스를 열 수 있습니다.
