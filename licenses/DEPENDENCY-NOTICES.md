# 1 应用代码依赖的版权与许可

本文件随安装包离线提供，适用于本项目锁定版本的代码依赖；人体模型及中文内容的来源另见 `MODEL-NOTICE.md`。下列第三方库由各自作者持有版权，不改变本项目自行编写代码或人体模型的许可。

## 1.1 Android 运行依赖

以下坐标来自本次 release 解析产生的 SDK 依赖清单，包含公共变体与 BOM；它们是解析输入，并不表示每个类均在 R8 优化后保留。AndroidX 由 Android Open Source Project 及各贡献者提供，Kotlin、协程和序列化由 JetBrains 及各贡献者提供，Okio 由 Square 及各贡献者提供，ListenableFuture 由 Guava Authors 提供，JSpecify 由 JSpecify Authors 提供。Apache-2.0 全文同时保留在本目录 `FILAMENT-Apache-2.0.txt`。

| Maven 坐标 | 许可 | 官方元数据 |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.21` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.pom) |
| `org.jetbrains:annotations:23.0.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib-common:2.2.21` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/2.2.21/kotlin-stdlib-common-2.2.21.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.8.0/kotlin-stdlib-jdk8-1.8.0.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.8.0/kotlin-stdlib-jdk7-1.8.0.pom) |
| `androidx.compose:compose-bom:2025.10.01` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom) |
| `androidx.compose.material:material-icons-extended:1.7.8` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended/1.7.8/material-icons-extended-1.7.8.pom) |
| `androidx.compose.material:material-icons-extended-android:1.7.8` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended-android/1.7.8/material-icons-extended-android-1.7.8.pom) |
| `androidx.compose.material:material-icons-core:1.7.8` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core/1.7.8/material-icons-core-1.7.8.pom) |
| `androidx.compose.material:material-icons-core-android:1.7.8` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core-android/1.7.8/material-icons-core-android-1.7.8.pom) |
| `androidx.compose.ui:ui:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.9.4/ui-1.9.4.pom) |
| `androidx.compose.ui:ui-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.9.4/ui-android-1.9.4.pom) |
| `androidx.activity:activity-ktx:1.11.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.11.0/activity-ktx-1.11.0.pom) |
| `androidx.activity:activity:1.11.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.11.0/activity-1.11.0.pom) |
| `androidx.annotation:annotation:1.9.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.9.1/annotation-1.9.1.pom) |
| `androidx.annotation:annotation-jvm:1.9.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.pom) |
| `androidx.core:core-ktx:1.17.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.17.0/core-ktx-1.17.0.pom) |
| `androidx.core:core:1.17.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/core/core/1.17.0/core-1.17.0.pom) |
| `androidx.annotation:annotation-experimental:1.5.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.5.0/annotation-experimental-1.5.0.pom) |
| `androidx.collection:collection:1.5.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.5.0/collection-1.5.0.pom) |
| `androidx.collection:collection-jvm:1.5.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0.pom) |
| `androidx.collection:collection-ktx:1.5.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/collection/collection-ktx/1.5.0/collection-ktx-1.5.0.pom) |
| `androidx.concurrent:concurrent-futures:1.1.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.pom) |
| `com.google.guava:listenablefuture:1.0` | Apache-2.0（Guava 源文件） | [POM](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.pom) |
| `androidx.core:core-viewtree:1.0.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/core/core-viewtree/1.0.0/core-viewtree-1.0.0.pom) |
| `androidx.interpolator:interpolator:1.0.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.pom) |
| `androidx.lifecycle:lifecycle-common:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.9.4/lifecycle-common-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-common-jvm:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.9.4/lifecycle-common-jvm-2.9.4.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.8.1/kotlinx-coroutines-core-1.8.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/1.8.1/kotlinx-coroutines-bom-1.8.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.8.1/kotlinx-coroutines-android-1.8.1.pom) |
| `org.jspecify:jspecify:1.0.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.pom) |
| `androidx.lifecycle:lifecycle-common-java8:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-java8/2.9.4/lifecycle-common-java8-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.9.4/lifecycle-runtime-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.9.4/lifecycle-runtime-android-2.9.4.pom) |
| `androidx.arch.core:core-common:2.2.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.pom) |
| `androidx.arch.core:core-runtime:2.2.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.pom) |
| `androidx.profileinstaller:profileinstaller:1.4.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0.pom) |
| `androidx.startup:startup-runtime:1.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1.pom) |
| `androidx.tracing:tracing:1.2.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.2.0/tracing-1.2.0.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.9.4/lifecycle-runtime-compose-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.9.4/lifecycle-runtime-compose-android-2.9.4.pom) |
| `androidx.compose.runtime:runtime:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime/1.9.4/runtime-1.9.4.pom) |
| `androidx.compose.runtime:runtime-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.9.4/runtime-android-1.9.4.pom) |
| `androidx.compose.runtime:runtime-annotation:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation/1.9.4/runtime-annotation-1.9.4.pom) |
| `androidx.compose.runtime:runtime-annotation-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation-android/1.9.4/runtime-annotation-android-1.9.4.pom) |
| `androidx.compose.runtime:runtime-saveable:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable/1.9.4/runtime-saveable-1.9.4.pom) |
| `androidx.compose.runtime:runtime-saveable-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.9.4/runtime-saveable-android-1.9.4.pom) |
| `androidx.savedstate:savedstate-compose:1.3.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose/1.3.3/savedstate-compose-1.3.3.pom) |
| `androidx.savedstate:savedstate-compose-android:1.3.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-android/1.3.3/savedstate-compose-android-1.3.3.pom) |
| `androidx.savedstate:savedstate:1.3.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.3.3/savedstate-1.3.3.pom) |
| `androidx.savedstate:savedstate-android:1.3.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-android/1.3.3/savedstate-android-1.3.3.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core/1.7.3/kotlinx-serialization-core-1.7.3.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-bom/1.7.3/kotlinx-serialization-bom-1.7.3.pom) |
| `androidx.savedstate:savedstate-ktx:1.3.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-ktx/1.3.3/savedstate-ktx-1.3.3.pom) |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/2.9.4/lifecycle-runtime-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx-android/2.9.4/lifecycle-runtime-ktx-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-livedata-core:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.9.4/lifecycle-livedata-core-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.9.4/lifecycle-viewmodel-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-android/2.9.4/lifecycle-viewmodel-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose/2.9.4/lifecycle-viewmodel-compose-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose-android/2.9.4/lifecycle-viewmodel-compose-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.9.4/lifecycle-viewmodel-savedstate-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate-android/2.9.4/lifecycle-viewmodel-savedstate-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.9.4/lifecycle-viewmodel-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-process:2.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.9.4/lifecycle-process-2.9.4.pom) |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.pom) |
| `androidx.activity:activity-compose:1.11.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.11.0/activity-compose-1.11.0.pom) |
| `androidx.autofill:autofill:1.0.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/autofill/autofill/1.0.0/autofill-1.0.0.pom) |
| `androidx.compose.ui:ui-geometry:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry/1.9.4/ui-geometry-1.9.4.pom) |
| `androidx.compose.ui:ui-geometry-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry-android/1.9.4/ui-geometry-android-1.9.4.pom) |
| `androidx.compose.ui:ui-util:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util/1.9.4/ui-util-1.9.4.pom) |
| `androidx.compose.ui:ui-util-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util-android/1.9.4/ui-util-android-1.9.4.pom) |
| `androidx.compose.ui:ui-graphics:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics/1.9.4/ui-graphics-1.9.4.pom) |
| `androidx.compose.ui:ui-graphics-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.9.4/ui-graphics-android-1.9.4.pom) |
| `androidx.compose.ui:ui-unit:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit/1.9.4/ui-unit-1.9.4.pom) |
| `androidx.compose.ui:ui-unit-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.9.4/ui-unit-android-1.9.4.pom) |
| `androidx.compose.ui:ui-text:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text/1.9.4/ui-text-1.9.4.pom) |
| `androidx.compose.ui:ui-text-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.9.4/ui-text-android-1.9.4.pom) |
| `androidx.emoji2:emoji2:1.4.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2/1.4.0/emoji2-1.4.0.pom) |
| `androidx.compose.ui:ui-tooling-preview:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-tooling-preview/1.9.4/ui-tooling-preview-1.9.4.pom) |
| `androidx.compose.ui:ui-tooling-preview-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-tooling-preview-android/1.9.4/ui-tooling-preview-android-1.9.4.pom) |
| `androidx.graphics:graphics-path:1.0.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.pom) |
| `androidx.customview:customview-poolingcontainer:1.0.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.pom) |
| `androidx.compose.foundation:foundation:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.4/foundation-1.9.4.pom) |
| `androidx.compose.foundation:foundation-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.9.4/foundation-android-1.9.4.pom) |
| `androidx.compose.animation:animation:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation/1.9.4/animation-1.9.4.pom) |
| `androidx.compose.animation:animation-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.9.4/animation-android-1.9.4.pom) |
| `androidx.compose.animation:animation-core:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core/1.9.4/animation-core-1.9.4.pom) |
| `androidx.compose.animation:animation-core-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.9.4/animation-core-android-1.9.4.pom) |
| `androidx.compose.foundation:foundation-layout:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout/1.9.4/foundation-layout-1.9.4.pom) |
| `androidx.compose.foundation:foundation-layout-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.9.4/foundation-layout-android-1.9.4.pom) |
| `androidx.compose.material3:material3:1.4.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0.pom) |
| `androidx.compose.material3:material3-android:1.4.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0.pom) |
| `androidx.compose.material:material-ripple:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple/1.9.4/material-ripple-1.9.4.pom) |
| `androidx.compose.material:material-ripple-android:1.9.4` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.9.4/material-ripple-android-1.9.4.pom) |
| `androidx.room:room-runtime:2.8.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.8.3/room-runtime-2.8.3.pom) |
| `androidx.room:room-runtime-android:2.8.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.3/room-runtime-android-2.8.3.pom) |
| `androidx.room:room-common:2.8.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/room/room-common/2.8.3/room-common-2.8.3.pom) |
| `androidx.room:room-common-jvm:2.8.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/room/room-common-jvm/2.8.3/room-common-jvm-2.8.3.pom) |
| `androidx.room:room-ktx:2.8.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/room/room-ktx/2.8.3/room-ktx-2.8.3.pom) |
| `androidx.sqlite:sqlite:2.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite/2.6.1/sqlite-2.6.1.pom) |
| `androidx.sqlite:sqlite-android:2.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-android/2.6.1/sqlite-android-2.6.1.pom) |
| `androidx.sqlite:sqlite-framework:2.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework/2.6.1/sqlite-framework-2.6.1.pom) |
| `androidx.sqlite:sqlite-framework-android:2.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework-android/2.6.1/sqlite-framework-android-2.6.1.pom) |
| `androidx.datastore:datastore-preferences:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/1.1.7/datastore-preferences-1.1.7.pom) |
| `androidx.datastore:datastore-preferences-android:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-android/1.1.7/datastore-preferences-android-1.1.7.pom) |
| `androidx.datastore:datastore:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore/1.1.7/datastore-1.1.7.pom) |
| `androidx.datastore:datastore-android:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-android/1.1.7/datastore-android-1.1.7.pom) |
| `androidx.datastore:datastore-core:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core/1.1.7/datastore-core-1.1.7.pom) |
| `androidx.datastore:datastore-core-android:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-android/1.1.7/datastore-core-android-1.1.7.pom) |
| `org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-parcelize-runtime/1.9.22/kotlin-parcelize-runtime-1.9.22.pom) |
| `org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-android-extensions-runtime/1.9.22/kotlin-android-extensions-runtime-1.9.22.pom) |
| `androidx.datastore:datastore-core-okio:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-okio/1.1.7/datastore-core-okio-1.1.7.pom) |
| `androidx.datastore:datastore-core-okio-jvm:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-okio-jvm/1.1.7/datastore-core-okio-jvm-1.1.7.pom) |
| `com.squareup.okio:okio:3.4.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/3.4.0/okio-3.4.0.pom) |
| `com.squareup.okio:okio-jvm:3.4.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.4.0/okio-jvm-3.4.0.pom) |
| `androidx.datastore:datastore-preferences-core:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-core/1.1.7/datastore-preferences-core-1.1.7.pom) |
| `androidx.datastore:datastore-preferences-core-android:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-core-android/1.1.7/datastore-preferences-core-android-1.1.7.pom) |
| `androidx.datastore:datastore-preferences-proto:1.1.7` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-proto/1.1.7/datastore-preferences-proto-1.1.7.pom) |
| `androidx.datastore:datastore-preferences-external-protobuf:1.1.7` | BSD-3-Clause | [POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-external-protobuf/1.1.7/datastore-preferences-external-protobuf-1.1.7.pom) |

