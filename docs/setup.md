# 개발·운영 설정

## 앱을 실행할 때

제출 APK는 도란의 서울 리전 Supabase 프로젝트에 연결한다. 모바일은 Google 로그인, 차량은 별도 기기 인증과 일회용 코드 연결을 사용한다. 네트워크와 서버 운영이 필요하며, 서버가 사용할 수 없을 때 고정된 AI 응답으로 대체하지 않는다.

차량은 Pleos Connect 2.1.0 / Android 14 ARM64의 기본 1920×1080 화면을 기준으로 한다. SDK별 실제 지원 상태와 에뮬레이터 실행 조건은 [SDK 확인 기록](car-sdk.md)을 확인한다. 휴대전화용 Android APK와 차량 APK는 서로 다른 앱이다. iOS 산출물은 시뮬레이터 빌드이며 실제 iPhone에 설치하는 배포 패키지가 아니다.

현재 도란의 Google OAuth 앱은 프로덕션 상태로 전환했다. 이 설정 확인과 실제 모바일 로그인 성공은 별도이며, [검증 기록](verification.md)의 최신 결과가 우선한다. 별도 서버를 만들 때 Google OAuth 앱을 테스트 상태로 두면 등록된 테스트 사용자만 로그인할 수 있다.

## 클라이언트 설정

차량은 `apps/car/local.properties.example`을 같은 폴더의 `local.properties`로 복사하고 `sdk.dir`을 설정한다. 기본 공개 Supabase URL과 publishable key는 예시 파일에 있다. 모바일 기본값은 같은 프로젝트를 가리키며 다른 서버를 사용할 때는 `SUPABASE_URL`과 `SUPABASE_PUBLISHABLE_KEY`를 `--dart-define`으로 전달한다.

publishable key는 사용자 인증을 대신하지 않는다. 모든 개인 기록 API는 유효한 세션과 소유권 검사를 요구한다. `OPENAI_API_KEY`, Supabase service role 또는 secret key를 클라이언트 설정에 넣지 않는다.

Flutter 버전과 커밋은 루트 `toolchain.json`, Dart 패키지는 `apps/mobile/pubspec.lock`을 따른다. 도구를 프로젝트 전용 경로에 설치해도 된다. 전역 Flutter 또는 Xcode 선택을 변경할 필요는 없다.

## 별도 서버를 구성할 때

1. Supabase 프로젝트를 준비하고 `supabase/migrations`의 마이그레이션을 파일 순서대로 적용한다. 로컬 DB 시험용 Auth shim을 운영 DB에 적용하지 않는다.
2. Auth에서 Google 공급자를 설정한다. Google OAuth 웹 클라이언트의 redirect URI는 해당 프로젝트의 `https://PROJECT_REF.supabase.co/auth/v1/callback`이다. Supabase의 Site URL과 허용 redirect에는 `app.doran.mobile://auth-callback`을 등록한다.
3. 차량 식별용 anonymous sign-in을 활성화한다. 이 계정에 개인 기록 읽기 권한을 추가하지 않는다. 마이그레이션의 RLS와 service-only 함수 권한을 유지한다.
4. Edge Function Secrets에 `OPENAI_API_KEY`를 등록한다. 비밀값은 공개 저장소·클라이언트·로그에 기록하지 않는다. Supabase가 제공하는 서버 환경 변수는 함수 런타임에서 사용한다.
5. `supabase/functions/doran-api`와 `doran-worker`를 배포한다. `supabase/config.toml`의 `verify_jwt = false`는 함수 내부 Auth 검증을 전제로 한다. `/about`과 `/privacy` 외 API를 인증 없이 열어서는 안 된다.
6. 워커 스케줄 마이그레이션의 실행 대상이 새 프로젝트를 가리키는지 확인한다. 큐 처리·비밀 워커 인증·예산 예약을 실제 HTTP 요청으로 검증한다. 원래 도란 프로젝트의 운영 예산을 새 환경에 그대로 공유하지 않는다.

제출 서비스의 총 AI 한도는 30USD다. 서버에 29USD, 별도 연결·품질 평가에 1USD를 배정한다. 기본 마이그레이션의 서버 한도를 그대로 둔 채 외부 평가비를 추가하지 않도록 배포 후 다음을 적용한다.

```sql
update private.budget set limit_usd = 29 where id = 1;
```

직접 모델 평가는 DB 원장을 거치지 않으므로 평가 실행기의 이전 정산·미정산 예약액을 이어 사용한다. 예약·차감 방식은 [서버 문서](backend.md), 평가 명령은 [평가 안내](../supabase/tests/evals/README.md)를 따른다.

## Release 서명과 제출

서명키와 비밀번호 파일은 저장소 밖에 둔다. 차량·모바일 README의 `DORAN_KEYSTORE_*`, `DORAN_KEY_ALIAS`, `DORAN_KEY_PASSWORD` 설정을 사용한다. 제출용 키와 복구 사본은 공개 저장소에 포함하지 않는다. 서명되지 않은 APK나 debug 키로 서명한 빌드를 제출본으로 표시하지 않는다.

단위·계약 시험 이후 깨끗한 환경에서 최종 release APK를 설치하고 Google 로그인부터 기록 삭제까지 확인한다. 최종 소스 커밋, APK, 영상은 [제출 절차](submission.md)에 따라 SHA-256과 함께 고정한다.
