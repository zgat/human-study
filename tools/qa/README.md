# 1 设备交互与耐久验证

本目录测试面向已安装的同次构建 debug APK 和对应 androidTest APK。release 的启动、画面与手势验收需单独记录；本脚本输出的性能属于 debug 构建。

## 1.1 Compose 交互测试

测试类为 `com.zga.meridianatlas.ResponsiveUiDeviceTest`。默认执行紧凑 420×760 dp 范围的模式切换、肌肉总开关与区域深度、中文显示、`fuai`/`EXHN12` 搜索、金津玉液局部显示、左右点、夹脊末点选择、各模式定位前状态恢复；原生宽度达到 840 dp 的设备另执行平板常驻目录用例。窄设备会跳过该平板用例，不改变系统分辨率、旋转或字体设置。

使用项目既有构建和安装流程生成两个 APK，再运行该测试类。`soakModelGestures` 默认跳过，只有收到 `soak_seconds` 参数时才运行。

## 1.2 十分钟持续手势

先通过项目 `tools/device.py` 绑定所需设备，并安装同次构建的两个 debug APK。在项目目录运行：

```sh
python3 tools/qa/device_soak.py \
  --expected-apk app/build/outputs/apk/debug/app-debug.apk \
  --expected-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --minutes 10 --quality standard --mode muscles
```

参数：`--quality standard|low`、`--mode muscles|overview|meridians`；`--label` 可修改结果目录后缀。脚本调用既有设备选择器一次，此后固定该 transport；不安装 APK，不自动重连或重启应用/设备，不改变系统设置。两份已安装 `base.apk` 的 SHA-256 必须分别匹配参数文件，目标必须为 debuggable。

手势由本应用测试进程向实际模型 SurfaceView 注入单指旋转和双指缩放。仅应用在前台且窗口有焦点时注入；每十秒输出手势计数，每分钟记录 PID、PSS/RSS、SurfaceView 图层呈现时间戳，并在应用前台截图。结束时只向本应用发送停止测试广播，由测试 rule 关闭 Activity。

截图和汇总位于 `outputs/qa/<运行标识>/`，原始 instrumentation、内存、SurfaceFlinger 与事件日志位于 `logs/qa/<运行标识>/`。PID 改变、失去前台、手势失败或连续两分钟呈现时间戳停滞均失败。若系统不给有效 SurfaceView latency 或内存数据，汇总为 `inconclusive`；不会用 Compose 帧统计替代。

退出码：`0` 为完成并通过；`2` 为失败；`3` 为运行完成但测量数据不足。确认 `summary.json` 的 `instrumentationPassed`、`driverComplete`、`activityClosedByRule` 与分钟样本，不能只凭 shell 返回码宣称验收。

SurfaceFlinger 三列中第二列为 `actualPresentTime`，实现依据 [AOSP FrameTracker.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/FrameTracker.cpp)。本脚本保存的是稀疏分钟采样的最近呈现时间戳，不能据此计算完整十分钟的帧时间分布或卡顿百分比。

## 1.3 主机解析验证

以下检查不连接设备：

```sh
python3 -m unittest discover -s tools/qa -p 'test_*.py' -v
python3 -m py_compile tools/qa/device_soak.py
```

## 1.4 SurfaceFlinger TimeStats 呈现采样

设备未提供 SurfaceView latency 或 FrameTimeline 事件时，可用 TimeStats 对模型实际呈现 fence 进行整段统计。采集器核对本包 ownerPid、精确 BLAST SurfaceView 层名和 UID，并对累计 present2present 桶做前后差分；不会清空统计。

在已经确认原 TimeStats 开关为关闭的设备上运行：

```sh
python3 tools/qa/device_soak.py \
  --expected-apk app/build/outputs/apk/debug/app-debug.apk \
  --expected-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --minutes 10 --quality standard --mode muscles \
  --timestats --timestats-original-state disabled
```

`--timestats-original-state` 必须对应实际原状态：`disabled` 会临时开启并在 finally 恢复；`enabled` 保持原开启状态；默认 `infer-empty` 只在原 dump 为空时推断。非空 dump 不暴露开启布尔值，状态不明确时采集器拒绝猜测。此功能是临时系统性能统计，可能增加统计开销。

结果见同次运行的 `timestats/summary.json`。`intervalCount` 是完成的相邻呈现间隔数，`presentedIntervalsPerWallSecond` 使用主机单调时钟快照窗口；`histogramEstimatedFps` 和间隔百分位来自整数毫秒分桶，属于近似。统计完整只表示测量有效，30 FPS 目标应结合实际计数、窗口范围及各分钟结果判断。

## 1.5 交付版安装与恢复检查

下面命令会将指定 APK 安装到已绑定设备，核验安装后的实际字节哈希，检查本地模型载入，并切到桌面后返回应用，保存同进程恢复证据与应用前台截图：

```sh
python3 tools/qa/release_smoke.py app/build/outputs/apk/release/app-release.apk
```

输出为 `manifests/release-device.json`、`logs/release-smoke/` 与 `outputs/release-smoke/`。该检查使用交付版自身的界面，不调用与调试版编译绑定的 Compose 测试代码。

## 1.6 FrameTimeline 呈现数据

当设备的 SurfaceFlinger latency 不返回帧记录时，可同步采集临时 Perfetto 会话。官方工具安装在项目 `.tools/perfetto/`，官方启动器只用于读取平台与 SHA-256 清单；二进制直接下载并校验，不运行会向用户目录写缓存的官方启动器。首次联网准备：

```sh
python3 tools/qa/bootstrap_perfetto.py
```

对上节耐久命令追加 `--frametimeline`。脚本在实际模型手势驱动就绪后开始纯 FrameTimeline 追踪，32 MB 环形缓冲，时限与 `--minutes` 一致；每次会话使用唯一文件名。退出仅向命令行仍含该 trace 路径的 Perfetto PID 发送 TERM，拉取后保留原始 trace，不修改系统持久设置。

单独重新分析已拉取文件：

```sh
python3 tools/qa/frametimeline.py outputs/qa/<运行标识>/model-frametimeline.perfetto-trace \
  --output outputs/qa/<运行标识>/frametimeline --expected-seconds 600 --pid <测试报告的应用PID>
```

分析严格要求本包 `SurfaceView` 的 actual surface frame，以 `display_frame_token` 关联 SurfaceFlinger actual DisplayFrame，使用后者 `ts + dur` 作为呈现时间；应用 slice 结束仅代表 GPU/提交结束，不能充当实际呈现。结果按实际呈现时间去重后计算 FPS、帧间隔分位数与逐秒呈现数量，并检查观察区间和 trace 覆盖/解析错误。

[Perfetto 官方 FrameTimeline 文档](https://perfetto.dev/docs/data-sources/frametimeline) 当前仍注明 SurfaceViews 可能不受支持。数据源存在不能证明模型帧存在；若 trace 仅包含本应用 Compose 顶层窗口，模型呈现测量仍为 `inconclusive`。工具会保留 `own-app-layers.csv`、匹配呈现原始行与统计查询，便于核查。