DataStore 的 `datastore-preferences-external-protobuf:1.1.7` 是重打包的 Protocol Buffers lite，实际 POM 明确标注 BSD-3-Clause。后文保留 Google Protocol Buffers 的 BSD 原文；文本来源链接用于保留版权声明，不将该链接中的 Protobuf tag 当作 DataStore 内部实现版本。Kotlin 标准库中时间和数学代码的附加声明来自 [Kotlin v2.2.21 许可索引](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/README.md)，后文保留其中适用于 JVM 标准库的 ThreeTenBP 和 Boost 许可。

`com.google.guava:listenablefuture:1.0` 的版权为 Guava Authors，按 Apache-2.0 提供；[该固定版本源包](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0-sources.jar)中的 `ListenableFuture.java` 包含声明。构建工具 Gradle 8.14.3 不进入 APK；源码中随附的 `gradle-wrapper.jar` 已在其 `META-INF/LICENSE` 内保留 Apache-2.0 许可。

## 1.2 Filament 与原生组件

Filament / gltfio 1.78.0 由 Android Open Source Project 及各贡献者提供，使用官方发布 AAR；Filament 固定 tag 对应提交为 `c1dc4e444784850d6dc891a1fb38747c30b85463`。[官方主许可](https://github.com/google/filament/blob/c1dc4e444784850d6dc891a1fb38747c30b85463/LICENSE)为 Apache-2.0，全文随本目录 `FILAMENT-Apache-2.0.txt` 分发。Draco 为 Google 及各贡献者提供的 Apache-2.0 组件；Basis Universal transcoder 为 Binomial LLC 提供的 Apache-2.0 组件，原声明为 Copyright (C) 2019-2026 Binomial LLC. All Rights Reserved.

原生附加组件依据固定提交中的 [gltfio 链接清单](https://github.com/google/filament/blob/c1dc4e444784850d6dc891a1fb38747c30b85463/libs/gltfio/CMakeLists.txt)、[backend 链接清单](https://github.com/google/filament/blob/c1dc4e444784850d6dc891a1fb38747c30b85463/filament/backend/CMakeLists.txt)、[geometry 链接清单](https://github.com/google/filament/blob/c1dc4e444784850d6dc891a1fb38747c30b85463/libs/geometry/CMakeLists.txt)和内嵌 shader 来源收录。这里不收录未随应用分发的 Filament 桌面工具、测试样例、示例模型或编译器的全部第三方源码许可。

AAR 原生库的编译器标记指向 Android LLVM 提交 `5e96669f06077099aa41290cdb4c5e6fa0f59349`；后文保留该提交的 libc++ / libc++abi 许可，包含 LLVM runtime exception 和原有版权声明。

## 1.3 附加版权与许可原文

以下英文原文按所列官方来源保留。SPIRV-Headers 仅收录运行头文件对应段落；MikkTSpace 仅提取源文件开头完整许可声明；其他条目保留对应许可文件全文。每项 SHA-256 指向原始获取文本（有节选时仍是完整来源文件），便于核验版本。

### 1.3.1 cgltf

许可：MIT。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/cgltf/LICENSE)。

来源文本 SHA-256：`f619925f80ef862497aaf8e8155ef218fa6a2190055129523ca3df9119a9ba95`。

```text
Copyright (c) 2018-2021 Johannes Kuhlmann

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### 1.3.2 robin-map / tsl

许可：MIT。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/robin-map/LICENSE)。

来源文本 SHA-256：`11f2c685ba565a31aef1219f63a7a8845602c97bff70016b9e3278dfe51a2ec5`。

```text
MIT License

Copyright (c) 2017 Thibaut Goetghebuer-Planchon <tessil@gmx.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 1.3.3 meshoptimizer

许可：MIT。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/meshoptimizer/LICENSE)。

