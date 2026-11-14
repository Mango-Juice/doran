# 차량 맥락 SDK 연결

`PleosVehicleContext`는 사용자가 차량 정보 활용에 동의했고 앱이 전면에 있을 때만 동작한다. 모든 콜백은 SDK 생성 세대와 연결되어 중지·재시작 전의 콜백이 새 상태를 변경할 수 없다. `stop()`은 구독과 SDK를 해제하고 메모리의 차량 맥락을 비운다.

| SDK | 버전 | 실제 사용하는 데이터 | 권한 |
|---|---|---|---|
| Vehicle | 2.3.2 | 지원 확인 후 현재 운행 시간·거리. 좌석 자동 판단은 의미 불일치로 제외 | `pleos.car.permission.CAR_DRIVE`, `pleos.car.permission.CAR_SEATS` |
| NaviHelper | 3.0.5 | 활성 경로의 남은 시간, 목적지 도착 이벤트 | `pleos.car.permission.NAVI_ROUTE` |
| FusedLocation | 1.1.3 | 현재 위치와 측정 시각의 로컬 상태 | `pleos.car.permission.FUSED_LOCATION` |

FusedLocation 권한은 런타임 허용이 필요하다. 권한 요청 화면은 호출 앱이 정차 상태에서 제공한다. 권한 거부는 대화 전체의 실패가 되지 않으며 위치 상태만 `PERMISSION_REQUIRED`가 된다. 나머지 API의 권한 오류·미지원·null 결과도 정상 값과 구별한다.

## 단위와 유효성

- `Odometer.getTripCurrentDrivingTime()`의 단위는 **분**이다. 서버 계약에 맞춰 초로 변환한다.
- `Odometer.getTripCurrentDrivingDistance()`의 단위는 **미터**다. 누적 주행거리 API의 km 단위와 혼동하지 않는다.
- NaviHelper `DestinationInfo.remainTime`, `DrivingState.duration`, `DestinationArrivedInfo.duration`은 **초**다. 목적지 도착의 이동거리는 **미터**다.
- 현재 값은 수신 시각과 출처를 가진다. 위치는 SDK의 원래 측정 시각을 보존한다. 서버에 보내는 신호는 수신 후 60초를 넘었거나 미래 시각이면 제외한다. on-change 구독 값이 갱신되지 않으면 오래된 값으로 질문하지 않는다.
- 명시적인 `ROUTE_GUIDING` 모드에서만 목적지·남은 시간을 사용한다. `isRouting`만으로 안내 여부를 판정하지 않으며 `ROUTE_DRIVE` 단독이나 경로 검색 완료도 실제 안내 시작으로 해석하지 않는다. 명시 취소·FREE/비안내 상태·오류는 활성 경로를 지운다. 정상적으로 수락한 도착 직후 FREE 전환에서는 도착 사실을 60초간 보존하지만, 취소된 경로의 늦은 도착은 수락하지 않는다. 시뮬레이션 안내는 실제 이동 맥락에서 제외한다.

## 동승자 정보의 보수적 처리

현재 생산 코드의 `SeatOccupancyTrust`는 자동 좌석 판단을 허용하지 않는다. Vehicle 2.3.2와 Pleos Connect 2.1.0에서 공식 Console의 Empty/Occupied 의미와 SDK Boolean 변환이 맞지 않는 현상을 실제 토글로 확인했으며, 신뢰할 수 있는 다른 SDK·플랫폼 조합은 아직 검증하지 못했다. 미시험 이미지나 실차를 자동으로 허용하지 않는다.

실제 capability의 READ 응답은 그대로 받아들이되, 생산 맥락의 좌석 상태를 `UNAVAILABLE`, 값·측정 시각을 null로 유지하고 자동 점유 구독을 시작하지 않는다. 서버에는 `passenger_state=unknown`으로 전달한다. 이전 콜백이나 수동 구성된 메모리 값에 `true`가 있더라도 전송 경계에서 다시 제외하며, 좌석 값의 시각으로 맥락을 새 관측처럼 갱신하지 않는다. 다른 유효한 차량 신호에는 영향을 주지 않는다.

Boolean 값을 반전하거나 가짜 미점유 값으로 대체하지 않는다. debug 진단 하네스의 직접 capability/GET 조회는 그대로 유지해 SDK의 실제 결과를 기록한다. `unknown`에서는 개인 과거 이야기를 먼저 꺼내지 않는 기본 제한이 유지된다. 정차 중 사용자가 명시적으로 혼자임을 확인하는 기존 선택은 별도의 `user_assertion` 출처이며 센서 검증 결과가 아니다. SDK 어댑터는 `alone`을 추정하지 않는다.

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

## 에뮬레이터에서 확인한 지원 범위

Pleos Connect 2.1.0 / Android 14 ARM64에서 확인한 범위입니다. 가상 신호와 합성 입력을 사용한 SDK 시험이며 실차 센서 검증은 아닙니다.

| 기능 | 관측과 사용 범위 |
|---|---|
| SpeechToText·TextToSpeech | SDK 인식 결과와 출력 완료 콜백 확인. 청취 품질은 별도 검증 |
| FusedLocation | 권한 거부·허용 상태와 가상 GPS의 측정 시각 수신 |
| Odometer | 현재 운행 시간·거리 capability 오류. 앱에서 유효하지 않은 맥락으로 제외 |
| Seat | Console 점유 상태와 SDK Boolean 의미 불일치. 자동 동승 판단에서 제외 |
| NaviHelper | 실제 서비스 연결·경로 시작·안내 모드·남은 시간·취소 콜백 확인. 실제 도착·반복 방문은 미검증 |
| CarUxRestrictions | 주행·정차 전환의 표시 제한과 분할 화면 확인 |

`NAVI_ROUTE_SEARCH` 권한과 경로 요청 시험은 debug/instrumentation에만 있으며 release에는 없습니다. SDK 콜백에 경로 식별자가 없어 새 안내가 시작된 뒤 이전 경로의 payload가 도착하는 경우를 완전히 구분할 수는 없습니다.

## 에뮬레이터 실행

전체 디스플레이 기준은 1920×1080입니다. OEM 화면 배치에 따라 앱 영역은 더 작으며 분할 시 다시 줄어듭니다. 메모리가 부족한 호스트에서는 Android 빌드와 에뮬레이터를 동시에 실행하지 않습니다.

Virtual Console을 열어 DDS bridge를 활성화한 뒤 가상 차량 신호를 사용합니다. Console이 비활성인 동안의 HTTP 오류를 SDK 미지원으로 판정하지 않습니다. `adb`는 Android SDK의 `platform-tools`를 PATH에 추가합니다.

```sh
adb -s emulator-5554 forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088/`를 열어 유지합니다. 현재 기기 serial과 포트는 실행 환경에 맞게 선택합니다. 준비 도구와 Mac 출력 경로는 [오디오 안내](emulator-audio.md), 실제 시험 범위는 [검증 요약](verification.md)을 참고하세요.
