# 의존성과 제3자 고지

2026-11-14에 고정된 선언 파일과 실제 내려받은 배포물의 `LICENSE`·`NOTICE`·Maven POM을 확인했다. 이 문서는 확인한 의존성과 고지 위치를 설명하며, 이름이 같다는 이유로 미확인 배포물에 라이선스를 추정 적용하지 않는다.

## 배포물에 포함한 고지

- 차량: [원문 고지 모음](license-notices.txt)을 `apps/car/app/src/main/assets/licenses.txt`에도 복사했다. 아래의 152개 해석된 Maven 의존성에서 확인한 9개 출처의 고유 원문 3개를 보존한다. Apache-2.0 원문, JNA의 선택 라이선스 안내, OkHttp의 public suffix 고지를 포함한다. 원문별 출처와 SHA-256을 함께 기록했다.
- 모바일: Flutter가 생성한 `assets/flutter_assets/NOTICES.Z`가 Android release APK에 들어 있으며, 주요 직접 의존성 6개 모두의 이름과 고지가 포함됨을 압축 해제하여 확인했다. iOS 시뮬레이터 빌드에도 `App.framework/flutter_assets/NOTICES.Z`가 있다. Flutter 엔진은 여러 제3자 구성요소를 포함하므로 엔진 전체를 단일 BSD 라이선스로 표기하지 않는다.
- 라이선스 요약과 링크는 배포물의 원문 고지를 대체하지 않는다. APK를 다시 빌드할 때 고지 파일을 제거하지 않고, 의존성이 바뀌면 이 목록과 고지를 갱신한다.

## 차량 앱

선언 기준은 [Gradle 설정](../apps/car/app/build.gradle.kts)이다. Kotlin 2.0.0, AGP 8.8.2, Compose BOM 2024.04.01을 사용한다. 라이브러리가 요청한 버전은 Gradle 해석에서 상향될 수 있으므로 아래 펼침 목록은 실제 release의 `sdkDependencies.txt`를 기준으로 작성했다. Gradle dependency lock 파일은 현재 없으며, 선언된 고정 버전과 실제 해석 결과를 구별한다.

| 주요 직접 구성 | 선언 버전 | 라이선스 근거 |
|---|---|---|
| AndroidX Core / Activity | 1.16.0 / 1.10.1 | 해당 버전 POM의 Apache-2.0 |
| AndroidX Lifecycle | 2.8.7 | 해당 버전 POM의 Apache-2.0 |
| Compose UI / Material3 | BOM 2024.04.01 | 해석된 AndroidX POM의 Apache-2.0; 실제 버전은 아래 표 |
| Room runtime / ktx | 2.6.1 | 해당 버전 POM의 Apache-2.0 |
| Kotlin coroutines | 1.9.0 | 해당 버전 POM의 Apache-2.0 |
| OkHttp | 4.12.0 | 해당 버전 POM의 Apache-2.0; 별도 public suffix NOTICE 포함 |
| Room compiler — 빌드 시 사용 | 2.6.1 | POM에 Apache-2.0 및 ANTLR BSD 선언; 앱 runtime 목록과 구분 |
| JUnit — 시험 시 사용 | 4.13.2 | POM의 EPL-1.0 |
| JSON-java — 시험 시 사용 | 20240303 | 해당 버전 POM의 Public Domain 선언 |