来源文本 SHA-256：`063805e3b8e24ceab4c5dea358b03245ee41c31be87cf65c61d836636cb494bb`。

```text
MIT License

Copyright (c) 2016-2022 Arseny Kapoulkine

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 1.3.4 stb

许可：Public domain / fallback permission。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/stb/LICENSE)。

来源文本 SHA-256：`296bf9398cd1c82620d19cd623db6b880e31a266df7183141d49e486fc30c8da`。

```text
This software is in the public domain. Where that dedication is not
recognized, you are granted a perpetual, irrevocable license to copy,
distribute, and modify this file as you see fit.
```

### 1.3.5 Zstandard

许可：BSD-3-Clause。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/zstd/LICENSE)。

来源文本 SHA-256：`7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8`。

```text
BSD License

For Zstandard software

Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

 * Neither the name Facebook, nor Meta, nor the names of its contributors may
   be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### 1.3.6 SMOL-V

许可：MIT 或 Unlicense。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/smol-v/LICENSE)。

来源文本 SHA-256：`9cea538cc1ee6680f4d13e212bfef5b581cd5d873cbedf6dc07699dbe7e69651`。

```text
------------------------------------------------------------------------------
This software is available under 2 licenses -- choose whichever you prefer.
------------------------------------------------------------------------------
ALTERNATIVE A - MIT License
Copyright (c) 2016-2018 Aras Pranckevicius
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
------------------------------------------------------------------------------
ALTERNATIVE B - Public Domain (www.unlicense.org)
This is free and unencumbered software released into the public domain.
Anyone is free to copy, modify, publish, use, compile, sell, or distribute this
software, either in source code form or as a compiled binary, for any purpose,
commercial or non-commercial, and by any means.
In jurisdictions that recognize copyright laws, the author or authors of this
software dedicate any and all copyright interest in the software to the public
domain. We make this dedication for the benefit of the public at large and to
the detriment of our heirs and successors. We intend this dedication to be an
overt act of relinquishment in perpetuity of all present and future rights to
this software under copyright law.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
------------------------------------------------------------------------------
```

