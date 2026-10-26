# 圆形车机导航屏 · 固件工程

基于 **ESP-IDF v5.x + LVGL 8.3 + esp_lvgl_port**，目标芯片 **ESP32-S3 (N16R8)**。

**当前需求已锁定（v1.2 · 手机算路 + BLE 推送路线）**：

- 屏幕 **不小于 3 寸**（确定 3.4 寸 480×480 圆形 RGB 屏）
- **不带喇叭**，无语音播报
- **不做音乐与通话**功能（蓝牙 A2DP/HFP 全部移除）
- **算路在手机端**：高德 SDK 定位 + 路径规划 → BLE 推转向指令、位置、路线点 → 圆屏只显示

界面共 **3 页**：导航 / 仪表 / 设置。旋钮短按在这三页之间循环。

两端代码均已写完可直接编译：屏端 `main/`，手机端 `android/`（见 `android/README.md`）。

---

## 一、目录结构

```
navscreen-firmware/
├── CMakeLists.txt              顶层工程
├── sdkconfig.defaults          芯片/PSRAM/LVGL/蓝牙配置
├── .github/workflows/build.yml GitHub Actions 云端编译（免装 ESP-IDF）
├── main/
│   ├── CMakeLists.txt
│   ├── idf_component.yml       LVGL 8.3 + esp_lvgl_port 依赖
│   ├── include/config.h        ★ 所有引脚与功能开关，按你的屏改这里
│   ├── include/drivers.h
│   ├── include/ui_screens.h
│   ├── include/ble_nav.h       BLE 导航状态 / 位置 / 路线缓冲
│   ├── include/map.h           坐标纠偏 / 瓦片拼接 / 屏幕投影
│   ├── main.c                  启动流程：屏 → LVGL → 外设 → UI 任务
│   ├── ui_screens.c            三个界面 + 转向箭头 + 地图叠加渲染
│   ├── ble_nav.c               GATT Server（NavScreen）+ 帧解析
│   ├── map.c                   WGS84→GCJ02 / 瓦片拼图 / 路线折线绘制
│   └── drivers.c               GPS NMEA 解析 / EC11 旋钮 / SD 瓦片
├── docs/ble_protocol.md        手机↔圆屏通信协议（唯一契约）
├── tools/build_tiles.py        离线底图瓦片生成脚本
└── android/                    手机端 App（见 android/README.md）
```

## 二、两种编译方式（推荐第二种）

### 方式 A：本地装 ESP-IDF

```bash
# 已安装 ESP-IDF v5.1+ 并 source export.sh 后
idf.py set-target esp32s3
idf.py build
idf.py -p COM3 flash monitor
```

### 方式 B：GitHub Actions 云端编译（不用装任何环境）

