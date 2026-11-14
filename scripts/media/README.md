# 기능 소개 보드

`render-feature-board.py`는 실제 앱 캡처로 2400×1600 PNG와 편집 가능한 SVG를 만든다. Python 3, Pillow, NumPy가 필요하다. 글꼴은 저장소의 [SUIT](../../docs/fonts.md)를 사용한다.

```sh
python3 scripts/media/render-feature-board.py \
  --car /absolute/path/car.png \
  --today /absolute/path/today.png \
  --compare /absolute/path/comparison.png \
  --captures-reviewed
```

개인정보를 확인한 캡처만 사용한다. 차량은 전체 화면을, 모바일은 한국어 개별 기록과 새 일기 부분을 확대한다. 좌표는 스크립트의 `records`와 `journal`에서 지정하며 원본 화면의 문구를 바꾸지 않는다. 다른 해상도의 캡처를 사용할 때는 해당 영역을 조정한다.

출력은 `docs/images/doran-features.png`와 `docs/images/doran-features.svg`다. 기존 파일을 덮어쓰므로 보관할 파일은 먼저 복사한다. 출력 뒤 문구·잘림·개인정보·원본 화면 일치 여부를 확인한다. 마이크·기기·네트워크에 접근하거나 소리를 재생하지 않는다.

제품 연출은 [Things](https://culturedcode.com/things/)의 기능 확대, [Day One](https://dayoneapp.com/)의 기기 간 연결, [Linear](https://linear.app/)의 제목과 화면 크기 대비를 참고했다. 해당 서비스의 이미지·영상은 사용하지 않는다.
