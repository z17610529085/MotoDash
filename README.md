# MotoDash · 摩托车车机行车记录仪
把闲置安卓手机变成摩托车行车记录仪。支持锁屏 / 后台持续录像、双摄画中画、循环覆盖、GPS 水印、一键锁定。
## 功能
| 功能 | 说明 |
|---|---|
| 后台录像 | 前台服务 + 唤醒锁，锁屏、切后台继续录制 |
| 分段录制 | 默认 5 分钟一段，可在设置中调整（1–30 分钟） |
| 循环覆盖 | 默认上限 100GB，超限自动删除最旧文件 |
| 一键锁定 | 当前片段移入 `locked/` 目录，永不被自动删除 |
| 双摄并发 | 优先前后摄同时录制（画中画），失败自动降级单后置 |
| GPS 水印 | 速度、经纬度、时间叠加显示 |
| 视频列表 | 播放、分享、删除、锁定/解锁 |
| 开机自启 | BOOT_COMPLETED 广播 + 前台服务兜底 |
| 自定义壁纸 | 从相册选择图片作为背景 |
| 屏幕常亮 | `FLAG_KEEP_SCREEN_ON` + 横屏全屏沉浸 |
## v2.0 车机化升级

### 仪表盘主界面
- **大号速度环**：霓虹渐变进度弧 + 实时 km/h 数字 + 方位角（北/东北/东…）
- **骑行统计**：本次里程、最高速度、平均速度三栏卡片
- **顶部状态栏**：大时钟 + 日期 + 录像状态胶囊 + 天气 + 电量
- **底部 Dock**：录像 / 锁定 / 回放 / 导航 / 音乐 / 壁纸 / 设置

### 车机功能
| 功能 | 说明 |
|---|---|
| 实时天气 | Open-Meteo 免费 API（无需 Key），显示温度 + 天气图标 + 描述，15 分钟自动刷新 |
| 导航快捷 | 自动探测高德/百度/Google 地图，多选弹窗，一键唤起 |
| 音乐快捷 | 自动探测网易云/QQ音乐/酷狗，无则唤起系统播放器 |
| 电量监控 | 实时百分比 + 充电标识，低电量变黄/红 |
| 骑行统计 | GPS 里程累计（过滤漂移）、最高速、平均速（过滤静止噪声） |
| 指南针 | 基于 GPS bearing 计算 8 方位中文显示 |

### 视觉风格
- 深色仪表盘主题（`#07090C` 深空黑底）
- 霓虹青绿主色 `#00E5A0` + 青色 `#22D3EE` + 琥珀 `#FFB020` 辅助
- 玻璃拟态卡片（半透明 + 细边框 + 20dp 圆角）
- 录像按钮呼吸态切换（待机青绿 / 录制红色）

## 快速开始
### 方式一：直接下载 APK（推荐）
1. 把本仓库推送到 GitHub。
2. 进入 **Actions** 页面，选择 `Build APK`，点击 **Run workflow**。
3. 构建完成后，在 **Releases** 或 Artifacts 中下载 APK，手机直接安装。
> 也可以在本地打 tag 自动发布：`git tag v1.0.0 && git push origin v1.0.0`
### 方式二：Android Studio
1. Android Studio（Ladybug 或更新）打开项目根目录。
2. 等待 Gradle Sync 完成。
3. 连接手机，点击 Run。
## 权限说明（首次启动会依次申请）
- **相机**：录像必需
- **麦克风**：录音必需
- **通知**（Android 13+）：前台服务通知必需，否则服务会被系统限制
- **定位**：GPS 水印
- **电池优化白名单**：设置页提供跳转，建议关闭优化，避免后台被杀
## Android 14/15/16 适配要点
1. `AndroidManifest.xml` 中已声明：
   ```xml
   android:foregroundServiceType="camera|microphone|location"
   ```
   并申请 `FOREGROUND_SERVICE_CAMERA`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_LOCATION`。
2. 启动前台服务时使用 `ServiceCompat.startForeground(...)` 显式传入类型，避免 `MissingForegroundServiceTypeException`。
3. 动态申请 `POST_NOTIFICATIONS`，否则通知不显示、服务可能被判定为无效。
4. `FLAG_KEEP_SCREEN_ON` 在 Activity 中设置。
5. 双摄 API `availableConcurrentCameraInfos` 全程 `try-catch`，任何异常立即 `unbindAll()` 并回退单摄。
6. 视频保存在应用私有外部目录 `Android/data/<pkg>/files/Movies/MotoDash/`，无需存储权限；分享使用 `FileProvider`。
## 目录结构
```
app/src/main/java/com/motodash/recorder/
├── MotoDashApp.kt              # Application，通知渠道
├── camera/
│   └── CameraController.kt     # CameraX 双摄 + 降级
├── data/
│   ├── LocationHelper.kt       # GPS 速度/坐标
│   ├── SettingsRepository.kt   # 设置存储
│   ├── StorageManager.kt       # 文件管理、循环清理、锁定
│   └── VideoFile.kt
├── service/
│   ├── BootReceiver.kt         # 开机自启
│   └── RecordingService.kt     # 前台服务
└── ui/
    ├── GalleryActivity.kt      # 视频列表
    ├── MainActivity.kt         # 主界面
    ├── MainViewModel.kt
    ├── PlayerActivity.kt       # 播放
    ├── SettingsActivity.kt     # 设置
    ├── VideoAdapter.kt
    └── WatermarkView.kt        # 水印绘制
```
## 已知限制
- **水印烧录**：CameraX 原生不支持把 UI 叠加层写进视频像素。当前实现为预览层叠加（屏幕上看得到速度/坐标/时间）。如需把水印真正烧进视频文件，需要引入 OpenGL `OverlayEffect`（CameraX 1.5+）或 FFmpeg 后处理，本仓库未包含该部分。
- **开机自启**：Android 10+ 禁止后台启动 Activity，部分厂商 ROM 需要手动在「自启动管理」中允许本应用。设置页提供跳转入口。
- **双摄**：仅部分机型支持并发相机（如 Pixel、部分三星/小米）。不支持时自动降级，不会崩溃。
## License
MIT