# 导航屏伴侣 App（手机端）

手机跑定位与算路，通过 BLE 把导航指令推给圆屏。圆屏只管显示。

工程结构已按「能不能不要第三方 SDK」拆成两个变体：

| 变体 | 依赖 | 能做什么 | 适用阶段 |
|---|---|---|---|
| **lite** | 零依赖（只用手机自带 GPS） | 定位、航向、车速推送；验证 BLE 链路 | **先跑这个** |
| **amap** | 高德定位 aar + 搜索 aar + Key | 搜地点、驾车算路、转向指令、偏航重算 | 正式使用 |

---

## 一、最快拿到 APK（不用装 Android Studio）

适合只想先装到手机上验证 BLE 的场景。走 GitHub Actions 自动编译 **lite 版**：

1. 把整个 `navscreen-firmware` 目录推到一个 GitHub 仓库（公开或私有都行）
2. 推完等几分钟，打开仓库的 **Actions** 页，会看到一个 `Android APK` 工作流在跑
3. 跑完后点进去，最下方 **Artifacts** 里下载 `navscreen-lite-apk`
4. 解压得到 `app-lite-debug.apk`，传到手机安装（需允许「未知来源」）

> 只改 `android/` 目录下的文件才会触发重新编译。

## 二、用 Android Studio 编译（要 amap 完整版必须走这条）

1. Android Studio（Hedgehog 2023.1 或更新）打开 `android` 目录，等待 Gradle 同步
2. 左下角 **Build Variants** 面板里选变体：
   - 先选 `liteDebug` → Run，验证蓝牙链路
   - 再切 `amapDebug`，装好下面的 Key 和 aar 后 Run

### amap 变体的两个前置条件

**① 申请高德 Key**
到 https://lbs.amap.com 注册 → 控制台创建应用 → 添加 Key，平台选 **Android**，填包名 `com.example.navscreen` 和调试版 SHA1（命令行 `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey`，密码 `android`）。
拿到 Key 后填进 `app/src/main/AndroidManifest.xml`：

```xml
<meta-data android:name="com.amap.api.v2.apikey" android:value="把你申请的Key粘这里" />
```

**② 下载高德 SDK 的 aar**
在高德官网下载 **Android 定位 SDK** 和 **Android 搜索 SDK**（只要这两个，不需要导航 SDK、不需要 3D 地图），解压后把里面的 `.aar` 文件全部拷进 `app/libs/`（没有就新建这个目录）。

> Key 不对或 aar 没放，表现是「搜索没结果、定位报错 12/1002」，不是代码问题。

---

## 三、使用步骤

1. 手机打开 App，授权定位 / 蓝牙 / 通知权限
2. 圆屏通电，等它广播（设备名 `NavScreen`）
3. App 点 **开始推送**（先不设目的地）
   - 圆屏顶部状态条出现「手机 XX%」→ **BLE 链路通了**
4. 设目的地：
   - amap 版：输入「万达广场」→ 搜索 → 点一条 → 再点开始推送
   - 任意版本：直接填 `29.84,115.24` 这种「纬度,经度」→ 搜索 → 选中 → 开始推送
5. 手机放兜里，圆屏显示转向箭头、距离、剩余里程

⚠️ 国产安卓会杀后台，装完去系统设置里给这个 App 开**自启动 + 后台运行 + 电池无限制**，否则骑到一半推送断了。

---

## 四、源码说明

```
app/src/main/java/com/example/navscreen/
├── NavProvider.kt    地图能力抽象（NavFix / RouteStep / RoutePlan / PoiHit）
│                     + NavProviderFactory（反射加载实现，缺 SDK 也不崩）
├── NavProtocol.kt    与圆屏的通信组帧（AA 55 TYPE LEN PAYLOAD CRC8）
├── BleClient.kt      BLE 扫描/连接/写特征（队列串行发送，避免 Android 丢包）
├── PathTracker.kt    路径跟随：当前位置投影到路线 → 转向/距离/剩余
├── NavService.kt     前台服务：定位 → 算路 → 组帧 → 1Hz 推送
└── MainActivity.kt   界面：搜目的地 / 选点 / 开始停止

app/src/lite/kotlin/.../NavProviderImpl.kt   系统 GPS 实现（含 WGS84→GCJ02 纠偏）
app/src/amap/kotlin/.../NavProviderImpl.kt   高德实现（定位 + POI + 算路）
```

**坐标系约定**：手机推给圆屏的一律是 **GCJ-02**。高德返回的就是 GCJ-02，直接用；lite 版用系统 GPS 拿到 WGS-84，在 `Wgs2Gcj` 里转换后再发——不转的话地图会偏 300~500 米。

**为什么用反射加载实现**：保证 lite 变体在没有高德 aar 时也能编译通过（main 源码里没有任何 `import com.amap...`）。

通信协议详见 `../docs/ble_protocol.md`，两端唯一契约，改哪边都要同步。
