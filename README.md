# MineCrasht

Android 多无人机语音/按键控制器（ROS2 FLU + JSON UDP + 离线 Vosk 识别 + 地雷栅格地图）。

## 功能概览

- 按键控制：Forward / Backward / Left / Right / Up / Down
- 状态控制：Arm / Disarm（即时 JSON 指令）
- 安全控制：Stop -> 发送 hold
- 语音控制：Vosk 离线识别（无网可用）
- 语音容错：Levenshtein 编辑距离 + 双重确认机制
- 控制发送：20Hz JSON UDP 持续广播 move setpoint
- 地图页面：实时监听 UDP 地雷坐标并渲染栅格地图
- 地图联动：Start Voice 自动进入地图，Stop Voice 自动退出地图

## 控制模型

- 坐标系：ROS2 FLU
  - `x` 前（Forward）
  - `y` 左（Left）
  - `z` 上（Up）
- 状态定义：`state = (seq, x, y, z, yaw)`
- 当前实现状态变量均为 `Int`：`x/y/z/yaw/dx/dy/dz`

## JSON UDP 协议

### move（20Hz）

```json
{"seq":105,"cmd":"move","x":3,"y":1,"z":2,"yaw":0}
```

### arm / disarm（即时）

```json
{"seq":106,"cmd":"arm"}
{"seq":107,"cmd":"disarm"}
```

### hold（failsafe / stop）

```json
{"seq":108,"cmd":"hold"}
```

### 地雷回传（监听）

```json
{"x":100,"y":100}
```

字段约定：

- 控制发送字段：`seq` / `cmd` / `x` / `y` / `z` / `yaw`
- 地雷回传字段：`x` / `y`（整数）

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

每次有效操作都会 `seq++`。

## 语音识别与容错

语音模块位置：`app/src/main/java/com/example/dronecontroller/VoiceCommandProcessor.kt`

提供：

- `levenshtein(a, b)`
- `matchCommand(input)`
- `parseVoiceCommand(input)`
- `VoiceCommandProcessor.process(input)`

标准语音命令集合：

- `forward`
- `back`
- `left`
- `right`
- `up`
- `down`
- `arm`
- `lock`（映射到 Disarm）
- `stop`

注意：为提升识别稳定性，语音侧使用 `lock` 替代 `disarm`。

预处理：

- 小写化
- 去首尾空白
- 去除非字母

距离阈值：

- 输入长度 `<= 3`：允许编辑距离 `<= 1`
- 输入长度 `> 3`：允许编辑距离 `<= 2`

双重确认状态机：

- `IDLE`
- `WAIT_CONFIRM`

流程：

1. 第一次匹配命令，进入 `WAIT_CONFIRM`
2. 第二次匹配一致才执行
3. 不一致则丢弃并复位

日志示例：

```text
Raw Input: op, Matched: up, Distance: 1, State: WAIT_CONFIRM
```

## 地雷栅格地图（MapActivity）

地图相关文件：

- `app/src/main/java/com/example/dronecontroller/MapActivity.kt`
- `app/src/main/java/com/example/dronecontroller/MapRenderView.kt`
- `app/src/main/res/layout/activity_map.xml`

渲染规则：

- 原点在左下角 `(0,0)`
- `x` 向右为正，`y` 向上为正
- 坐标采用累计点集（历史点持续保留）
- 栅格尺寸动态扩展到 `maxX/maxY`
- 每个格点为正方形（不是矩形）
- 有地雷的格子填充为黑色

联动规则：

- 点击 Start Voice -> 进入地图实时界面
- 点击 Stop Voice -> 自动退出地图回调试界面
- 进入地图后，不中断语音识别和指令发送线程
- 地图界面实时显示语音状态和识别文本

## 线程与并发

- 控制发送：`ScheduledExecutorService`（50ms 周期）
- 语音识别：Vosk `SpeechService` 持续监听
- 地图监听：独立 UDP 监听线程
- UI 更新：切回主线程渲染

## Failsafe

在 `onPause` / `onDestroy`（非切换地图场景）发送：

```json
{"seq":N,"cmd":"hold"}
```

## Settings 配置项

`SettingsActivity` 可配置并持久化：

- `dx` / `dy` / `dz`（正整数）
- 广播发送 IP / Port
- 监听 IP / Port

默认值：

- 广播发送：`255.255.255.255:5005`
- 监听：`0.0.0.0:6006`

## 快速使用

1. 准备 Vosk 模型到 `app/src/main/assets/model/`
2. 构建：

```bash
./gradlew :app:assembleDebug
```

3. 安装：

```bash
./gradlew :app:installDebug
```

4. 在 Settings 中配置发送/监听 IP 和端口
5. 点击 Start Voice 进入地图，发送回传坐标验证渲染

## 地雷回传测试脚本（Python）

```python
#!/usr/bin/env python3
import argparse
import json
import random
import socket
import time


def main():
    parser = argparse.ArgumentParser(description="UDP mine position sender")
    parser.add_argument("--ip", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6006)
    parser.add_argument("--interval", type=float, default=0.2)
    parser.add_argument("--max-x", type=int, default=20)
    parser.add_argument("--max-y", type=int, default=20)
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        while True:
            payload = {
                "x": random.randint(0, args.max_x),
                "y": random.randint(0, args.max_y),
            }
            sock.sendto(json.dumps(payload).encode("utf-8"), (args.ip, args.port))
            print(payload)
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()


if __name__ == "__main__":
    main()
```

## 关键文件

- 主控制：`app/src/main/java/com/example/dronecontroller/MainActivity.kt`
- 语音容错：`app/src/main/java/com/example/dronecontroller/VoiceCommandProcessor.kt`
- 地图页面：`app/src/main/java/com/example/dronecontroller/MapActivity.kt`
- 栅格视图：`app/src/main/java/com/example/dronecontroller/MapRenderView.kt`
- 设置页面：`app/src/main/java/com/example/dronecontroller/SettingsActivity.kt`
- 配置常量：`app/src/main/java/com/example/dronecontroller/AppPrefs.kt`
- 语音共享状态：`app/src/main/java/com/example/dronecontroller/VoiceSharedState.kt`
- 主布局：`app/src/main/res/layout/activity_main.xml`
- 地图布局：`app/src/main/res/layout/activity_map.xml`
- 设置布局：`app/src/main/res/layout/activity_settings.xml`

## 常见问题

### 1) `voice model not ready`

- 检查 `assets/model` 是否完整（`am/conf/graph/ivector`）
- 清除应用数据后重试
- 首次启动需等待模型复制和加载

### 2) 16KB 页大小兼容报错

- 使用 `vosk-android:0.3.75` 或更高版本

### 3) 地图无点显示

- 检查 Settings 的监听 IP/Port
- 确认测试脚本目标地址与监听配置一致
- 回传 JSON 必须包含整数 `x/y`

### 4) 语音停止后地图未关闭

- 通过 Stop Voice 或返回键触发关闭流程
- 确认广播联动 action 未被改动
