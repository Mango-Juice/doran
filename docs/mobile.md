# 도란 모바일 구현

## 구조

`lib/domain`의 기록·하루 일기 모델과 KST 날짜 함수, `lib/data`의 HTTP 계약 및 보안 저장소, `lib/ui`의 화면을 분리했습니다. Riverpod은 인증 상태와 저장소 생명주기를 관리합니다. 사용자가 바뀌면 화면 트리를 새로 만들고 진행 중 요청의 응답은 소유자를 다시 검사합니다.

초기화 전에도 준비 화면을 표시합니다. 10초가 지나면 안내와 재확인을 제공하되, 대기 중인 초기화 Future를 재사용합니다. 초기화가 실제로 실패하고 기존 클라이언트 정리까지 끝난 경우에만 새 시도를 시작합니다. 화면이 사라지거나 새 관찰을 시작한 뒤 도착한 이전 응답은 반영하지 않습니다. Dart 진단 빌드는 진입·프레임·보안 저장소 읽기·서버 클라이언트 초기화의 고정 단계명과 시각만 남기며 키·계정·세션·오류 원문은 기록하지 않습니다. Swift 단계는 `DEBUG` 컴파일 조건을 켠 구성에서만 제공하며 이번 실행의 실측에는 포함하지 않았습니다.

