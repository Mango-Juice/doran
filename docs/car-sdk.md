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
- 경로가 활성화된 경우에만 남은 시간을 사용한다. 경로 취소·종료·오류는 남은 시간을 지우며, 새 도착 이벤트는 별도로 보존한다.

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

## 최초 SDK 계측: 2026-11-14 04:17 KST

공식 Pleos Connect 2.1.0/API 34의 `Doran_Verification`에서 별도 `app.doran.car.debug` 패키지를 새로 설치해 04:17:24–04:18:45 KST에 1회 측정했다. 아래 결과는 실제 SDK 콜백이며 입력은 에뮬레이터 신호와 합성 PCM이다. 사람의 마이크 발화, 실차 센서, 실제 스피커 청취 시험으로 해석하지 않는다.

| 항목 | 실제 관측 | 앱에서의 의미와 남은 검증 |
|---|---|---|
| FusedLocation | 권한 미허용 `PERMISSION_REQUIRED` → 허용 후 `AVAILABLE`, 원래 측정 시각 수신 | 호스트가 주입한 가상 GPS 값이다. 실제 위치 정확도는 미검증이다. |
| Odometer 시간·거리 | 두 capability 요청 모두 `ERROR`(예외 클래스 `a`, 상세 오류 코드 없음) | 어댑터는 `UNAVAILABLE`로 처리하고 맥락에서 제외했다. 값·단위 변환의 실측 및 이 이미지의 정상 지원 여부는 미확인이다. |
| Seat | `READ_SUPPORTED`, 앞 좌우·뒤 좌중우 5개 영역에서 GET 콜백 각 1회, SDK Boolean `true` | 이 시험 당시 코드는 `occupied`로 처리했다. 이후 동시 토글에서 의미 불일치를 확인해 현재 생산 코드에서는 자동 판단을 제외했다. |
| NaviHelper | 연결 `AVAILABLE`, `CONNECTED_NO_ACTIVE_ROUTE_OBSERVED`; ETA `UNAVAILABLE`, 도착 `WAITING` | 서비스 연결만 확인했다. 실제 활성 경로·목적지 도착·반복 방문 학습은 이 실행에서 검증하지 못했다. |
| CarUxRestrictions | 정차 시 `known=true`, `requires_do=false`, 제한 플래그 `0`, 설정 허용 | SDK 계측 중의 정차 상태다. 주행 전환 검증은 별도 UI 시험 결과를 사용한다. |
| SpeechToText 3.3.1 | `ON_DEVICE`, 0.974초 PCM의 FINAL “감사합니다”, 예상 문장 일치 | 합성 입력에서 실제 인식 결과를 수신했다. 마이크 권한·사람 발화·20회 연속 음성 턴은 별도 시험이 필요하다. |
| TextToSpeech 3.3.1 | `ON_DEVICE`, 실제 `onReady`·`onStart`·`onDone`, 오디오 포커스 중단 0회 | SDK 완료 콜백을 확인했다. 실제 소리·첫 음절·재생 속도는 녹음 또는 청취로 확인해야 한다. |

공개용 [SDK 관측 요약](../apps/car/verification/sdk-91390127.json)에는 실제 시험한 debug APK와 instrumentation APK의 SHA-256, 실행 조건과 한계를 기록했다. 이름의 `91390127`은 같은 소스 빌드에서 만든 release APK를 가리키며, 계측 대상은 별도 debug 패키지다. 이 결과가 이후 최종 release APK의 실제 실행 검증을 대신하지 않는다.

원본은 로컬 `apps/car/app/build/verification/`의 `sdk-verification-91390127.json`, `sdk-summary-91390127.json`, `sdk-callback-log-91390127.txt`다. 원본 로그와 설정은 Git 관리 대상이 아니다. StateFlow 관측 개수와 SDK의 원래 GET 콜백 개수는 서로 다른 지표다. `harness_completed=true`는 결과 수집과 정리 완료를 뜻하며 모든 SDK 기능 성공을 뜻하지 않는다.

