# 도란 사용 예시 보드

실제 앱의 색상·서체·화면 구조를 참고한 일상 사용 예시다. 퇴근길에 회의 이야기를 나누고, 집에서 그날의 기록을 다시 읽는 흐름을 보여준다. 실제 실행 캡처나 검증 자료가 아니며 보드에 `사용 예시`를 표시한다. 앱 데이터는 변경하지 않는다.

Python 3, Pillow, NumPy와 저장소의 [SUIT 글꼴](../../docs/fonts.md)이 필요하다.

```sh
python3 scripts/media/render-feature-board.py
```

출력은 `docs/images/doran-features.png`(2400×1600)와 `docs/images/doran-features.svg`다. 문구와 예시 UI는 `vehicle_example`, `mobile_example`, 배치는 `board`와 `svg_for_board`에서 편집한다. 기존 파일을 덮어쓰므로 보관할 파일은 먼저 복사한다. SVG는 제목과 기기 레이어를 개별 편집할 수 있다. 렌더 후 글자 잘림·겹침과 차량 대화·모바일 일기의 내용 일치를 확인한다.

제품 연출은 [Things](https://culturedcode.com/things/)의 기능 확대, [Day One](https://dayoneapp.com/)의 기기 간 연결, [Linear](https://linear.app/)의 제목과 화면 크기 대비를 참고했다. 해당 서비스의 이미지·영상은 사용하지 않는다. 제작 과정에서 마이크·기기·네트워크에 접근하거나 소리를 재생하지 않는다.