Pleos SDK 5개 직접 의존성은 `FusedLocation 1.1.3`, `SpeechToText 3.3.1`, `TextToSpeech 3.3.1`, `Vehicle 2.3.2`, `NaviHelper 3.0.5`이다. 7개 전이 Pleos 구성요소를 포함한 12개 POM에 라이선스 선언이 없었다. 이들을 일반 오픈소스로 재분류하지 않으며, 이용·앱 배포 조건은 Pleos의 별도 SDK 약관과 공모전 안내를 따른다. [공식 SDK 개발 문서](https://document.pleos.ai/)

SDK 원본 AAR/JAR·에뮬레이터 이미지를 저장소에 별도 복사해 배포하지 않는다. 빌드 중 공식 Maven 저장소에서 받으며, 차량 APK에는 사용한 SDK 코드가 포함될 수 있다. 원본 SDK의 미포함을 APK 내부 코드의 미포함으로 해석하지 않는다.

JNA 5.13.0의 원문은 Apache-2.0 또는 LGPL을 선택할 수 있다고 명시한다. 도란 배포 고지는 Apache-2.0 경로로 제공하며 원래 선택 안내도 보존한다. [JNA 5.13.0 LICENSE](https://github.com/java-native-access/jna/blob/5.13.0/LICENSE)

<details>
<summary>차량 release의 해석된 Maven 구성요소 152개</summary>

표의 링크는 해당 버전 POM이다. `POM 선언 없음`은 라이선스가 없다는 뜻이 아니다. `listenablefuture 1.0`은 상위 [guava-parent 26.0-android POM](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom)의 Apache-2.0 선언을 확인했다. 전체 152개 중 Apache-2.0 139개, JNA 선택 라이선스 1개, 별도 조건 확인 대상 Pleos 12개다. 이 목록만으로 Pleos 약관을 포함한 모든 재배포 권리 검토가 끝났다고 주장하지 않는다.

| 구성요소 | 실제 해석 버전 | 확인한 라이선스 선언 |
|---|---|---|
| [ai.pleos.playground:FusedLocation](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/FusedLocation/1.1.3/FusedLocation-1.1.3.pom) | 1.1.3 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:NaviHelper](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5.pom) | 3.0.5 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:SpeechToText](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/SpeechToText/3.3.1/SpeechToText-3.3.1.pom) | 3.3.1 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:TextToSpeech](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/TextToSpeech/3.3.1/TextToSpeech-3.3.1.pom) | 3.3.1 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:Vehicle](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2.pom) | 2.3.2 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:fused-location-aidl](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/fused-location-aidl/0.0.5/fused-location-aidl-0.0.5.pom) | 0.0.5 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:fused-location-data](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/fused-location-data/0.0.3/fused-location-data-0.0.3.pom) | 0.0.3 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:navi-aidl](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/navi-aidl/3.0.1/navi-aidl-3.0.1.pom) | 3.0.1 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:navi-data](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/navi-data/3.0.4/navi-data-3.0.4.pom) | 3.0.4 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:vehicle-aidl](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/vehicle-aidl/1.1.1/vehicle-aidl-1.1.1.pom) | 1.1.1 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground:vehicle-data](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/vehicle-data/1.1.11/vehicle-data-1.1.11.pom) | 1.1.11 | 별도 SDK 조건; POM 선언 없음 |
| [ai.pleos.playground.security:universal-access](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/security/universal-access/0.6.0/universal-access-0.6.0.pom) | 0.6.0 | 별도 SDK 조건; POM 선언 없음 |
| [androidx.activity:activity](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.10.1/activity-1.10.1.pom) | 1.10.1 | Apache-2.0 |
| [androidx.activity:activity-compose](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.10.1/activity-compose-1.10.1.pom) | 1.10.1 | Apache-2.0 |
| [androidx.activity:activity-ktx](https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.10.1/activity-ktx-1.10.1.pom) | 1.10.1 | Apache-2.0 |
| [androidx.annotation:annotation](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.8.1/annotation-1.8.1.pom) | 1.8.1 | Apache-2.0 |
| [androidx.annotation:annotation-experimental](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.pom) | 1.4.1 | Apache-2.0 |
| [androidx.annotation:annotation-jvm](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.pom) | 1.8.1 | Apache-2.0 |
| [androidx.appcompat:appcompat](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.6.1/appcompat-1.6.1.pom) | 1.6.1 | Apache-2.0 |
| [androidx.appcompat:appcompat-resources](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat-resources/1.6.1/appcompat-resources-1.6.1.pom) | 1.6.1 | Apache-2.0 |
| [androidx.arch.core:core-common](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.pom) | 2.2.0 | Apache-2.0 |
| [androidx.arch.core:core-runtime](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.pom) | 2.2.0 | Apache-2.0 |
| [androidx.autofill:autofill](https://dl.google.com/dl/android/maven2/androidx/autofill/autofill/1.0.0/autofill-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.cardview:cardview](https://dl.google.com/dl/android/maven2/androidx/cardview/cardview/1.0.0/cardview-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.collection:collection](https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.4.2/collection-1.4.2.pom) | 1.4.2 | Apache-2.0 |
| [androidx.collection:collection-jvm](https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.4.2/collection-jvm-1.4.2.pom) | 1.4.2 | Apache-2.0 |
| [androidx.collection:collection-ktx](https://dl.google.com/dl/android/maven2/androidx/collection/collection-ktx/1.4.2/collection-ktx-1.4.2.pom) | 1.4.2 | Apache-2.0 |
| [androidx.compose:compose-bom](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.04.01/compose-bom-2024.04.01.pom) | 2024.04.01 | Apache-2.0 |
| [androidx.compose.animation:animation](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation/1.6.6/animation-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.animation:animation-android](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.6.6/animation-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.animation:animation-core](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core/1.6.6/animation-core-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.animation:animation-core-android](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.6.6/animation-core-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.foundation:foundation](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.6.6/foundation-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.foundation:foundation-android](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.6.6/foundation-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.foundation:foundation-layout](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout/1.6.6/foundation-layout-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.foundation:foundation-layout-android](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.6.6/foundation-layout-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.material:material-icons-core](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core/1.6.6/material-icons-core-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.material:material-icons-core-android](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core-android/1.6.6/material-icons-core-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.material:material-ripple](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple/1.6.6/material-ripple-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.material:material-ripple-android](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.6.6/material-ripple-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.material3:material3](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.2.1/material3-1.2.1.pom) | 1.2.1 | Apache-2.0 |
| [androidx.compose.material3:material3-android](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.2.1/material3-android-1.2.1.pom) | 1.2.1 | Apache-2.0 |
| [androidx.compose.runtime:runtime](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime/1.7.1/runtime-1.7.1.pom) | 1.7.1 | Apache-2.0 |
| [androidx.compose.runtime:runtime-android](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.7.1/runtime-android-1.7.1.pom) | 1.7.1 | Apache-2.0 |
| [androidx.compose.runtime:runtime-saveable](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable/1.7.1/runtime-saveable-1.7.1.pom) | 1.7.1 | Apache-2.0 |
| [androidx.compose.runtime:runtime-saveable-android](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.7.1/runtime-saveable-android-1.7.1.pom) | 1.7.1 | Apache-2.0 |
| [androidx.compose.ui:ui](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.6.6/ui-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.6.6/ui-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-geometry](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry/1.6.6/ui-geometry-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-geometry-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry-android/1.6.6/ui-geometry-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-graphics](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics/1.6.6/ui-graphics-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-graphics-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.6.6/ui-graphics-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-text](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text/1.6.6/ui-text-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-text-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.6.6/ui-text-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-tooling-preview](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-tooling-preview/1.6.6/ui-tooling-preview-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-tooling-preview-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-tooling-preview-android/1.6.6/ui-tooling-preview-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-unit](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit/1.6.6/ui-unit-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-unit-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.6.6/ui-unit-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-util](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util/1.6.6/ui-util-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.compose.ui:ui-util-android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util-android/1.6.6/ui-util-android-1.6.6.pom) | 1.6.6 | Apache-2.0 |
| [androidx.concurrent:concurrent-futures](https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.constraintlayout:constraintlayout](https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout/2.0.1/constraintlayout-2.0.1.pom) | 2.0.1 | Apache-2.0 |
| [androidx.constraintlayout:constraintlayout-solver](https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout-solver/2.0.1/constraintlayout-solver-2.0.1.pom) | 2.0.1 | Apache-2.0 |
| [androidx.coordinatorlayout:coordinatorlayout](https://dl.google.com/dl/android/maven2/androidx/coordinatorlayout/coordinatorlayout/1.1.0/coordinatorlayout-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.core:core](https://dl.google.com/dl/android/maven2/androidx/core/core/1.16.0/core-1.16.0.pom) | 1.16.0 | Apache-2.0 |
| [androidx.core:core-ktx](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.16.0/core-ktx-1.16.0.pom) | 1.16.0 | Apache-2.0 |
| [androidx.core:core-viewtree](https://dl.google.com/dl/android/maven2/androidx/core/core-viewtree/1.0.0/core-viewtree-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.cursoradapter:cursoradapter](https://dl.google.com/dl/android/maven2/androidx/cursoradapter/cursoradapter/1.0.0/cursoradapter-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.customview:customview](https://dl.google.com/dl/android/maven2/androidx/customview/customview/1.1.0/customview-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.customview:customview-poolingcontainer](https://dl.google.com/dl/android/maven2/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.documentfile:documentfile](https://dl.google.com/dl/android/maven2/androidx/documentfile/documentfile/1.0.0/documentfile-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.drawerlayout:drawerlayout](https://dl.google.com/dl/android/maven2/androidx/drawerlayout/drawerlayout/1.1.1/drawerlayout-1.1.1.pom) | 1.1.1 | Apache-2.0 |
| [androidx.dynamicanimation:dynamicanimation](https://dl.google.com/dl/android/maven2/androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.emoji2:emoji2](https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2/1.3.0/emoji2-1.3.0.pom) | 1.3.0 | Apache-2.0 |
| [androidx.emoji2:emoji2-views-helper](https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2-views-helper/1.3.0/emoji2-views-helper-1.3.0.pom) | 1.3.0 | Apache-2.0 |
| [androidx.fragment:fragment](https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/1.5.1/fragment-1.5.1.pom) | 1.5.1 | Apache-2.0 |
| [androidx.interpolator:interpolator](https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.legacy:legacy-support-core-utils](https://dl.google.com/dl/android/maven2/androidx/legacy/legacy-support-core-utils/1.0.0/legacy-support-core-utils-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-common](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.8.7/lifecycle-common-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-common-java8](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-java8/2.8.7/lifecycle-common-java8-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-common-jvm](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.8.7/lifecycle-common-jvm-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-livedata](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata/2.8.7/lifecycle-livedata-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-livedata-core](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.8.7/lifecycle-livedata-core-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-livedata-core-ktx](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core-ktx/2.8.7/lifecycle-livedata-core-ktx-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-process](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.8.7/lifecycle-process-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.8.7/lifecycle-runtime-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime-android](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.8.7/lifecycle-runtime-android-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime-compose](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.8.7/lifecycle-runtime-compose-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime-compose-android](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.8.7/lifecycle-runtime-compose-android-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime-ktx](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/2.8.7/lifecycle-runtime-ktx-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-runtime-ktx-android](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx-android/2.8.7/lifecycle-runtime-ktx-android-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.8.7/lifecycle-viewmodel-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel-android](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-android/2.8.7/lifecycle-viewmodel-android-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel-compose](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose/2.8.7/lifecycle-viewmodel-compose-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel-compose-android](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose-android/2.8.7/lifecycle-viewmodel-compose-android-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel-ktx](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.8.7/lifecycle-viewmodel-ktx-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.lifecycle:lifecycle-viewmodel-savedstate](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.8.7/lifecycle-viewmodel-savedstate-2.8.7.pom) | 2.8.7 | Apache-2.0 |
| [androidx.loader:loader](https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.localbroadcastmanager:localbroadcastmanager](https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.0.0/localbroadcastmanager-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.print:print](https://dl.google.com/dl/android/maven2/androidx/print/print/1.0.0/print-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.profileinstaller:profileinstaller](https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0.pom) | 1.4.0 | Apache-2.0 |
| [androidx.recyclerview:recyclerview](https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/1.1.0/recyclerview-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.resourceinspection:resourceinspection-annotation](https://dl.google.com/dl/android/maven2/androidx/resourceinspection/resourceinspection-annotation/1.0.1/resourceinspection-annotation-1.0.1.pom) | 1.0.1 | Apache-2.0 |
| [androidx.room:room-common](https://dl.google.com/dl/android/maven2/androidx/room/room-common/2.6.1/room-common-2.6.1.pom) | 2.6.1 | Apache-2.0 |
| [androidx.room:room-ktx](https://dl.google.com/dl/android/maven2/androidx/room/room-ktx/2.6.1/room-ktx-2.6.1.pom) | 2.6.1 | Apache-2.0 |
| [androidx.room:room-runtime](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.6.1/room-runtime-2.6.1.pom) | 2.6.1 | Apache-2.0 |
| [androidx.savedstate:savedstate](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.pom) | 1.2.1 | Apache-2.0 |
| [androidx.savedstate:savedstate-ktx](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-ktx/1.2.1/savedstate-ktx-1.2.1.pom) | 1.2.1 | Apache-2.0 |
| [androidx.security:security-crypto](https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/1.1.0-alpha06/security-crypto-1.1.0-alpha06.pom) | 1.1.0-alpha06 | Apache-2.0 |
| [androidx.security:security-crypto-ktx](https://dl.google.com/dl/android/maven2/androidx/security/security-crypto-ktx/1.1.0-alpha06/security-crypto-ktx-1.1.0-alpha06.pom) | 1.1.0-alpha06 | Apache-2.0 |
| [androidx.sqlite:sqlite](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite/2.4.0/sqlite-2.4.0.pom) | 2.4.0 | Apache-2.0 |
| [androidx.sqlite:sqlite-framework](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework/2.4.0/sqlite-framework-2.4.0.pom) | 2.4.0 | Apache-2.0 |
| [androidx.startup:startup-runtime](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1.pom) | 1.1.1 | Apache-2.0 |
| [androidx.tracing:tracing](https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.2.0/tracing-1.2.0.pom) | 1.2.0 | Apache-2.0 |
| [androidx.transition:transition](https://dl.google.com/dl/android/maven2/androidx/transition/transition/1.2.0/transition-1.2.0.pom) | 1.2.0 | Apache-2.0 |
| [androidx.vectordrawable:vectordrawable](https://dl.google.com/dl/android/maven2/androidx/vectordrawable/vectordrawable/1.1.0/vectordrawable-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.vectordrawable:vectordrawable-animated](https://dl.google.com/dl/android/maven2/androidx/vectordrawable/vectordrawable-animated/1.1.0/vectordrawable-animated-1.1.0.pom) | 1.1.0 | Apache-2.0 |
| [androidx.versionedparcelable:versionedparcelable](https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.pom) | 1.1.1 | Apache-2.0 |
| [androidx.viewpager:viewpager](https://dl.google.com/dl/android/maven2/androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [androidx.viewpager2:viewpager2](https://dl.google.com/dl/android/maven2/androidx/viewpager2/viewpager2/1.0.0/viewpager2-1.0.0.pom) | 1.0.0 | Apache-2.0 |
| [com.google.android.material:material](https://repo.maven.apache.org/maven2/com/google/android/material/material/1.10.0/material-1.10.0.pom) | 1.10.0 | Apache-2.0 |
| [com.google.code.findbugs:jsr305](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) | 3.0.2 | Apache-2.0 |
| [com.google.code.gson:gson](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.pom) | 2.10.1 | Apache-2.0 |
| [com.google.crypto.tink:tink-android](https://repo.maven.apache.org/maven2/com/google/crypto/tink/tink-android/1.8.0/tink-android-1.8.0.pom) | 1.8.0 | Apache-2.0 |
| [com.google.dagger:dagger](https://repo.maven.apache.org/maven2/com/google/dagger/dagger/2.51.1/dagger-2.51.1.pom) | 2.51.1 | Apache-2.0 |
| [com.google.dagger:dagger-lint-aar](https://repo.maven.apache.org/maven2/com/google/dagger/dagger-lint-aar/2.51.1/dagger-lint-aar-2.51.1.pom) | 2.51.1 | Apache-2.0 |
| [com.google.dagger:hilt-android](https://repo.maven.apache.org/maven2/com/google/dagger/hilt-android/2.51.1/hilt-android-2.51.1.pom) | 2.51.1 | Apache-2.0 |
| [com.google.dagger:hilt-core](https://repo.maven.apache.org/maven2/com/google/dagger/hilt-core/2.51.1/hilt-core-2.51.1.pom) | 2.51.1 | Apache-2.0 |
| [com.google.errorprone:error_prone_annotations](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.15.0/error_prone_annotations-2.15.0.pom) | 2.15.0 | Apache-2.0 |
| [com.google.guava:listenablefuture](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.pom) | 1.0 | Apache-2.0 |
| [com.squareup.okhttp3:okhttp](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.pom) | 4.12.0 | Apache-2.0 |
| [com.squareup.okio:okio](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/3.6.0/okio-3.6.0.pom) | 3.6.0 | Apache-2.0 |
| [com.squareup.okio:okio-jvm](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.pom) | 3.6.0 | Apache-2.0 |
| [javax.inject:javax.inject](https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom) | 1 | Apache-2.0 |
| [net.java.dev.jna:jna](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.pom) | 5.13.0 | Apache-2.0 OR LGPL-2.1-or-later |
| [org.jetbrains:annotations](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) | 23.0.0 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-bom](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-bom/1.8.22/kotlin-bom-1.8.22.pom) | 1.8.22 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-reflect](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/1.9.25/kotlin-reflect-1.9.25.pom) | 1.9.25 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-stdlib](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.0/kotlin-stdlib-2.0.0.pom) | 2.0.0 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-stdlib-common](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/2.0.0/kotlin-stdlib-common-2.0.0.pom) | 2.0.0 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-stdlib-jdk7](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.9.10/kotlin-stdlib-jdk7-1.9.10.pom) | 1.9.10 | Apache-2.0 |
| [org.jetbrains.kotlin:kotlin-stdlib-jdk8](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.9.10/kotlin-stdlib-jdk8-1.9.10.pom) | 1.9.10 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-coroutines-android](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.9.0/kotlinx-coroutines-android-1.9.0.pom) | 1.9.0 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-coroutines-bom](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/1.9.0/kotlinx-coroutines-bom-1.9.0.pom) | 1.9.0 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-coroutines-core](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.pom) | 1.9.0 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.pom) | 1.9.0 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-serialization-bom](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-bom/1.6.3/kotlinx-serialization-bom-1.6.3.pom) | 1.6.3 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-serialization-core](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core/1.6.3/kotlinx-serialization-core-1.6.3.pom) | 1.6.3 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-serialization-core-jvm](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/kotlinx-serialization-core-jvm-1.6.3.pom) | 1.6.3 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-serialization-json](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.6.3/kotlinx-serialization-json-1.6.3.pom) | 1.6.3 | Apache-2.0 |
| [org.jetbrains.kotlinx:kotlinx-serialization-json-jvm](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/kotlinx-serialization-json-jvm-1.6.3.pom) | 1.6.3 | Apache-2.0 |
| [org.jspecify:jspecify](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.pom) | 1.0.0 | Apache-2.0 |

</details>

## 모바일 앱

[pubspec.yaml](../apps/mobile/pubspec.yaml)의 직접 의존성은 아래 표에서 `직접`으로 표시했다. [pubspec.lock](../apps/mobile/pubspec.lock)이 hosted 패키지 104개의 버전과 다운로드 SHA-256을 고정한다. 아래 라이선스는 그 버전의 로컬 설치 배포물에 포함된 원문을 확인했고, 패키지명은 해당 버전의 공식 pub.dev 라이선스 페이지로 연결한다. 개발·시험 의존성 및 이번에 빌드하지 않은 플랫폼용 전이 의존성도 잠금 파일 전체 목록에 포함된다.

Flutter SDK 구성 5개는 `flutter`, `flutter_localizations`, `flutter_test`, `flutter_web_plugins`, `sky_engine`이다. SDK 버전은 Flutter 3.50.0, commit `ab2912035e160d9cad6306df307576da6128ebdb`이며, 잠금 파일의 SDK 패키지 버전 `0.0.0`을 Flutter 배포 버전으로 사용하지 않는다. Flutter 프레임워크 자체는 BSD-3-Clause, 엔진의 결합 고지는 여러 라이선스다. [고정 Flutter 커밋의 LICENSE](https://github.com/flutter/flutter/blob/ab2912035e160d9cad6306df307576da6128ebdb/LICENSE)

<details>
<summary>모바일 hosted 의존성 104개</summary>

| 패키지 | 고정 버전 | 배포물의 라이선스 | 관계 |
|---|---|---|---|
| [app_links](https://pub.dev/packages/app_links/versions/7.2.2/license) | 7.2.2 | Apache-2.0 | 전이 |
| [app_links_linux](https://pub.dev/packages/app_links_linux/versions/1.0.5/license) | 1.0.5 | Apache-2.0 | 전이 |
| [app_links_platform_interface](https://pub.dev/packages/app_links_platform_interface/versions/2.0.4/license) | 2.0.4 | Apache-2.0 | 전이 |
| [app_links_web](https://pub.dev/packages/app_links_web/versions/1.0.5/license) | 1.0.5 | Apache-2.0 | 전이 |
| [args](https://pub.dev/packages/args/versions/2.7.0/license) | 2.7.0 | BSD-3-Clause | 전이 |
| [async](https://pub.dev/packages/async/versions/2.13.1/license) | 2.13.1 | BSD-3-Clause | 전이 |
| [boolean_selector](https://pub.dev/packages/boolean_selector/versions/2.1.2/license) | 2.1.2 | BSD-3-Clause | 전이 |
| [characters](https://pub.dev/packages/characters/versions/1.4.1/license) | 1.4.1 | BSD-3-Clause | 전이 |
| [clock](https://pub.dev/packages/clock/versions/1.1.3/license) | 1.1.3 | Apache-2.0 | 전이 |
| [code_assets](https://pub.dev/packages/code_assets/versions/2.2.0/license) | 2.2.0 | BSD-3-Clause | 전이 |
| [collection](https://pub.dev/packages/collection/versions/1.19.1/license) | 1.19.1 | BSD-3-Clause | 전이 |
| [convert](https://pub.dev/packages/convert/versions/3.1.2/license) | 3.1.2 | BSD-3-Clause | 전이 |
| [crypto](https://pub.dev/packages/crypto/versions/3.0.8/license) | 3.0.8 | BSD-3-Clause | 전이 |
| [cupertino_ui](https://pub.dev/packages/cupertino_ui/versions/1.1.6/license) | 1.1.6 | BSD-3-Clause | 전이 |
| [dart_jsonwebtoken](https://pub.dev/packages/dart_jsonwebtoken/versions/3.4.1/license) | 3.4.1 | MIT | 전이 |
| [fake_async](https://pub.dev/packages/fake_async/versions/1.3.3/license) | 1.3.3 | Apache-2.0 | 전이 |
| [ffi](https://pub.dev/packages/ffi/versions/2.2.0/license) | 2.2.0 | BSD-3-Clause | 전이 |
| [ffi_leak_tracker](https://pub.dev/packages/ffi_leak_tracker/versions/0.1.2/license) | 0.1.2 | BSD-3-Clause | 전이 |
| [file](https://pub.dev/packages/file/versions/7.0.1/license) | 7.0.1 | BSD-3-Clause | 전이 |
| [fixnum](https://pub.dev/packages/fixnum/versions/1.1.1/license) | 1.1.1 | BSD-3-Clause | 전이 |
| [flutter_lints](https://pub.dev/packages/flutter_lints/versions/6.0.0/license) | 6.0.0 | BSD-3-Clause | 개발 직접 |
| [flutter_riverpod](https://pub.dev/packages/flutter_riverpod/versions/3.5.0/license) | 3.5.0 | MIT | 직접 |
| [flutter_secure_storage](https://pub.dev/packages/flutter_secure_storage/versions/11.2.0/license) | 11.2.0 | BSD-3-Clause | 직접 |
| [flutter_secure_storage_darwin](https://pub.dev/packages/flutter_secure_storage_darwin/versions/0.4.3/license) | 0.4.3 | BSD-3-Clause | 전이 |
| [flutter_secure_storage_linux](https://pub.dev/packages/flutter_secure_storage_linux/versions/3.0.3/license) | 3.0.3 | BSD-3-Clause | 전이 |
| [flutter_secure_storage_platform_interface](https://pub.dev/packages/flutter_secure_storage_platform_interface/versions/2.1.1/license) | 2.1.1 | BSD-3-Clause | 전이 |
| [flutter_secure_storage_web](https://pub.dev/packages/flutter_secure_storage_web/versions/2.1.1/license) | 2.1.1 | BSD-3-Clause | 전이 |
| [flutter_secure_storage_windows](https://pub.dev/packages/flutter_secure_storage_windows/versions/4.2.2/license) | 4.2.2 | BSD-3-Clause | 전이 |
| [functions_client](https://pub.dev/packages/functions_client/versions/2.7.1/license) | 2.7.1 | MIT | 전이 |
| [gotrue](https://pub.dev/packages/gotrue/versions/2.27.3/license) | 2.27.3 | MIT | 전이 |
| [hooks](https://pub.dev/packages/hooks/versions/2.2.0/license) | 2.2.0 | BSD-3-Clause | 전이 |
| [http](https://pub.dev/packages/http/versions/1.6.0/license) | 1.6.0 | BSD-3-Clause | 직접 |
| [http_parser](https://pub.dev/packages/http_parser/versions/4.1.2/license) | 4.1.2 | BSD-3-Clause | 전이 |
| [intl](https://pub.dev/packages/intl/versions/0.20.3/license) | 0.20.3 | BSD-3-Clause | 전이 |
| [jni](https://pub.dev/packages/jni/versions/1.1.2/license) | 1.1.2 | BSD-3-Clause | 전이 |
| [jni_flutter](https://pub.dev/packages/jni_flutter/versions/1.0.4+1/license) | 1.0.4+1 | BSD-3-Clause | 전이 |
| [jni_util](https://pub.dev/packages/jni_util/versions/1.0.0/license) | 1.0.0 | BSD-3-Clause | 전이 |
| [json_annotation](https://pub.dev/packages/json_annotation/versions/4.12.0/license) | 4.12.0 | BSD-3-Clause | 전이 |
| [leak_tracker](https://pub.dev/packages/leak_tracker/versions/11.0.2/license) | 11.0.2 | BSD-3-Clause | 전이 |
| [leak_tracker_flutter_testing](https://pub.dev/packages/leak_tracker_flutter_testing/versions/3.0.10/license) | 3.0.10 | BSD-3-Clause | 전이 |
| [leak_tracker_testing](https://pub.dev/packages/leak_tracker_testing/versions/3.0.2/license) | 3.0.2 | BSD-3-Clause | 전이 |
| [lints](https://pub.dev/packages/lints/versions/6.1.0/license) | 6.1.0 | BSD-3-Clause | 전이 |
| [listen](https://pub.dev/packages/listen/versions/1.0.1/license) | 1.0.1 | BSD-3-Clause | 전이 |
| [logging](https://pub.dev/packages/logging/versions/1.3.0/license) | 1.3.0 | BSD-3-Clause | 전이 |
| [matcher](https://pub.dev/packages/matcher/versions/0.12.20/license) | 0.12.20 | BSD-3-Clause | 전이 |
| [material_color_utilities](https://pub.dev/packages/material_color_utilities/versions/0.13.0/license) | 0.13.0 | Apache-2.0 | 전이 |
| [material_ui](https://pub.dev/packages/material_ui/versions/1.11.0/license) | 1.11.0 | BSD-3-Clause | 직접 |
| [meta](https://pub.dev/packages/meta/versions/1.19.0/license) | 1.19.0 | BSD-3-Clause | 전이 |
| [mime](https://pub.dev/packages/mime/versions/2.1.0/license) | 2.1.0 | BSD-3-Clause | 전이 |
| [objective_c](https://pub.dev/packages/objective_c/versions/9.6.4/license) | 9.6.4 | BSD-3-Clause | 전이 |
| [package_config](https://pub.dev/packages/package_config/versions/3.0.0/license) | 3.0.0 | BSD-3-Clause | 전이 |
| [passkeys_platform_interface](https://pub.dev/packages/passkeys_platform_interface/versions/2.11.0/license) | 2.11.0 | BSD-3-Clause | 전이 |
| [path](https://pub.dev/packages/path/versions/1.9.1/license) | 1.9.1 | BSD-3-Clause | 전이 |
| [path_provider](https://pub.dev/packages/path_provider/versions/2.1.6/license) | 2.1.6 | BSD-3-Clause | 전이 |
| [path_provider_android](https://pub.dev/packages/path_provider_android/versions/2.3.1/license) | 2.3.1 | BSD-3-Clause | 전이 |
| [path_provider_foundation](https://pub.dev/packages/path_provider_foundation/versions/2.6.0/license) | 2.6.0 | BSD-3-Clause | 전이 |
| [path_provider_linux](https://pub.dev/packages/path_provider_linux/versions/2.2.2/license) | 2.2.2 | BSD-3-Clause | 전이 |
| [path_provider_platform_interface](https://pub.dev/packages/path_provider_platform_interface/versions/2.1.3/license) | 2.1.3 | BSD-3-Clause | 전이 |
| [path_provider_windows](https://pub.dev/packages/path_provider_windows/versions/2.3.0/license) | 2.3.0 | BSD-3-Clause | 전이 |
| [platform](https://pub.dev/packages/platform/versions/3.2.0/license) | 3.2.0 | BSD-3-Clause | 전이 |
| [plugin_platform_interface](https://pub.dev/packages/plugin_platform_interface/versions/2.1.8/license) | 2.1.8 | BSD-3-Clause | 전이 |
| [pointycastle](https://pub.dev/packages/pointycastle/versions/4.0.0/license) | 4.0.0 | MIT | 전이 |
| [postgrest](https://pub.dev/packages/postgrest/versions/2.9.1/license) | 2.9.1 | MIT | 전이 |
| [pub_semver](https://pub.dev/packages/pub_semver/versions/2.2.1/license) | 2.2.1 | BSD-3-Clause | 전이 |
| [realtime_client](https://pub.dev/packages/realtime_client/versions/2.13.2/license) | 2.13.2 | MIT | 전이 |
| [record_use](https://pub.dev/packages/record_use/versions/1.1.1/license) | 1.1.1 | BSD-3-Clause | 전이 |
| [riverpod](https://pub.dev/packages/riverpod/versions/3.5.0/license) | 3.5.0 | MIT | 전이 |
| [shared_preferences](https://pub.dev/packages/shared_preferences/versions/2.5.6/license) | 2.5.6 | BSD-3-Clause | 전이 |
| [shared_preferences_android](https://pub.dev/packages/shared_preferences_android/versions/2.5.5/license) | 2.5.5 | BSD-3-Clause | 전이 |
| [shared_preferences_foundation](https://pub.dev/packages/shared_preferences_foundation/versions/2.6.2/license) | 2.6.2 | BSD-3-Clause | 전이 |
| [shared_preferences_linux](https://pub.dev/packages/shared_preferences_linux/versions/2.4.1/license) | 2.4.1 | BSD-3-Clause | 전이 |
| [shared_preferences_platform_interface](https://pub.dev/packages/shared_preferences_platform_interface/versions/2.4.2/license) | 2.4.2 | BSD-3-Clause | 전이 |
| [shared_preferences_web](https://pub.dev/packages/shared_preferences_web/versions/2.4.3/license) | 2.4.3 | BSD-3-Clause | 전이 |
| [shared_preferences_windows](https://pub.dev/packages/shared_preferences_windows/versions/2.4.1/license) | 2.4.1 | BSD-3-Clause | 전이 |
| [source_span](https://pub.dev/packages/source_span/versions/1.10.2/license) | 1.10.2 | BSD-3-Clause | 전이 |
| [stack_trace](https://pub.dev/packages/stack_trace/versions/1.12.2/license) | 1.12.2 | BSD-3-Clause | 전이 |
| [state_notifier](https://pub.dev/packages/state_notifier/versions/1.0.0/license) | 1.0.0 | MIT | 전이 |
| [storage_client](https://pub.dev/packages/storage_client/versions/2.8.1/license) | 2.8.1 | MIT | 전이 |
| [stream_channel](https://pub.dev/packages/stream_channel/versions/2.1.4/license) | 2.1.4 | BSD-3-Clause | 전이 |
| [string_scanner](https://pub.dev/packages/string_scanner/versions/1.4.1/license) | 1.4.1 | BSD-3-Clause | 전이 |
| [supabase](https://pub.dev/packages/supabase/versions/2.16.5/license) | 2.16.5 | MIT | 전이 |
| [supabase_common](https://pub.dev/packages/supabase_common/versions/0.1.2/license) | 0.1.2 | MIT | 전이 |
| [supabase_flutter](https://pub.dev/packages/supabase_flutter/versions/2.18.3/license) | 2.18.3 | MIT | 직접 |
| [term_glyph](https://pub.dev/packages/term_glyph/versions/1.2.2/license) | 1.2.2 | BSD-3-Clause | 전이 |
| [test_api](https://pub.dev/packages/test_api/versions/0.7.14/license) | 0.7.14 | BSD-3-Clause | 전이 |
| [typed_data](https://pub.dev/packages/typed_data/versions/1.4.0/license) | 1.4.0 | BSD-3-Clause | 전이 |
| [url_launcher](https://pub.dev/packages/url_launcher/versions/6.3.3/license) | 6.3.3 | BSD-3-Clause | 전이 |
| [url_launcher_android](https://pub.dev/packages/url_launcher_android/versions/6.3.33/license) | 6.3.33 | BSD-3-Clause | 전이 |
| [url_launcher_ios](https://pub.dev/packages/url_launcher_ios/versions/6.4.2/license) | 6.4.2 | BSD-3-Clause | 전이 |
| [url_launcher_linux](https://pub.dev/packages/url_launcher_linux/versions/3.2.3/license) | 3.2.3 | BSD-3-Clause | 전이 |
| [url_launcher_macos](https://pub.dev/packages/url_launcher_macos/versions/3.2.6/license) | 3.2.6 | BSD-3-Clause | 전이 |
| [url_launcher_platform_interface](https://pub.dev/packages/url_launcher_platform_interface/versions/2.3.2/license) | 2.3.2 | BSD-3-Clause | 전이 |
| [url_launcher_web](https://pub.dev/packages/url_launcher_web/versions/2.4.3/license) | 2.4.3 | BSD-3-Clause | 전이 |
| [url_launcher_windows](https://pub.dev/packages/url_launcher_windows/versions/3.1.6/license) | 3.1.6 | BSD-3-Clause | 전이 |
| [uuid](https://pub.dev/packages/uuid/versions/4.6.0/license) | 4.6.0 | MIT | 직접 |
| [vector_math](https://pub.dev/packages/vector_math/versions/2.4.3/license) | 2.4.3 | BSD-3-Clause | 전이 |
| [vm_service](https://pub.dev/packages/vm_service/versions/15.4.0/license) | 15.4.0 | BSD-3-Clause | 전이 |
| [web](https://pub.dev/packages/web/versions/1.1.2/license) | 1.1.2 | BSD-3-Clause | 전이 |
| [web_socket](https://pub.dev/packages/web_socket/versions/1.0.1/license) | 1.0.1 | BSD-3-Clause | 전이 |
| [web_socket_channel](https://pub.dev/packages/web_socket_channel/versions/3.0.3/license) | 3.0.3 | BSD-3-Clause | 전이 |
| [win32](https://pub.dev/packages/win32/versions/6.4.0/license) | 6.4.0 | BSD-3-Clause | 전이 |
| [xdg_directories](https://pub.dev/packages/xdg_directories/versions/1.1.0/license) | 1.1.0 | BSD-3-Clause | 전이 |
| [yaml](https://pub.dev/packages/yaml/versions/3.1.4/license) | 3.1.4 | MIT | 전이 |
| [yet_another_json_isolate](https://pub.dev/packages/yet_another_json_isolate/versions/2.1.1/license) | 2.1.1 | MIT | 전이 |

</details>

## 서버와 개발 도구

Supabase Edge Functions의 TypeScript 코드는 현재 상대 경로의 프로젝트 파일과 Deno/Web 표준 API만 import한다. 외부 npm/JSR 모듈을 사용하지 않아 `deno.lock`도 생성되어 있지 않다. OpenAI와 Supabase HTTP 요청은 직접 수행하며 OpenAI 또는 Supabase JavaScript SDK를 번들하지 않는다. Deno 2.5.6은 MIT 라이선스의 개발 검사 도구이고, 호스팅된 Edge 런타임 배포판의 버전을 의미하지 않는다. [Deno 2.5.6 LICENSE](https://github.com/denoland/deno/blob/v2.5.6/LICENSE.md)

PostgreSQL·Queues·Cron 등 Supabase 서비스와 Google 로그인·OpenAI API는 외부 서비스로 이용한다. 이 저장소가 해당 서비스 서버·모델·SDK 전체를 재배포한다는 의미는 아니며, 서비스 이용 조건과 소스 의존성 라이선스를 구분한다.

UI 방향을 검토할 때 Damir Sokolovsky의 Unslop 지침(CC BY 4.0)을 참고했다. 지침 원문을 복제하지 않았으며 도란 UI 코드는 프로젝트에서 작성했다. [Unslop 원출처](https://heydamir.design/unslop)