최초 시험에서는 약 7분 앞선 Console의 4좌석 `false`와 SDK 5영역 `true` 사이의 차이만 확인해 원인을 미확정으로 남겼다. [Vehicle 2.3.2 배포 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar)의 `SeatImpl.kt:435`와 `PropertyCallback.kt:352`는 원시 정수 `1`만 `true`로 바꾸며 실제 AAR 바이트코드도 같다. [공개 Seat API](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy)는 Boolean 콜백을 제공하지만 원시 정수 의미나 Android 표준 enum과의 변환 계약을 설명하지 않는다. 후속 동시 토글 결과는 아래에 별도로 기록했다.

### 첫 실행 음성 지연 분석

| 측정 구간 | 첫 실행 관측 |
|---|---:|
| STT 하네스 호출 전체 | 20.505초 |
| STT initialize → 서비스 연결 | 약 0.366초 |
| STT 첫 PCM 전송 → completeAudioSend | 약 1.305초 |
| STT completeAudioSend → FINAL 콜백 | 약 18.514초 |
| TTS 하네스 호출 전체 | 28.569초 |
| TTS initialize → 서비스 연결 | 약 0.065초 |
| TTS speak → onStart | 약 0.391초 |
| TTS onStart → onDone | 약 27.122초 |

STT 입력은 16 kHz·16-bit·mono PCM 31,168바이트였다. [공식 PCM 전송 예제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)에 맞춰 최대 3,200바이트씩 100ms 간격으로 보내고 `completeAudioSend()`를 호출했다. 전송 간격의 실제 합계에는 에뮬레이터 스케줄링 지연도 포함된다. TTS 문장은 “도란 음성 출력 점검입니다. 감사합니다”로 공백·마침표를 포함한 21자이며 앱에서 속도 설정을 변경하지 않았다. 배포 AAR 3.3.1의 `TextToSpeech` 공개 메서드에는 재생 속도 setter가 없고, [공식 API 목록](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/set-speaker-type)과도 대조했다. SDK 내부 기본 속도의 실제 값은 확인하지 못했다.

가장 긴 구간은 서비스 연결 이후다. STT 클라이언트 바이트코드는 서비스 연결을 기다린 뒤 `requestCompleteSendBuffer`를 호출하지만, 서비스 내부 VAD·추론·모델 로드 시점은 공개 클라이언트에서 확인되지 않는다. TTS 콜백 간 27초가 실제 27초간의 소리인지도 로그만으로 판단할 수 없다. 이 한 번의 결과로 SDK 인스턴스 생성이나 모델 cold start를 원인으로 단정하지 않는다.

`completeAudioSend()`의 공개 설명은 입력 종료 통지이며 즉시 EPD 종료·FINAL 반환이나 특정 무음 길이·최대 대기시간을 보장하지 않는다. 사용한 PCM 끝 100ms의 RMS는 약 −89dBFS(최대 샘플 절댓값 2), 연속 완전 무음은 약 3.31ms였다. 따라서 무음이 전혀 없는 입력이라고 하기는 어렵지만 실제 마이크에서 긴 무음을 계속 받는 조건과 같지도 않다. 입력 종료 후 EPD 처리의 영향은 원인 후보로 남기되 확인된 원인으로 쓰지 않는다. 이 합성 `sendAudio()` 시험의 지연을 사람 발화의 `request()` 경로 지연으로 그대로 일반화하지 않는다.

코드 검토에서 18–27초 동안 메인 스레드를 막는 명시적 대기는 발견하지 못했다. 하네스의 `runBlocking`은 JUnit 계측 스레드에서 실행되고, Main 컨텍스트의 콜백 대기는 코루틴을 중단한다. PCM 전송과 간격 대기는 IO 컨텍스트에서 실행한다. TTS 준비 무음의 160ms `Thread.sleep`도 IO 컨텍스트에서 실행한다. 이것은 SDK 내부 처리나 에뮬레이터 CPU 경합이 없다는 증거는 아니다.