1. 在 GitHub 新建空仓库（例如 `navscreen`）
2. 把 `navscreen-firmware/` 整个目录推上去
3. Actions 会自动跑编译，完成后在 **Artifacts** 下载 `navscreen-firmware-bin.zip`
4. 解压得到三个 bin，用乐鑫官方 **[ESP-Launchpad](https://espressif.github.io/esp-launchpad/)** 网页工具选 `merged-binary.bin`、地址 `0x0` 一键烧录（无需装驱动之外的任何东西）

烧录地址（不用 merged 时）：

| 文件 | 地址 |
|------|------|
| bootloader.bin | 0x0 |
| partition-table.bin | 0x8000 |
| navscreen.bin | 0x10000 |

## 三、上电前必改的三处

1. **`main/include/config.h` → `LCD_DATA_GPIO`**：16 根数据线的顺序按屏厂给的排线定义填，填错会花屏
2. **`config.h` → `LCD_*_PORCH / PCLK_HZ`**：用屏厂资料里的时序值，点不亮就把 PCLK 降到 8MHz 试试
3. **`config.h` → `FEAT_*` 开关**：第一次上电建议先关掉 `FEAT_BT`、`FEAT_AUDIO`、`FEAT_SD`，只验证「屏幕 + LVGL + 旋钮 + GPS」，逐个调通再开

## 四、旋钮操作定义

| 操作 | 行为 |
|------|------|
| 顺时针旋转 | 导航页：地图放大（80%~220%） |
| 逆时针旋转 | 导航页：地图缩小 |
| 短按 | 导航页：切换 正北朝上 / 车头朝上；仪表/设置页：进入下一页 |
| 长按 800ms | 任意页回到导航主界面 |

## 五、开发路线（建议按此顺序推进）

- [ ] **第 1 步** 3.4 寸 RGB 圆屏点亮，跑通 LVGL + 旋钮（改 `config.h` 的 LCD_DATA_GPIO 与时序）
- [ ] **第 2 步** 调通触摸（或不装触摸，纯旋钮操作）
- [ ] **第 3 步** 接 GPS，串口看 NMEA，界面速度/时间开始跳动
- [ ] **第 4 步** 离线地图：把瓦片转成 `256×256 RGB565 .raw` 存进 SD 卡 `/sdcard/tiles/<z>/<x>_<y>.raw`（`tools/build_tiles.py`）
- [ ] **第 5 步** 手机 App 装好，验证 BLE 链路（不设目的地先跑，圆屏状态条出现「手机 XX%」即通）
- [ ] **第 6 步** 接罗盘，实现「车头朝上」视角（旋转地图）
- [ ] **第 7 步** 装车：宽压供电 + 防水 + 三防漆

## 六、地图实现方案

采用**三层架构**：底图离线、定位本地、算路借网络。

```
┌─ 底图层 ── SD 卡瓦片 /sdcard/tiles/<z>/<x>_<y>.raw ── 无需网络
├─ 定位层 ── 板载 GPS/北斗（ATGM336H）── 无需手机
└─ 算路层 ── 手机 WiFi 热点 → 高德/百度 REST API ── 需要网络（可选）
```

### 6.1 离线底图（默认，永远可用）

瓦片用 `tools/build_tiles.py` 事先生成：

```bash
pip install pillow requests
python tools/build_tiles.py --lat 29.84 --lon 115.24 --radius 3 --zoom 13 14 15 --out ./tiles
# 把 tiles 整个目录拷到 SD 卡根目录 → /sdcard/tiles/...
```

- 每张 256×256 RGB565 = 128KB，z15 半径 3 约 49 张 ≈ 6MB，三层也就几十 MB，32GB 卡绰绰有余
- 坐标已在 `map.c` 里做 **WGS-84 → GCJ-02 纠偏**，否则国内底图会偏 300~600 米
- 屏上 `map_blit()` 拼 3×3 瓦片、支持缩放，读不到瓦片时**自动退回内置矢量地图**，界面不会黑屏

### 6.2 连接手机实时更新（可选，第二阶段）

ESP32-S3 本身没有 4G，**所有联网数据必须借手机的网络通道**，两条路：

| 通道 | 做法 | 耗电 | 工作量 |
|------|------|------|--------|
| **手机开 WiFi 热点**（推荐先做） | ESP32 连热点 → HTTPS 调高德路径规划/交通态势 API → cJSON 解析 → 画路线 | 手机耗电较明显 | 中：需要 API Key + mbedtls + cJSON |
| **BLE 推送**（✅ 已选定） | 自研 Android App 跑高德 SDK，把转向指令/距离/路名通过 BLE 推给屏 | 省电 | 大：要写手机端 App，代码已在 `android/` |

实时能力边界，说清楚：

- **不开热点**：底图跟随 GPS 实时移动、实时显示速度和位置 —— 能用，但不能重新算路、没有路况
- **开热点**：可实时算路、绕拥堵重算、显示路况颜色（需联网 API）
- 高德 Key 在 [lbs.amap.com](https://lbs.amap.com) 免费申请，骑行/驾车路径规划有免费额度

### 6.3 已选定的路线：手机 SDK 算路 + BLE 推送

**整套链路**：手机（高德 SDK 算路 + 定位）→ BLE → 圆屏（只显示，不算路）。

- 协议：`docs/ble_protocol.md`（帧格式 AA 55 TYPE LEN PAYLOAD CRC8，含转向枚举表）
- 屏端：`main/ble_nav.c` —— GATT Server，设备名 `NavScreen`，收帧后更新导航状态
- 手机端：`android/` —— Kotlin 工程，`NavProtocol.kt` 组帧、`BleClient.kt` 连接、`NavService.kt` 前台服务推送
- 屏端 UI 已做**双模切换**：手机连着就用手机指令；断连超过 10 秒自动回退到板载 GPS + 离线底图，不会卡住旧数据

详见 [`android/README.md`](android/README.md)。

### 6.4 屏端收到的四类数据与渲染（已实现）

| 帧 | 内容 | 屏端处理 |
|----|------|----------|
| 0x01 NAV_STATE | 转向 / 距离 / 剩余 / 车速 / 路名 | 底部蓝条文案 + 转向箭头图标 |
| 0x02 POSITION | 手机经纬度 + 航向 | 地图中心点 + 定位三角旋转 |
| 0x03 TEXT | 目的地 / 限速 / 提示 | 剩余里程卡、限速牌 |
| 0x05 ROUTE | 规划路线点（GCJ-02） | `map_draw_route()` 在底图上叠蓝线 |

坐标系约定：**手机推来的一律是 GCJ-02，屏端不做纠偏**；只有板载 GPS（WGS-84）
在 `map_blit()` 内部转 GCJ-02。两条数据混用时不会互相错位。

手机断连 10 秒 → `ble_nav_is_stale()` 判定掉线 → 自动切回板载 GPS + 离线底图。

### 6.5 转向箭头绘制

`ui_screens.c` 的 `draw_turn_icon()` 用矢量方式画 12 种箭头
（直行 / 左右转 / 稍左右 / 掉头 / 靠左右 / 到达 / 环岛 / 重算），
画在 56×56 的小 canvas 上，蓝底白箭头压在底部蓝条左侧。不依赖任何图片资源。

## 六点五、手机端 App 与 APK

`android/` 是一个完整的 Android Studio 工程，两个构建变体：

| 变体 | 依赖 | 能力 |
|---|---|---|
| `liteDebug` | **零第三方依赖**（系统 GPS） | 定位/航向/车速推送，用来验证 BLE 链路 |
| `amapDebug` | 高德定位 + 搜索 aar、高德 Key | 搜地点、驾车算路、转向指令、偏航重算 |

**拿 APK 最快的路**：把本目录推到 GitHub，`.github/workflows/android-apk.yml`
会自动编译 `liteDebug` 并在 Actions 的 Artifacts 里给出 APK，全程不用装 Android Studio。
详细步骤见 `android/README.md`。

## 七、已知待办

- 「车头朝上」目前只切标记，地图旋转需结合罗盘航向角做旋转变换（路线第 6 步）
- 仪表页的里程为占位数据，接里程计后替换
- 限速 / 电子眼：协议里 `TEXT` 子类型 2/3 已留通道，手机端暂无数据源（需高德导航 SDK）
- 经典蓝牙与音频代码已整体移除；日后若要加回，参考 IDF 官方 a2dp_sink / hfp_client 例程

> 代码为可编译工程骨架，实机参数（屏时序、GPIO）需按你的硬件调整；我这边无法本地编译验证，第一次 `idf.py build` 如有报错，把日志发我逐条改。