Flutter 3.50.0부터 deprecated된 `flutter/material.dart` 대신 공식 `material_ui` 패키지를 직접 사용합니다. 밝은 중성 바탕, 짙은 글자, 갈색 강조색을 사용하고 앱 아이콘은 대화에서 기록으로 이어지는 말풍선·세 줄로 구성했습니다. Unslop의 간격·색·타이포그래피 원칙을 참고했습니다 (Damir Sokolovsky, CC BY 4.0, https://heydamir.design/unslop).

Google 로그인 버튼은 공식 표준 G 원본, 흰색 바탕, Google Sans Medium과 플랫폼별 간격을 적용합니다. 원본·해시·OFL 고지는 `apps/mobile/assets/google/README.md`에 기록했습니다.

## 로그인과 데이터

Google 로그인은 시스템 브라우저 PKCE를 사용합니다. redirect는 `app.doran.mobile://auth-callback`입니다. URL과 publishable key만 앱에 포함하며 비밀키는 서버에 둡니다. 로그인 세션과 PKCE verifier, 계정별 캐시·초안은 Flutter Secure Storage로 보관합니다. 저장소 쓰기는 순서대로 처리해 늦은 초안 저장이 최신 글을 덮어쓰지 않도록 했습니다.

서버 HTTP 401/403 등 접근 거절에는 오프라인 캐시로 대신 성공하지 않습니다. 네트워크 오류일 때만 저장된 기록을 읽고 화면에 오프라인 상태를 표시합니다. 모든 변경은 서버 확인 후 완료로 표시합니다. 기록 충돌 시 최신 서버 본문을 보여주고 명시적으로 덮어쓰도록 합니다. 날짜와 개인화 제외는 사용자가 실제로 바꾼 경우에만 PATCH합니다.

## 실제 환경 확인 필요

`2c9bdc2` 소스에 대응하는 Android APK `59a42829…`에서 실제 Google 로그인과 모바일·차량 양쪽 승인 연결을 확인했습니다. iOS는 시스템 Google 로그인 페이지 표시까지 관측했으며 계정 인증 완료는 사용자 입력을 기다립니다. 실제 대화·클라우드 기록 생성과 Android·iOS 간 동기화는 남아 있습니다. 위젯 시험의 성공을 실제 운영 성공으로 기록하지 않습니다. iOS는 시뮬레이터 범위이며 App Store 배포나 실제 iPhone 설치를 포함하지 않습니다.

## 2026-11-14 확인 결과

- Flutter 3.50.0 / Dart 3.14.0, 공식 Flutter commit `ab2912035e160d9cad6306df307576da6128ebdb`.
- `2c9bdc2` 소스 기준으로 현재 설치·검증한 Android release APK `59a42829…` 빌드 성공. `app.doran.mobile`, versionName `1.0.0`, versionCode `1`, minSdk 26, targetSdk 36. 별도 보관한 release 키로 APK Signature Scheme v2 검증 통과. 중립 경로에서 난독화 빌드해 개인 홈 경로를 제거했으며 공개 검사에서 APK 발견 항목 없음.
- Xcode 27.0의 iOS 27.0 simulator 대상 초기 준비 화면·Google 브랜딩·Keychain entitlement를 포함한 당시 소스 빌드 성공. 현재 Runner에는 아래의 후속 로그인 런처 수정이 포함되지 않음. 프로젝트 빌드 스크립트로 Xcode 선택 문제를 해결했고 전역 `xcode-select`는 변경하지 않음. Android에서 iOS로 전환할 때 플러그인 연결을 재생성하도록 기본 pub 단계를 유지함.
- 현재 APK·Runner에 대응하는 기존 소스의 정적 검사와 전체 62개 단위·위젯 시험 통과. 계정 변경 시 Navigator 경로 폐기와 소유자에 고정된 저장소의 지연 저장 차단, 선택 날짜·이력 페이지 유지, 인증 만료 시 이전 내용 제거, 중복 초기화·늦은 완료·정리 전 재시도 방지를 포함함.
- 후속 로그인 소스는 시스템 브라우저 실행 결과가 `false`인 경우에도 오류·재시도를 표시하고, 화면 폐기 뒤 늦은 결과를 무시하도록 수정했다. 정적 검사와 전체 65개 단위·위젯 시험이 통과했다. 이는 소스 검사 결과이며 현재 Android APK `59a42829…`와 iOS Runner에는 아직 포함되지 않았다. 두 플랫폼의 네이티브 재빌드·실행 확인이 필요하다. 브라우저 실행 성공은 계정 인증 완료와 별개다.
- 이전 iPhone 17 Pro headless 실행은 흰 화면과 호스트 지연이 있어 통과로 판정하지 않음. 이후 다른 VM을 종료하고 Device Hub 실제 창에서 최신 앱을 단독 실행했을 때, 로그인 화면과 접근성 트리가 정상 표시되는 것을 확인함. 실행 환경과 초기화 코드가 함께 바뀌었으므로 이전 흰 화면의 원인을 한쪽으로 단정하지 않음.
- 기존 API 33 기기는 OS Bluetooth crash·System UI ANR이 겹쳐 정상 실행 판정에서 제외했고 데이터를 초기화하지 않고 종료함.
- 기존에 설치된 공식 Android API 37 Google APIs 이미지로 새 `Doran_Mobile_Verification` AVD(CPU 2개)를 생성해 release APK를 깨끗하게 설치함. RAM을 2GB로 요청했지만 실제 부팅 로그에서 4GB로 자동 상향된 것을 확인함. 실제 도란 로그인 화면이 오류 팝업 없이 표시되는 것을 확인함. `am start -W`의 Activity cold start는 3,300ms이며, Flutter 전체 화면 완료 시각을 별도로 계측한 결과는 아님.
- 위 headless/SwiftShader 환경은 이후 QEMU watchdog 종료가 발생함. crash report에는 CoreAudio 장치 시작 대기와 QEMU I/O 잠금 대기가 함께 나타나며, 근본 정체 원인은 확정하지 않음. 지속 실행·다중 VM 안정성은 통과로 판정하지 않음. 이후 동일 userdata로 오디오를 끈 visible 환경에서 단독 설치·로그인 검증을 수행함.
- Android 실제 Google 로그인 후 오늘 빈 화면과 모바일·차량 양쪽 승인 연결을 확인함. [인증 관측](verification/android-google-20261114.json)·[계정 연결 관측](verification/car-account-20261114.json). 기록 생성·수정·삭제·계정 간 동기화와 iOS 계정 인증 완료는 남음.
- Google 로그인 브랜딩과 초기 준비·재시도 화면을 포함한 Android release를 다시 빌드하고 동일 AVD에 업데이트 설치함. Android·iOS에서 공식 G 아이콘과 새 로그인 버튼의 실제 표시를 확인함.

## Android 검증 실행 설정

최신 실행은 Emulator 37.2.13의 API 37 Google APIs arm64 이미지, CPU 2개, 요청 RAM 4096MB를 사용한다. guest `/proc/meminfo`의 `MemTotal`은 4,009,164kB였다. [공식 릴리스 노트](https://developer.android.com/studio/releases/emulator#36-6-11)도 API 37 Phone AVD의 최소 RAM을 4GB로 명시한다. 같은 AVD userdata를 보존하고 다른 VM·네이티브 빌드와 순차로 실행한다.

```sh
emulator -avd Doran_Mobile_Verification -port 5556 -memory 4096 -cores 2 \
  -no-snapshot -no-boot-anim -audio none -crash-report-mode never -no-metrics
```

창을 표시했지만 API 37의 GLES 3.1 요구 때문에 실제 로그는 GLES `swangle`, Vulkan `lavapipe`로 소프트웨어 렌더링을 선택했다. 모바일 기록 앱에 음성 입력·출력이 없어 검증 기기의 오디오를 끄고 CoreAudio 대기를 분리한다. 이 설정은 차량의 실제 STT·TTS 시험에는 적용하지 않는다. crash 전송과 사용 통계는 공식 CLI 옵션으로 거절하며 앱·SDK 자료를 외부로 보내지 않고 로컬 진단만 보관한다.

앞선 업데이트 설치에서 로그인 화면을 확인했다. Activity cold start는 5,475ms였으며, 이 값은 Flutter의 전체 화면 준비 완료 시각을 뜻하지 않는다. 이후 같은 `59a42829…` APK에서 실제 Google 로그인·차량 연결을 확인했다. 장시간 안정성은 별도 검증 대상이다.

## iOS 첫 화면과 시스템 로그인 페이지

iPhone 17 Pro, iOS 27.0 runtime `24A5423a`, Xcode 27.0, Flutter 3.50.0의 simulator debug 빌드로 확인했다. 다른 VM·네이티브 빌드는 종료하고 Device Hub의 실제 창에서 실행했다. 최초 관측에서는 준비·앱 로그인 첫 화면이 표시됐고 Google 인증 버튼은 누르지 않았다. 후속 CUA에서는 같은 기존 Runner로 시스템 Google 로그인 페이지 표시까지 확인했으며 계정 인증 완료는 사용자 입력을 기다린다. 초기 검은 화면 뒤 재시작 없이 페이지가 표시된 원인은 확정하지 않았고, 아직 Runner에 포함되지 않은 런처 `false` 처리 수정의 효과로 보지 않는다.

고정 단계 메타데이터의 측정값은 Dart 진입→준비 프레임 구성 672ms, Supabase 초기화 575ms(첫 Keychain 읽기 525ms), Dart 진입→첫 raster 완료 1,432ms였다. 운영체제의 프로세스 실행 요청부터 잰 전체 시작 시간은 아니다. Swift `DEBUG` 조건은 이 Runner 구성에 정의되지 않아 Swift 단계 로그를 관측하지 못했다. 당시 첫 화면 검증을 마친 뒤 simulator를 정상 종료했다.
추가 검토에서 계정 변경 시 편집 경로가 남는 문제를 수정했습니다. MaterialApp/Navigator 전체를 계정별로 교체하고, 저장소 인스턴스를 생성 당시 소유자에 고정합니다. 이전 편집기의 비동기 저장이 재개되어도 새 계정 토큰으로 요청하지 않습니다. 자동 갱신은 선택 날짜와 이미 불러온 이력 페이지를 유지합니다. 설정 화면에서 오픈소스 라이선스를 열 수 있습니다.
