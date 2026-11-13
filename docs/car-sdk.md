# 차량 맥락 SDK 연결

`PleosVehicleContext`는 사용자가 차량 정보 활용에 동의했고 앱이 전면에 있을 때만 동작한다. 모든 콜백은 SDK 생성 세대와 연결되어 중지·재시작 전의 콜백이 새 상태를 변경할 수 없다. `stop()`은 구독과 SDK를 해제하고 메모리의 차량 맥락을 비운다.

| SDK | 버전 | 실제 사용하는 데이터 | 권한 |
|---|---|---|---|
| Vehicle | 2.3.2 | 현재 운행 시간·거리, 지원되는 좌석의 점유 | `pleos.car.permission.CAR_DRIVE`, `pleos.car.permission.CAR_SEATS` |
| NaviHelper | 3.0.5 | 활성 경로의 남은 시간, 목적지 도착 이벤트 | `pleos.car.permission.NAVI_ROUTE` |
| FusedLocation | 1.1.3 | 현재 위치와 측정 시각의 로컬 상태 | `pleos.car.permission.FUSED_LOCATION` |

FusedLocation 권한은 런타임 허용이 필요하다. 권한 요청 화면은 호출 앱이 정차 상태에서 제공한다. 권한 거부는 대화 전체의 실패가 되지 않으며 위치 상태만 `PERMISSION_REQUIRED`가 된다. 나머지 API의 권한 오류·미지원·null 결과도 정상 값과 구별한다.

## 단위와 유효성

- `Odometer.getTripCurrentDrivingTime()`의 단위는 **분**이다. 서버 계약에 맞춰 초로 변환한다.
- `Odometer.getTripCurrentDrivingDistance()`의 단위는 **미터**다. 누적 주행거리 API의 km 단위와 혼동하지 않는다.
- NaviHelper `DestinationInfo.remainTime`, `DrivingState.duration`, `DestinationArrivedInfo.duration`은 **초**다. 목적지 도착의 이동거리는 **미터**다.
- 현재 값은 수신 시각과 출처를 가진다. 위치는 SDK의 원래 측정 시각을 보존한다. 서버에 보내는 신호는 수신 후 60초를 넘었거나 미래 시각이면 제외한다. on-change 구독 값이 갱신되지 않으면 오래된 값으로 질문하지 않는다.
- 경로가 활성화된 경우에만 남은 시간을 사용한다. 경로 취소·종료·오류는 남은 시간을 지우며, 새 도착 이벤트는 별도로 보존한다.

## 동승자 정보의 보수적 처리

좌석 capability에서 읽기 지원을 확인하고 지원되는 영역에만 구독한다. 한국 에뮬레이터의 운전석인 `ROW_1_LEFT`가 포함된 영역은 동승자 판정에서 제외한다. 지원되는 다른 좌석 중 하나라도 점유가 확인되면 `occupied`로 전달한다.

미점유 센서만으로 모든 동승석의 센서가 지원된다고 단정할 수 없으므로, 그 외에는 `unknown`을 전달한다. 이 SDK 어댑터는 `alone`을 추정하지 않는다. 차량마다 실제 좌석 배치와 센서 지원 범위가 다르므로 실차 지원을 주장하려면 별도 검증이 필요하다.

## 서버 전송 범위

API v1의 `source`, `observed_at`, `passenger_state`와 유효한 `destination_eta_seconds`, `arrived`, `trip_duration_seconds`, `trip_distance_m`, `trip_duration_source`, `place_label`, `trip_baseline`만 직렬화한다. 정밀 좌표, 경로, 목적지 이름·주소, 임의의 위치 셀은 서버로 전송하지 않는다. FusedLocation 수집 값과 장소 클러스터는 기기에만 유지한다. 반복 방문 조건을 충족하고 사용자가 직접 의미를 알려준 장소만 `place_label`로 전달한다.

## 반복 방문과 사용자 장소 설명

- “회사 가는 중”, “집으로 가고 있어요”처럼 직접 진술한 제한된 문형만 인식한다. 인용·가정·부정·다른 사람의 이야기는 학습하지 않는다.
- 출발 중의 현재 위치를 회사나 집으로 지정하지 않는다. 활성 내비 목적지에 임시 라벨을 연결한 뒤, 실제 `onDestinationArrived` 이벤트와 최근 30초 내 위치가 목적지 반경 200 m 이내인 조건을 함께 확인해야 클러스터를 저장한다.
- 서로 다른 도착은 최소 30분 간격이어야 하며 같은 운행의 중복 콜백은 방문 횟수를 늘리지 않는다. 3회 이상 확인된 장소만 대화 맥락에 사용한다. 건물 출입이나 출근 완료를 증명하는 정보로 해석하지 않는다.
- “여기는 회사가 아니라 집이야”라는 정정은 최근 10분 내 실제 도착이 확인된 장소에서만 적용한다. 이전 라벨을 대체한다.
- 장소 중심·사용자 라벨·방문 횟수·마지막 도착 시각은 Android Keystore 기반 `SecureStorage`에 암호화해 보관한다. 최대 100개이며 90일 동안 방문하지 않은 패턴은 사용하지 않는다.
- 저장된 장소는 계정 소유자와 `vehicle_memory_revision`에 연결된다. 계정 변경, 연결 해제, 차량 정보 동의 철회, 서버에서 원본 기록을 수정·제외·삭제한 경우 기존 장소 기억을 지운다. 정상적인 새 기록 추가는 방문 횟수를 초기화하지 않는다.
- 아직 실제 차량 위치 오차 환경에서 검증한 기능은 아니다. 목적지 정보나 위치가 없거나 도착을 확인할 수 없으면 라벨을 만들지 않는다.