### 1.3.7 Vulkan Memory Allocator

许可：MIT。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/vkmemalloc/LICENSE.txt)。

来源文本 SHA-256：`52df2c03d6cfc9ffec13c9d3626c530fc9ce0cbe41d5ea3d10cd46edeb1aeb38`。

```text
Copyright (c) 2017-2026 Advanced Micro Devices, Inc. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### 1.3.8 SPIRV-Headers 运行时头文件

许可：Khronos MIT-style。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/spirv-headers/LICENSE)。

来源文本 SHA-256：`ea43b1de38a6f90c488800d66dec1ed671e68cda530266bc96951fb5b6307613`。

```text
Files: All files except for those called out below.
Copyright (c) 2015-2024 The Khronos Group Inc.

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and/or associated documentation files (the
"Materials"), to deal in the Materials without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Materials, and to
permit persons to whom the Materials are furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Materials.

MODIFICATIONS TO THIS FILE MAY MEAN IT NO LONGER ACCURATELY REFLECTS
KHRONOS STANDARDS. THE UNMODIFIED, NORMATIVE VERSIONS OF KHRONOS
SPECIFICATIONS AND HEADER INFORMATION ARE LOCATED AT
   https://www.khronos.org/registry/

THE MATERIALS ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
MATERIALS OR THE USE OR OTHER DEALINGS IN THE MATERIALS.
```

### 1.3.9 MikkTSpace

许可：Zlib-style。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/third_party/mikktspace/src/mikktspace.c)。

来源文本 SHA-256：`4d2538c27d28fd9404f93d172434d14758665d4d6619d36bee9bb2a49fb1411c`。

```text
/** \file mikktspace/mikktspace.c
 *  \ingroup mikktspace
 */
