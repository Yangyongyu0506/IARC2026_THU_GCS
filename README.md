# Android Multi-Drone Voice/Button Controller

Android 多无人机语音/按键控制器，采用 ROS2 FLU 坐标系，使用 JSON over UDP 进行 20Hz setpoint 广播发送。

## 功能概览

- 按键控制位置：Forward / Backward / Left / Right / Up / Down
- 状态控制：Arm / Disarm（即时 JSON 指令）
- 安全控制：Stop -> 发送 hold
- 语音控制：Vosk 离线识别（无网可用）
- 语音容错：Levenshtein 编辑距离模糊匹配
- 双重确认：两次识别一致才执行命令
- 广播发送：默认 `255.255.255.255:5005`
- 状态显示：当前位置、原始识别文本、系统状态

## 坐标与控制模型

- 坐标系：ROS2 FLU
  - `x` 前
  - `y` 左
  - `z` 上
- 状态定义：`state = (seq_id, x, y, z, yaw)`
- 当前实现全部状态变量为 `Int`：`x/y/z/yaw/dx/dy/dz`
- 控制方式：Setpoint 控制（发送绝对目标点，不发速度）

## UDP JSON 协议

### move（20Hz 持续发送）

```json
{"seq":105,"cmd":"move","x":3,"y":1,"z":2,"yaw":0}
```

### arm / disarm（即时发送）

```json
{"seq":106,"cmd":"arm"}
{"seq":107,"cmd":"disarm"}
```

### hold（failsafe / stop）

```json
{"seq":108,"cmd":"hold"}
```

字段说明：

- `seq`: 递增序列号
- `cmd`: `move` / `arm` / `disarm` / `hold`

## 按键行为

- Forward: `x += dx`
- Backward: `x -= dx`
- Left: `y += dy`
- Right: `y -= dy`
- Up: `z += dz`
- Down: `z -= dz`
- Arm: 发送 `{"cmd":"arm"}`
- Disarm: 发送 `{"cmd":"disarm"}`
- Stop: 发送 `{"cmd":"hold"}`

位置变化或状态指令都会触发 `seq++`。

## 语音容错模块（已集成）

位置：`app/src/main/java/com/example/dronecontroller/VoiceCommandProcessor.kt`

提供函数：

- `levenshtein(a: String, b: String): Int`
- `matchCommand(input: String): String?`
- `parseVoiceCommand(input: String): String?`
- `VoiceCommandProcessor.process(input: String): String?`

标准指令集合：

- `forward`
- `back`
- `left`
- `right`
- `up`
- `down`
- `arm`
- `disarm`
- `stop`

预处理：

- 小写化
- 去首尾空白
- 去除非字母字符

阈值：

- 输入长度 `<= 3`：允许距离 `<= 1`
- 输入长度 `> 3`：允许距离 `<= 2`

示例：

- `op -> up`
- `forword -> forward`
- `lef -> left`

## 双重确认机制

状态机：

- `IDLE`
- `WAIT_CONFIRM`

流程：

1. 第一次识别成功：进入 `WAIT_CONFIRM`
2. 第二次识别与第一次一致：返回并执行命令
3. 不一致：丢弃并重置状态

日志示例：

```text
Raw Input: op, Matched: up, Distance: 1, State: WAIT_CONFIRM
```

## 发送与线程模型

- 定时发送：`ScheduledExecutorService`
- 周期：50ms（20Hz）
- UDP 发送在线程池执行，不阻塞 UI
- Socket：`DatagramSocket`，开启 `broadcast = true`

## Failsafe

在 `onPause` / `onDestroy`：

- `seq++`
- 发送 `{"cmd":"hold"}`

## 设置页（步长）

`SettingsActivity` 支持配置：

- `dx`
- `dy`
- `dz`

要求为正整数，使用 `SharedPreferences` 存储。

## 离线语音（Vosk）

模型加载流程：

1. 从 `assets/model` 拷贝到 `filesDir/vosk-model`
2. 使用 `Model(path)` 创建模型
3. `SpeechService` 持续监听

模型目录要求：

```text
app/src/main/assets/model/am
app/src/main/assets/model/conf
app/src/main/assets/model/graph
app/src/main/assets/model/ivector
```

## 快速使用

1. 安装并确保模型文件已放入 `app/src/main/assets/model/`
2. 构建：`./gradlew :app:assembleDebug`
3. 安装：`./gradlew :app:installDebug`
4. 打开 App，设置目标 IP/端口
5. 使用按键或语音控制

## 关键文件

- 主逻辑：`app/src/main/java/com/example/dronecontroller/MainActivity.kt`
- 语音容错：`app/src/main/java/com/example/dronecontroller/VoiceCommandProcessor.kt`
- 设置页：`app/src/main/java/com/example/dronecontroller/SettingsActivity.kt`
- 主布局：`app/src/main/res/layout/activity_main.xml`
- 设置布局：`app/src/main/res/layout/activity_settings.xml`
- 文案：`app/src/main/res/values/strings.xml`
- 清单：`app/src/main/AndroidManifest.xml`

## 常见问题与解决

### 1) `voice model not ready`

原因：模型未正确打包或首次复制失败。

处理：

- 检查 `assets/model` 目录完整性
- 清除应用数据或重装
- 首次启动等待模型加载完成

### 2) `APK ... not compatible with 16 KB devices`

原因：旧版 native so 不满足 16KB page size。

处理：

- 使用新版 Vosk：`vosk-android:0.3.75`

### 3) `Android resource linking failed`（资源找不到）

原因：布局与字符串资源不同步。

处理：

- 确保布局中引用的 `@string/...` 均存在

### 4) `SDK location not found`

原因：本地 Android SDK 路径配置错误。

处理：

- 配置 `local.properties` 的 `sdk.dir`
- 或设置 `ANDROID_HOME`

## 踩坑记录

- 系统 `SpeechRecognizer` 即使设置离线偏好，设备差异仍大，已改为 Vosk。
- 删除功能时需同步删除相关布局/资源，否则会触发资源链接错误。
- Vosk 模型加载采用显式拷贝流程，便于定位设备端失败问题。
- 将状态与步长统一改为 Int，可减少 JSON 体积和解析复杂度。
