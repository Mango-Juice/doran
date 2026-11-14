# 에뮬레이터 오디오 재개 도구

이 디렉터리는 빌드 정리와 독립적으로 보존하는 소스다. 사용자가 준비만 요청한 이후 이 소스를 보존하는 단계에서는 녹음·재생·VM 실행을 시작하지 않았다. 앞선 실제 진단과 구분하며 운영 범위와 증거는 [오디오 안내](../../docs/emulator-audio.md)를 따른다.

## 환경

- Python 3.13.2에서 확인한 직접 의존성을 `requirements.txt`에 고정했다: `grpcio==1.71.0`, `protobuf==5.29.4`.
- 설치된 Android Emulator SDK의 `emulator/lib/emulator_controller.proto`가 필요하다. SDK 파일을 저장소에 재배포하지 않는다. 다른 경로는 `--proto`로 지정한다.
- 호스트 재생에는 외부 `ffplay`, 실제 마이크 수음에는 AVFoundation을 지원하는 macOS `ffmpeg`가 필요하다. 현재 기본 실행 경로는 `/opt/homebrew/bin/ffplay`와 `/opt/homebrew/bin/ffmpeg`다. pip 의존성만 설치해도 이 실행 파일들이 설치되는 것은 아니다.
- 에뮬레이터 기동·포트·인증 discovery 파일 준비는 별도 작업이다. 오디오 도구 자체는 VM을 기동하거나 시스템 음량·출력 장치를 바꾸지 않는다.

필요할 때 저장소 밖의 가상 환경에 의존성을 설치한다. 이 준비 작업에서는 새 설치를 실행하지 않았다.

```sh
python3 -m venv "$DORAN_AUDIO_ENV"
"$DORAN_AUDIO_ENV/bin/python" -m pip install -r scripts/emulator-audio/requirements.txt
```

`DORAN_AUDIO_ENV`는 사용자가 정한 저장소 밖 경로다. 토큰·discovery·음원·실행 보고서는 공개 저장소에 두지 않는다.

## 녹음 없이 확인

```sh
python3 scripts/emulator-audio/stream_output.py --self-test
python3 scripts/emulator-audio/inject_microphone.py --self-test
python3 scripts/emulator-audio/launch.py --help
```

이 모드는 설치된 proto를 읽고 메모리 내 PCM·스키마·타이밍 로직만 확인한다. 네트워크 연결·마이크·재생·VM 제어를 하지 않는다. 실제 오디오 검증 결과로 합산하지 않는다.

## 이후 명시적인 시작 지시가 있을 때

출력 도구는 `--play`가 없으면 호스트에서 재생하지 않는다. 단, 정상 캡처 실행은 guest 출력 원본을 저장하므로 준비만 할 때는 `--self-test`만 사용한다. 입력 도구는 `--record-microphone`를 명시하기 전에는 수음하지 않는다. 장치 이름·출력 경로·실행 시간과 사용자 시작 의사를 확인한 뒤 짧은 파일럿부터 진행한다.

접속은 `127.0.0.1`에 고정된다. 인증은 `--discovery` 또는 `--token-file`의 비공개 파일로 제공하며 토큰 값을 명령행·문서·콘솔에 붙여 넣지 않는다. 재생·수음에는 새 `--output` 디렉터리를 사용한다. 원본을 덮어쓰는 재실행은 허용하지 않는다.

긴 시험은 명시적으로 `--seconds 900`처럼 지정한다. 상한은 1,800초이며 시간 상한 지원 자체가 장기 안정성이나 사람의 연속 20턴 통과를 뜻하지 않는다.

입력의 기본 `--input-mode blocking`은 proto mode 0이다. 재개 파일럿에는 필요에 따라 `--input-mode realtime --seconds 30 --record-microphone`를 명시할 수 있다. realtime은 mode 1이며 늦은 입력이 덮어써질 수 있으므로 무손실 전달로 간주하지 않는다. 두 모드 모두 실제 SDK 인식과의 통합은 아직 확인하지 않았다.

`launch.py`를 옵션 없이 실행하면 VM을 기동하므로 준비만 할 때 실행하지 않는다. 재개 승인 뒤에는 기존 `Doran_Verification` AVD와 SDK Emulator 37.2.13을 확인하고 실행한다. 기본 프로필은 4096MB·4 core·host GPU·headless, `QEMU_AUDIO_DRV=none`·DAC 48000Hz다. 기존 `hw.audioInput=yes`·`hw.audioOutput=yes` 설정을 읽기 검사하며 호스트 수음은 별도 opt-in이다. 설정 파일·사용자 데이터를 초기화하거나 앱을 자동 실행하지 않는다.

launcher는 다른 VM·네이티브 빌드와 포트 점유를 확인하고 실제 gRPC listener가 loopback과 자신의 프로세스 그룹에 속하는지 검사한다. 새 비공개 상태 디렉터리의 `state.json`과 fresh discovery 경로·stop 파일 경로만 출력한다. token 값은 출력하지 않는다. foreground를 유지하며 Ctrl-C 또는 해당 stop 파일로 자신이 만든 heartbeat·forward·VM을 정리한다. 종료가 지연되면 그 상태를 남기며 다른 프로세스를 광역 종료하지 않는다. 이 새 launcher의 실제 기동·정리는 아직 미검증이다.

## 보존한 소스 해시

| 파일 | SHA-256 |
|---|---|
| `stream_output.py` | `b69a87afc2336e221e3bfd62d79b367f7adf8e9eb5699d43218443752e91a07e` |
| `inject_microphone.py` | `9736bcbcacf34cc8263f8863996f358bf6178580823c3585373d1dd199cdb269` |
| `launch.py` | `caff08ae65bbdd57ab7018275b2bdc154fc94449d60cb1488949b38099cba228` |

두 오디오 helper는 작성 담당자의 최종 원본과 바이트가 같다. 공개 위치에서 각각 `--self-test`를 실행해 통과했고 launcher는 `--help`와 문법 검사만 수행했다. 실제 오디오·VM 실행 결과는 아니다.
