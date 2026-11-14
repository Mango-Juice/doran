# SUIT 글꼴 출처와 배포

도란의 한국어 UI 글꼴은 SUNN의 **SUIT Variable**이다. 공식 저장소는 [sun-typeface/SUIT](https://github.com/sun-typeface/SUIT), 제작자 안내는 [Sun — SUIT](https://sun.fo/suit/)다.

## 고정한 원본

- 공식 release: [v2.0.5](https://github.com/sun-typeface/SUIT/releases/tag/v2.0.5)
- 해당 tag commit: `55118d981336d8fce005eb62888c12c0568ef7b0`
- 원본: [SUIT-Variable-ttf.zip](https://github.com/sun-typeface/SUIT/releases/download/v2.0.5/SUIT-Variable-ttf.zip)
- ZIP SHA-256: `ba6ae5cce0190698ae7b830886d98e34557b36f688a4f06c833efb267f5157c1`
- TTF SHA-256: `12e1fea375d27869a686d7432c1e6a38154d394994f5d82ace24f36a7db68333`
- TTF 크기: 1,585,020 bytes
- 동봉 LICENSE SHA-256: `30d101ee3c35ef629d622d933d261a27b2f60223e0a3b0f36791e569c2b9daa9`

2026-11-14에 공식 release에서 내려받았다. 배포 tag는 v2.0.5이지만 폰트 내부 version 문자열은 `Version 2.040;Glyphs 3.2.3 (3260)`이며 둘을 같은 값으로 바꾸어 기록하지 않는다. TTF의 `wght` 축은 100–900, 원본 기본값은 100이다. 앱은 400·500·600을 명시해 사용한다. 기본 축을 그대로 읽어 Thin으로 표시하지 않도록 실제 렌더링에서 확인한다.

Variable 한 파일을 선택했다. 원본 Regular·Medium·SemiBold 정적 TTF 세 파일 합계는 1,778,852 bytes이므로, 같은 세 굵기를 지원하는 variable 원본이 더 작다. 글리프 삭제·서브셋·형식 변환·폰트 내부 이름 변경을 하지 않았다.

## 앱에 포함하는 위치

| 플랫폼 | 폰트 | 동봉 라이선스 |
|---|---|---|
| 차량 | `apps/car/app/src/main/res/font/suit_variable.ttf` | `apps/car/app/src/main/assets/fonts/SUIT-OFL.txt` |
| 모바일 | `apps/mobile/assets/fonts/SUIT-Variable.ttf` | `apps/mobile/assets/fonts/SUIT-OFL.txt` |

두 TTF와 두 라이선스 사본은 각각 같은 원본 바이트다. Android 리소스 파일명만 소문자/밑줄 규칙에 맞췄으며 폰트 내용은 수정하지 않았다. Flutter는 해당 라이선스를 assets 및 앱 라이선스 화면에 등록한다. 폰트 파일만 추가한 상태와 각 앱 테마에 연결하고 빌드한 상태는 구분한다.

## 이용 조건

Copyright (c) 2022, SUNN (http://sun.fo/suit), with Reserved Font Name SUIT.

공식 배포물의 [SIL Open Font License 1.1 원문](../apps/mobile/assets/fonts/SUIT-OFL.txt)을 그대로 동봉한다. 이 라이선스는 도란 자체 코드나 다른 자산의 이용허락을 변경하지 않는다. 별도 수정 폰트를 배포할 때는 Reserved Font Name 조건을 다시 확인해야 한다.