/**
 *  Copyright (C) 2011 by Morten S. Mikkelsen
 *
 *  This software is provided 'as-is', without any express or implied
 *  warranty.  In no event will the authors be held liable for any damages
 *  arising from the use of this software.
 *
 *  Permission is granted to anyone to use this software for any purpose,
 *  including commercial applications, and to alter it and redistribute it
 *  freely, subject to the following restrictions:
 *
 *  1. The origin of this software must not be misrepresented; you must not
 *     claim that you wrote the original software. If you use this software
 *     in a product, an acknowledgment in the product documentation would be
 *     appreciated but is not required.
 *  2. Altered source versions must be plainly marked as such, and must not be
 *     misrepresented as being the original software.
 *  3. This notice may not be removed or altered from any source distribution.
 */
```

### 1.3.10 AMD FidelityFX FSR

许可：MIT。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/filament/src/materials/fsr/license.txt)。

来源文本 SHA-256：`db089274ce766da70f5b7d791029c3486f9f9e27c8c79c652689603d3192e802`。

```text
Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### 1.3.11 Qualcomm SGSR

许可：BSD-3-Clause。来源：[官方原文](https://raw.githubusercontent.com/google/filament/c1dc4e444784850d6dc891a1fb38747c30b85463/filament/src/materials/sgsr/LICENSE.txt)。

来源文本 SHA-256：`bc496633d3db8c6ca1362216ec382d361b69c7d5518acd6bbfd91503febfb3ef`。

```text
Copyright (c) 2023, Qualcomm Innovation Center, Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

SPDX-License-Identifier: BSD-3-Clause
```

### 1.3.12 Kotlin 标准库的 ThreeTenBP 衍生时间实现

许可：BSD-3-Clause。来源：[官方原文](https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/license/third_party/threetenbp_license.txt)。

来源文本 SHA-256：`d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331`。

```text
Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos.

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of JSR-310 nor the names of its contributors
  may be used to endorse or promote products derived from this software
  without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### 1.3.13 Kotlin 标准库的 Boost 衍生数学实现

许可：BSL-1.0。来源：[官方原文](https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/license/third_party/boost_LICENSE.txt)。

来源文本 SHA-256：`8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b`。

```text
Boost Software License - Version 1.0 - August 17th, 2003

Permission is hereby granted, free of charge, to any person or organization
obtaining a copy of the software and accompanying documentation covered by
this license (the "Software") to use, reproduce, display, distribute,
execute, and transmit the Software, and to prepare derivative works of the
Software, and to permit third-parties to whom the Software is furnished to
do so, all subject to the following:

The copyright notices in the Software and this entire statement, including
the above license grant, this restriction and the following disclaimer,
must be included in all copies of the Software, in whole or in part, and
all derivative works of the Software, unless such copies or derivative
works are solely in the form of machine-executable object code generated by
a source language processor.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### 1.3.14 DataStore 重打包的 Protocol Buffers lite

许可：BSD-3-Clause。来源：[官方原文](https://raw.githubusercontent.com/protocolbuffers/protobuf/v25.5/LICENSE)。

来源文本 SHA-256：`6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d`。

```text
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Code generated by the Protocol Buffer compiler is owned by the owner
of the input file used when generating it.  This code is not
standalone and requires a support library to be linked with it.  This
support library is itself covered by the above license.
```

### 1.3.15 NDK libc++

许可：Apache-2.0 with LLVM exceptions / legacy notices。来源：[官方原文](https://android.googlesource.com/toolchain/llvm-project/+/5e96669f06077099aa41290cdb4c5e6fa0f59349/libcxx/LICENSE.TXT?format=TEXT)。

来源文本 SHA-256：`539dd7aed86e8a4f12cbdd0e6c50c189c7d74847e4fecc64ce2c6ee3a01da38b`。

```text
==============================================================================
The LLVM Project is under the Apache License v2.0 with LLVM Exceptions:
==============================================================================

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

    TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

    1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

    2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

    3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

    4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

    5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

    6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

    7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

    8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

    9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

    END OF TERMS AND CONDITIONS

    APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

    Copyright [yyyy] [name of copyright owner]

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


---- LLVM Exceptions to the Apache 2.0 License ----

As an exception, if, as a result of your compiling your source code, portions
of this Software are embedded into an Object form of such source code, you
may redistribute such embedded portions in such Object form without complying
with the conditions of Sections 4(a), 4(b) and 4(d) of the License.

In addition, if you combine or link compiled forms of this Software with
software that is licensed under the GPLv2 ("Combined Software") and if a
court of competent jurisdiction determines that the patent provision (Section
3), the indemnity provision (Section 9) or other Section of the License
conflicts with the conditions of the GPLv2, you may retroactively and
prospectively choose to deem waived or otherwise exclude such Section(s) of
the License, but only in their entirety and only with respect to the Combined
Software.

==============================================================================
Software from third parties included in the LLVM Project:
==============================================================================
The LLVM Project contains third party software which is under different license
terms. All such code will be identified clearly using at least one of two
mechanisms:
1) It will be in a separate directory tree with its own `LICENSE.txt` or
   `LICENSE` file at the top containing the specific license and restrictions
   which apply to that software, or
2) It will contain specific license and restriction terms at the top of every
   file.

==============================================================================
Legacy LLVM License (https://llvm.org/docs/DeveloperPolicy.html#legacy):
==============================================================================

The libc++ library is dual licensed under both the University of Illinois
"BSD-Like" license and the MIT license.  As a user of this code you may choose
to use it under either license.  As a contributor, you agree to allow your code
to be used under both.

Full text of the relevant licenses is included below.

==============================================================================

University of Illinois/NCSA
Open Source License

Copyright (c) 2009-2019 by the contributors listed in CREDITS.TXT

All rights reserved.

Developed by:

    LLVM Team

    University of Illinois at Urbana-Champaign

    http://llvm.org

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal with
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimers.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimers in the
      documentation and/or other materials provided with the distribution.

    * Neither the names of the LLVM Team, University of Illinois at
      Urbana-Champaign, nor the names of its contributors may be used to
      endorse or promote products derived from this Software without specific
      prior written permission.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
SOFTWARE.

==============================================================================

Copyright (c) 2009-2014 by the contributors listed in CREDITS.TXT

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### 1.3.16 NDK libc++abi

许可：Apache-2.0 with LLVM exceptions / legacy notices。来源：[官方原文](https://android.googlesource.com/toolchain/llvm-project/+/5e96669f06077099aa41290cdb4c5e6fa0f59349/libcxxabi/LICENSE.TXT?format=TEXT)。

来源文本 SHA-256：`e2b35be49f7284a45b7baca8fc7b3ab7440e7902392b2528a457816b5bb2a15c`。

```text
==============================================================================
The LLVM Project is under the Apache License v2.0 with LLVM Exceptions:
==============================================================================

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

    TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

    1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

    2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

    3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

    4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

    5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

    6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

    7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

    8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

    9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

    END OF TERMS AND CONDITIONS

    APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

    Copyright [yyyy] [name of copyright owner]

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


---- LLVM Exceptions to the Apache 2.0 License ----

As an exception, if, as a result of your compiling your source code, portions
of this Software are embedded into an Object form of such source code, you
may redistribute such embedded portions in such Object form without complying
with the conditions of Sections 4(a), 4(b) and 4(d) of the License.

In addition, if you combine or link compiled forms of this Software with
software that is licensed under the GPLv2 ("Combined Software") and if a
court of competent jurisdiction determines that the patent provision (Section
3), the indemnity provision (Section 9) or other Section of the License
conflicts with the conditions of the GPLv2, you may retroactively and
prospectively choose to deem waived or otherwise exclude such Section(s) of
the License, but only in their entirety and only with respect to the Combined
Software.

==============================================================================
Software from third parties included in the LLVM Project:
==============================================================================
The LLVM Project contains third party software which is under different license
terms. All such code will be identified clearly using at least one of two
mechanisms:
1) It will be in a separate directory tree with its own `LICENSE.txt` or
   `LICENSE` file at the top containing the specific license and restrictions
   which apply to that software, or
2) It will contain specific license and restriction terms at the top of every
   file.

==============================================================================
Legacy LLVM License (https://llvm.org/docs/DeveloperPolicy.html#legacy):
==============================================================================

The libc++abi library is dual licensed under both the University of Illinois
"BSD-Like" license and the MIT license.  As a user of this code you may choose
to use it under either license.  As a contributor, you agree to allow your code
to be used under both.

Full text of the relevant licenses is included below.

==============================================================================

University of Illinois/NCSA
Open Source License

Copyright (c) 2009-2019 by the contributors listed in CREDITS.TXT

All rights reserved.

Developed by:

    LLVM Team

    University of Illinois at Urbana-Champaign

    http://llvm.org

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal with
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimers.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimers in the
      documentation and/or other materials provided with the distribution.

    * Neither the names of the LLVM Team, University of Illinois at
      Urbana-Champaign, nor the names of its contributors may be used to
      endorse or promote products derived from this Software without specific
      prior written permission.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
SOFTWARE.

==============================================================================

Copyright (c) 2009-2014 by the contributors listed in CREDITS.TXT

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