## 이전 도착 기록과 비교

- 같은 목적지 클러스터에서 최근 30일 동안 확인된 도착 중 최대 120건에서 3건 이상으로 기준값을 만든다. 이동시간은 `DestinationArrivedInfo.duration`만 사용한다. Odometer의 현재 시동 구간 누적 시간은 목적지별 이동시간 기준에 섞지 않는다.
- 기준값은 표본 수, 이동시간 중앙값(초), KST 도착 이벤트 수신 시각의 중앙값(분), 마지막 관측 시각, `local_confirmed_arrivals` 출처를 가진다. 자정 전후의 도착 시각은 원형 중앙값으로 계산한다.
- 현재 운행의 도착은 그 운행을 비교하는 기준값에서 제외한다. 현재 도착이 저장되더라도 이전 표본으로 계산한 기준값을 유지한다.
- 실제 도착 직후 전송하는 이동시간은 NaviHelper의 해당 구간 값으로 교체하며 `trip_duration_source=pleos_navi_arrived`로 표시한다. 도착 전의 Odometer 값은 `pleos_vehicle_odometer`로 표시한다. 서로 다른 범위의 값을 정상 운행 기준과 직접 비교하지 않는다.
- “출근 시간이 바뀌었어”, “요즘 출근 시간이 바뀌어서 괜찮아요”라는 직접 설명은 회사 도착 기준을 새 구간으로 나눈다. 과거 도착 시간은 새 기준에 다시 포함하지 않고, 이후 확인된 도착 3건부터 새 중앙값을 제공한다. 집의 도착 기준에는 영향을 주지 않는다.
- 도착 시각은 앱이 도착 이벤트를 받은 시각이다. 건물 출입·출근 완료·지각·사용자 감정을 증명하지 않는다. 서로 다른 출발지·교통 상황이 포함될 수 있으므로 대화에서 경험을 확인하는 단서로만 사용한다.
- `tripKey`는 차량 UI의 운행당 대화 제안 중복 방지를 위한 메모리 값이다. 활성 내비 운행에서만 제공하며 서버에 전송하지 않는다.

## 검증 수준

공식 Maven에 배포된 AAR 및 source JAR의 타입·nullable 콜백·단위를 대조해 구현했다. Kotlin 컴파일과 정책 단위 시험은 코드 호환성 검증이며, 실제 에뮬레이터에서 각 센서가 값을 제공한다는 증거는 별도로 기록한다. 반환값을 임의의 성공 데이터로 대체하지 않는다.

공식 근거:

- [Vehicle 설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [Vehicle 도메인 객체와 생명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)
- [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)
- [NaviHelper 초기화와 해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release)
- [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)

## 에뮬레이터 실행 및 Virtual Console 활성화

검증용 `Doran_Verification` AVD는 공식 Pleos Connect 2.1.0 arm64 이미지를 사용하며 RAM 3072MB, CPU 2개, 1920×1080으로 구성했다. 기존 PoC AVD와 원본 시스템 이미지는 보존했다. 메모리가 작은 개발 Mac에서는 release 축소 빌드(R8)와 에뮬레이터의 동시 실행을 피한다. 실제로 QEMU main loop와 CPU thread의 15초 무응답 뒤 HangDetector 종료가 관측됐다. 이 현상을 앱 크래시와 구분한다.

Virtual Console의 페이지는 `POST /browser/session?client=<UUID>`를 1초마다 호출하며, 세션 유효기간은 3초다. 브라우저 페이지를 열어 두거나 같은 세션 프로토콜을 유지해야 DDS bridge가 활성화된다. 활성화 전에는 `/vehicle/state` 조회가 성공해도 `emulator_active=false`, `dds_ready.all=false`일 수 있고 상태 변경은 HTTP 503 `emulator_inactive`로 거절된다. 이를 차량 SDK 기능 미지원으로 판단하면 안 된다.

아래 명령은 `apps/car` 디렉터리에서 실행한다. `adb`는 Android SDK의 `platform-tools`를 PATH에 추가해 사용한다.

```sh
adb -s emulator-5554 forward tcp:8088 tcp:8088
# PC에서 http://127.0.0.1:8088/ 페이지를 열고 유지한다.
# 또는 실제 가상 신호와 앱 UI를 함께 확인하는 아래 스크립트를 사용한다.
python3 scripts/verify_vehicle.py \
  --serial emulator-5554 \
  --apk app/build/outputs/apk/release/app-release.apk \
  --output app/build/verification/release-ux-check \
  --expect-unpaired
```

스크립트는 실제 에뮬레이터만 허용하며 연결 세션 heartbeat, Park→Drive 20km/h→Park 전환, 플랫폼 UXR, 앱 생존과 화면을 검사한다. 시험 종료 시 Park 복구와 heartbeat 종료를 시도한다. 마이크·AI·모바일 연동 시험을 대신하지 않는다. 결과 디렉터리는 새 경로를 지정하여 이전 실패 증거를 보존한다.

`ui_wait_seconds`에는 UIAutomator의 idle 대기와 ADB 통신 시간이 포함되므로 조작 피드백 시간으로 사용하지 않는다. Android `am start -W`의 완료 시간도 사용자가 실제 내용을 본 시각과 다를 수 있다. 앱 첫 내용 draw 로그와 시각이 기록된 화면 샘플을 함께 비교한다.
