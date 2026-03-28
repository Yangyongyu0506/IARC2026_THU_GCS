# Android Multi-Drone Setpoint Controller

一个用于多无人机控制的 Android 应用，支持按键和语音输入，基于 ROS2 FLU 坐标系发送绝对目标点。

## 功能概览

- 按键控制（Forward / Backward / Left / Right / Up / Down）
- Takeoff / Land 指令发送（独立指令，不改坐标）
- 语音控制（Vosk 本地离线识别，不依赖互联网）
- 双重确认机制（第一次识别进入确认态，第二次一致才执行）
- 20Hz UDP 持续发送当前 setpoint
- 支持广播发送（默认 `255.255.255.255`）
- 状态可视化：当前位置、识别原始文本、系统状态

## 控制模型

采用 ROS2 FLU 坐标系：

- `x`: Forward（前）
- `y`: Left（左）
- `z`: Up（上）

状态定义：

```text
state = (seq_id, x, y, z, yaw)
```

当前实现中坐标和 yaw 都是整数（Int）发送：

```text
seq_id,x,y,z,yaw
```

示例：

```text
105,3,1,2,0
```

## 指令与行为

### 按键

- Forward: `x += 1`
- Backward: `x -= 1`
- Left: `y += 1`
- Right: `y -= 1`
- Up: `z += 1`
- Down: `z -= 1`
- Takeoff / Land: 发送即时指令字符串 `TAKEOFF` 或 `LAND`

每次有效操作都会 `seq_id++`。

### 语音命令映射

- forward / 前进
- back / 后退
- left / 左
- right / 右
- up / 上升
- down / 下降
- takeoff / 起飞
- land / 降落
- stop / 停止

## 校验机制（双重确认）

语音状态机：

- `IDLE`
- `WAIT_CONFIRM`

流程：

1. 第一次识别到命令后，不执行，进入 `WAIT_CONFIRM`
2. 第二次识别若与第一次一致，才执行命令
3. 不一致则丢弃，回到 `IDLE`

这样可显著减少误触发。

## 广播与发送机制

- 周期：50ms（20Hz）
- 通道：UDP DatagramSocket
- 线程：单线程调度器，避免阻塞 UI
- 默认目标：
  - IP: `255.255.255.255`
  - Port: `5005`

此外，Takeoff/Land 采用“即时发一包指令字符串”方式。

## Failsafe 机制

在 `onPause` / `onDestroy`：

- `seq_id++`
- 立即发送一次当前目标点（悬停语义）

## 语音识别（Vosk 离线）

本项目已从系统 `SpeechRecognizer` 切换为 Vosk，本地离线识别，无需联网。

关键点：

- 启动时从 `assets/model` 拷贝模型到应用私有目录
- 使用 `Model(path)` + `SpeechService` 持续监听
- 实时显示 partial 结果和 final 结果原始文本

模型路径：

- 资产目录：`app/src/main/assets/model/`
- 运行目录：`filesDir/vosk-model`

## 快速开始

### 1) 准备离线模型

确保以下目录存在（已安装英文模型时会有）：

```text
app/src/main/assets/model/am
app/src/main/assets/model/conf
app/src/main/assets/model/graph
app/src/main/assets/model/ivector
```

### 2) 构建

```bash
./gradlew :app:assembleDebug
```

### 3) 安装

```bash
./gradlew :app:installDebug
```

### 4) 使用

1. 打开 App，填写目标 IP/端口（或使用默认广播）
2. 使用按键控制移动目标点
3. 点击 `Start Voice Recognition`
4. 观察 `Voice Raw` 文本和状态提示

## 关键文件

- 业务主逻辑：`app/src/main/java/com/example/dronecontroller/MainActivity.kt`
- 主界面布局：`app/src/main/res/layout/activity_main.xml`
- 文案资源：`app/src/main/res/values/strings.xml`
- 清单权限：`app/src/main/AndroidManifest.xml`

## 依赖与兼容性

- Android Gradle Plugin: `9.1.0`
- Vosk Android: `0.3.75`

> 说明：已升级 Vosk 版本以规避 Android 15+ 16KB page size 对齐问题（旧版 `libvosk.so` 可能不兼容）。

## 常见报错与解决

### 1) `voice model not ready`

可能原因：模型未打包/拷贝失败/旧缓存干扰。

处理：

- 确认 `app/src/main/assets/model` 下有完整模型目录（am/conf/graph/ivector）
- 卸载旧 App 或清除应用数据后重装
- 首次启动等待模型加载完成（状态从 loading 到 ready）

### 2) `Android resource linking failed`（settings_* not found）

原因：历史 settings 页面资源残留（`activity_settings.xml`）但对应字符串已删除。

处理：

- 删除残留 settings 布局/Activity，保持资源一致

### 3) `SDK location not found`

原因：`local.properties` 里的 `sdk.dir` 不可用。

处理：

- 配置正确 Android SDK 路径，或设置 `ANDROID_HOME`

### 4) `APK is not compatible with 16 KB devices`

原因：native so（如旧版 Vosk）未满足 16KB 对齐要求。

处理：

- 升级相关依赖到兼容版本（本项目已使用 `vosk-android:0.3.75`）

## 踩坑记录

- 仅“偏好离线”的系统语音识别并不稳定，且设备差异大，最终改用 Vosk。
- 语音功能删除/替换时，需同步清理布局和字符串，否则资源链接会失败。
- 离线模型加载建议使用“assets -> app 私有目录 -> Model(path)”的显式流程，可观察、可调试。
- 广播控制可快速覆盖多机，但网络环境复杂时建议确认子网广播策略与端口放通。

## 备注

- 当前版本保留 `Settings` 功能停用状态（步长固定为 `1`）。
- 坐标发送为整数，坐标尺度/单位转换由无人机端处理。
