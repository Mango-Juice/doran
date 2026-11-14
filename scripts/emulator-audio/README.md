# 에뮬레이터 오디오 도구

설치된 Android Emulator의 인증 gRPC로 합성 음성을 입력하거나 출력 PCM을 수집합니다. 제품 APK를 수정하지 않으며 앱의 SDK 인식·응답은 별도로 확인해야 합니다.

## 환경

Python 3.13, [requirements.txt](requirements.txt)의 의존성, Android Emulator의 `lib/emulator_controller.proto`가 필요합니다. 선택적 호스트 재생에는 `ffplay`가 필요합니다. 도구는 localhost에만 연결하며 인증 토큰은 비공개 discovery 파일에서 읽습니다.

```sh
python3 -m venv "$DORAN_AUDIO_ENV"
"$DORAN_AUDIO_ENV/bin/python" -m pip install -r scripts/emulator-audio/requirements.txt
```

`DORAN_AUDIO_ENV`는 저장소 밖의 전용 환경 경로입니다. 녹음·패킷 로그·토큰은 공개 저장소 밖에 보관합니다. 명령은 저장소 루트에서 실행합니다.

## 합성 파일 입력

`inject_file.py`는 물리 마이크를 열거나 호스트에서 재생하지 않습니다. 16kHz·mono·signed 16-bit PCM 또는 같은 형식의 WAV를 공식 `injectAudio`에 전달합니다. 한 발화는 최대 30초이며 발화 사이에는 무음을 공급해 연결을 유지합니다.

```sh
python3 scripts/emulator-audio/inject_file.py \
  --discovery "$DORAN_DISCOVERY" \
  --input-root "$DORAN_AUDIO_INPUT" \
  --queue "$DORAN_AUDIO_QUEUE" \
  --output "$DORAN_AUDIO_OUTPUT" \
  --seconds 1800
```

입력·대기열 디렉터리는 미리 준비하고 출력은 새 경로를 지정합니다. 파일을 보낼 때 아래 JSON을 임시 파일에 쓴 뒤 대기열 디렉터리의 새 `.json` 이름으로 원자적으로 이동합니다. 앱이 듣기 준비를 마친 뒤 한 발화씩 전달합니다.

```json
{"request_id": "take1-u1", "audio_file": "solbit-01.pcm"}
```

`audio_file`은 입력 루트 안의 상대 경로입니다. 같은 요청 ID는 다시 보내지 않습니다. `SOURCE_FRAMED`는 파일을 패킷으로 구성했다는 뜻이며 음성 인식 성공은 아닙니다. 실제 앱의 인식 결과와 응답을 확인합니다.

## 출력과 기동

`stream_output.py`는 `--discovery`, 새 `--output`, `--seconds`를 받아 출력 PCM과 패킷 시각을 저장합니다. 기본값으로 호스트에서 소리가 나지 않으며 `--play`가 있을 때만 재생합니다. 재생용 gain은 원본 PCM에 적용하지 않습니다.

`launch.py`는 기존 AVD를 `QEMU_AUDIO_DRV=none`, 48kHz, localhost 인증 gRPC로 기동합니다. 다른 VM과 네이티브 빌드가 실행 중이면 시작하지 않습니다. 앱·재생·녹음은 자동으로 시작하지 않습니다. `--help`로 AVD·SDK·출력 경로를 지정할 수 있습니다.

연결 종료 시 에뮬레이터가 불안정해진 관측이 있으므로 발화마다 gRPC를 열고 닫지 않습니다. 촬영과 앱 대화를 종료한 뒤 VM을 정상 종료하고 입력 도구를 정리합니다. `inject_microphone.py`는 별도의 개발용 수음 도구이며 합성 파일 시연에는 사용하지 않습니다.

## 장치 없이 확인

```sh
python3 scripts/emulator-audio/inject_file.py --self-test
python3 scripts/emulator-audio/stream_output.py --self-test
python3 scripts/emulator-audio/launch.py --help
```

이 명령은 네트워크·녹음·재생·VM 기동 없이 로직과 옵션을 확인합니다. 실제 음질 검증을 대신하지 않습니다. 녹화 음원의 시간 정렬과 검증 한계는 [오디오 안내](../../docs/emulator-audio.md)를 참고하세요.
