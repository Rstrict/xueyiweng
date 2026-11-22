# 从源码重新构建

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `JAVA_HOME` 必须指向 JDK 17 |
| Android SDK | platform 36 + build-tools 34+ | `ANDROID_HOME` / `local.properties` 的 `sdk.dir` |
| Gradle | 8.14.4 | 需 ≥ 8.13（AGP 8.13.2 要求） |
| AGP | 8.13.2 | 见 `build.gradle.kts` |
| Kotlin | 2.0.21 | |

> 项目路径**不能含中文**。AGP 会直接拒绝：
> `Your project path contains non-ASCII characters`
> 如需在中文目录下维护，建议真源码放 ASCII 路径，中文目录里放软链或副本。

## 构建

```powershell
$env:JAVA_HOME = "<JDK17 路径>"
$env:ANDROID_HOME = "<SDK 路径>"
$env:GRADLE_USER_HOME = "<gradle 缓存目录>"

# 调试包
gradle assembleDebug

# 发布包（当前用 debug 证书签名，换正式证书见下）
gradle assembleRelease
```

产物：
```
app/build/outputs/apk/debug/app-debug.apk      (~7.8 MB)
app/build/outputs/apk/release/app-release.apk  (~5.6 MB)
```

## 单元测试

```powershell
gradle testDebugUnitTest
```
报告：`app/build/reports/tests/testDebugUnitTest/index.html`

## 设备内自检

```powershell
adb shell am start -n com.xueyiweng.app.debug/com.xueyiweng.app.ui.MainActivity --es run_selfcheck 1
adb logcat -s XYW_SELFCHECK:*
```

## 状态机驱动（端到端验证用）

```powershell
$pkg = "com.xueyiweng.app.debug"
$act = "$pkg/com.xueyiweng.app.ui.MainActivity"

adb shell am start -n $act --es drive start  --es q "问题文本"
adb shell am start -n $act --es drive stage  --es to recall_1
adb shell am start -n $act --es drive answer --es a "盲答内容"
adb shell am start -n $act --es drive dump   --es t "杂念"
adb shell am start -n $act --es drive pause  --es note "停在哪"
adb shell am start -n $act --es drive resume
adb shell am start -n $act --es drive finish --es m need_hint
adb shell am start -n $act --es drive status
adb shell am start -n $act --es drive queue

adb logcat -s XYW_DRIVE:*
```

`drive` 命令跑在 IO 线程（`Dispatchers.IO`），不会阻塞主线程。

## 换正式签名

1. 生成 keystore：
```powershell
keytool -genkeypair -v -keystore xyw-release.jks -alias xyw -keyalg RSA -keysize 2048 -validity 10000
```

2. 在 `app/build.gradle.kts` 里替换 `signingConfigs.getByName("debug")`：
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../xyw-release.jks")
        storePassword = System.getenv("XYW_STORE_PASS")
        keyAlias = "xyw"
        keyPassword = System.getenv("XYW_KEY_PASS")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

3. keystore 与密码**不要提交进仓库**。

## 项目结构

```
.
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/xueyiweng/app/
│       │   │   ├── XueYiWenApp.kt
│       │   │   ├── logic/Logic.kt
│       │   │   ├── data/{Db,Repository,SettingsRepository}.kt
│       │   │   ├── service/{Feedback,StageTimerService,Receivers}.kt
│       │   │   └── ui/...
│       │   └── res/
│       │       ├── layout/       # activity_main / fragment_* / stage_*
│       │       ├── values/       # colors, strings, themes, dimens
│       │       ├── values-night/ # 深色配色
│       │       ├── drawable/     # 形状、圆环、图标
│       │       └── mipmap-*/     # 启动图标（含 adaptive）
│       └── test/java/.../LogicTest.kt   # 26 项单测
├── tools/
│   ├── make_icons.py    # 用 Pillow 生成各密度图标
│   ├── serve.py         # 本地静态服务（遗留，PWA 版用）
│   ├── ui-e2e.ps1       # UI 端到端脚本
│   └── drive.sh         # 设备端驱动脚本
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── local.properties     # sdk.dir（不要提交）
```

## 依赖清单

```
androidx.core:core-ktx:1.13.1
androidx.appcompat:appcompat:1.7.1
com.google.android.material:material:1.14.0
androidx.constraintlayout:constraintlayout:2.2.1
androidx.activity:activity-ktx:1.9.3
androidx.fragment:fragment-ktx:1.8.9
androidx.lifecycle:lifecycle-runtime-ktx:2.8.7
androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7
androidx.recyclerview:recyclerview:1.4.0
androidx.datastore:datastore-preferences:1.1.7
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0

testImplementation junit:junit:4.13.2
```

无 Room、无 Compose、无第三方 JSON 库、无任何网络库。