하네스는 차량 맥락 수집·해제 → 개별 capability 확인·해제 → 음성 시험 순서로 실행한다. 로그의 마지막 Vehicle 해제는 04:17:56.070, STT 초기화는 04:17:56.310이며 NaviHelper 해제도 그 전에 끝났다. 이 실행에서 Seat/Odometer/Navi 초기화가 TTS `onDone` 시점과 겹쳐 지연시켰다는 증거는 없다. 실제 앱에서 차량 정보 동의 켜기·끄기 비교는 별도 측정 대상이다.

후속 계측 하네스는 초기화 호출·완료, 첫 PCM 전송·마지막 전송, `completeAudioSend` 호출·반환, FINAL의 단조 시각과 구간별 시간을 기록하며 실패·시간 초과에도 이미 수집한 시각을 보존한다. SDK 메서드 반환은 서비스 처리 완료 확인이 아니므로 SDK logcat과 함께 비교한다. TTS는 호출 시각·문장 길이와 `DoranSpeech` 콜백 로그를 대응시킨다. 별도 Main 코루틴의 100ms 간격 관측으로 최대 스케줄링 공백도 수집한다.

## 후속 SDK 계측: 2026-11-14 05:46 KST

보이는 Pleos 에뮬레이터 한 대를 4096MB·CPU 2개로 실행해 05:46:32–05:46:54 KST에 합성 PCM 3회를 측정했다. GLES 렌더러는 Apple M1 Pro/Metal로 별도 관측했다. 원래 AVD 설정 파일은 변경하지 않고 실행 인수로 메모리·코어를 지정했다. 원본은 로컬 `apps/car/app/build/verification/b4fdac87-sdk-warm3/`의 `runtime.json`, `artifacts.json`, `sdk-verification.json`, `sdk-logcat.txt`, `seat-transitions.json`, `summary.json`에 보존했다.

계측 APK SHA-256은 debug `8a565859f5966df9d823dca86a7b094747481828046c7b7455a22b198aa33be4`, instrumentation `d2e3b128b9e0582d8a19739b531498c09770a3db3df1d75f2728d8fc9ec8125c`다. 디렉터리 접미사 `b4fdac87`은 당시 release 참조 해시의 앞부분이며 계측 앱은 별도 debug 빌드다. 이후 자동 좌석 판단을 제외하는 변경은 이 계측 APK에 포함되지 않는다.

비식별 [3회 음성·좌석 관측 요약](../apps/car/verification/sdk-b4fdac87-warm3.json)을 공개 저장소에 함께 제공한다. 원본 logcat과 설정은 로컬 검증 폴더에만 둔다.

| 회차 | 합성 입력 | STT 전체 | 입력 완료 호출 → FINAL | TTS 전체 | 결과 |
|---|---|---:|---:|---:|---|
| 1 | 감사합니다, 0.974초 | 2.323초 | 1.277초 | 4.869초 | FINAL 일치·SDK onDone |
| 2 | 미안합니다, 0.998초 | 1.524초 | 0.503초 | 3.233초 | FINAL 일치·SDK onDone |
| 3 | 감사합니다, 0.974초 | 1.402초 | 0.366초 | 3.412초 | FINAL 일치·SDK onDone |

오디오 포커스 중단은 0회, Main 코루틴의 최대 관측 간격은 138ms였고 500ms 초과는 없었다. 이 실행에서는 최초 시험보다 지연이 줄었지만, 메모리·시스템 부하·초기화 이력이 통제된 비교시험은 아니므로 원인을 특정하지 않는다. 사람의 마이크 발화·실제 청취·첫 음절·연속 20턴 성공을 대신하지 않는다.

### 같은 실행에서 확인한 좌석 의미 불일치

공식 Console 카탈로그의 요청은 `PUT /vehicle/seat?kind=occupied&value=false&area=passenger`이며 Empty는 `false`, Occupied는 `true`다. SDK 구독 중 조수석을 Empty → Occupied → Empty로 바꾼 뒤 원래 상태로 복구했다.

| Console 조수석 설정 | SDK areaId 4 원시 이벤트 | SDK Boolean에 대한 근거 |
|---|---:|---|
| Empty (`false`) | `1` | 마지막 Empty 상태의 직접 GET에서 `true` 수신 |
| Occupied (`true`) | `2` | 배포 소스의 `raw == 1` 규칙에 따르면 `false`. 개별 Boolean 전환은 이 실행의 JSON에 따로 기록되지 않음 |
| Empty (`false`) | `1` | 원시 이벤트의 복귀와 최종 GET `true`를 확인 |

다른 좌석의 값이 남아 집계 상태는 계속 `occupied`였으므로 집계만으로 조수석 Boolean 전환을 관측했다고 주장하지 않는다. Console은 운전석·동승석·뒤 좌우 4영역만 제공하며 SDK의 뒤 가운데 영역에 대응하는 입력은 없다. 이 결과는 현재 이미지/SDK 조합의 의미 불일치를 재현한 것이며 모든 차량의 동작으로 일반화하지 않는다. 현재 생산 코드의 자동 판단 제외 정책은 이 증거를 근거로 한다.

Odometer 현재 운행 시간·거리 capability는 이 실행에서도 예외 `a`로 실패했다. Console의 누적 주행거리(km)를 현재 운행거리(m)로 대체하지 않는다. Navi 연결은 확인됐지만 이 합성 음성 시험에서 활성 경로·도착은 관측하지 않았다.

## 에뮬레이터 실행 및 Virtual Console 활성화

검증용 `Doran_Verification` AVD는 공식 Pleos Connect 2.1.0 arm64, 전체 디스플레이 1920×1080이다. 최초 시험은 RAM 3072MB·CPU 2개였으며, 현재 검증 명령은 `-memory 4096 -cores 2`로 지정한다. 디스플레이 전체 크기와 OEM이 배치하는 앱 영역(관측된 1256×850)은 구분한다. 기존 PoC AVD와 원본 시스템 이미지는 보존했다. 메모리가 작은 개발 Mac에서는 release 축소 빌드(R8)와 에뮬레이터의 동시 실행을 피한다. 실제로 QEMU main loop와 CPU thread의 15초 무응답 뒤 HangDetector 종료가 관측됐다. 이 현상을 앱 크래시와 구분한다.

기본 이미지 시간대는 `America/Los_Angeles`였으며, UTC를 비교했을 때 호스트와 에뮬레이터는 초 단위 측정의 1초 범위 안에서 일치했다. 한국 시나리오의 화면 표시를 위해 새 검증 AVD에만 `settings put global auto_time_zone 0`과 `cmd alarm set-timezone Asia/Seoul`을 적용했다. 자동 시각 동기화(`auto_time=1`)와 실제 epoch는 유지했다. 기존 AVD나 시스템 이미지의 시간은 변경하지 않았다.

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

사람의 마이크 입력과 TTS 청취를 확인할 때는 보이는 창으로 실행하고 `-allow-host-audio`를 추가한다. 설치된 emulator의 도움말은 이 옵션이 없으면 호스트 입력을 0으로 채운다고 명시한다. `hw.audioInput=yes`만으로 실제 마이크 입력이 활성화됐다고 판단하지 않는다. 다음 명령에는 `-no-window`, `-no-audio`, `-audio none`을 사용하지 않는다. 호스트의 마이크 접근 권한과 실제 출력 청취는 별도로 확인한다.

```sh
emulator -avd Doran_Verification -port 5554 \
  -memory 4096 -cores 2 -no-snapshot -no-boot-anim -allow-host-audio
```
