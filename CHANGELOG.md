# Changelog

本项目所有重要变更均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.2.0] - 2026-04-13

### Added

- 直播播放新增分片预取与命中观测能力，可输出直播分片时长、预取下载耗时、缓存命中率等调试信息，便于分析卡顿、贴边播放与缓存收益

### Changed

- 直播 HLS 分片的预取策略升级：基于修正后的 `EXT-X-MEDIA-SEQUENCE` 跟踪直播窗口，并在 playlist 刷新后立即重算后续分片预取，减少窗口滑动带来的预取抖动
- 新增“启动时刷新 M3U 直播源”设置项，默认开启；进入播放页时可先刷新当前激活订阅，再使用最新频道列表起播，刷新失败时仍回退本地缓存
- 新增“使用硬盘缓存预取直播分片”设置项，默认关闭；关闭时仍保留 `m3u8` 修正能力，但不再启用直播 `ts` 的磁盘预取与缓存命中逻辑
- 直播缓存相关日志与说明文档更新，更方便定位“播放器贴近 live edge 导致预取被抢占”等问题

### Fixed

- 改善部分直播源在播放列表滑动刷新时的分片序号跟踪一致性，降低因窗口更新导致的缓存目标抖动
- 改善直播播放过程中未来分片刚进入窗口却来不及预取的问题，在 playlist 刷新后会立刻按最近播放位置补发一次预取

## [1.1.0] - 2026-03-22

### Added

- HLS 播放列表客户端修正：识别源站将 `#EXT-X-MEDIA-SEQUENCE` 误写为「最后一片序号」时，改写为第一片序号；修正 `##EXT-X-VERSION` 双井号（`HlsMediaSequenceFixUtil` + `M3u8RewritingDataSource` 注入 `DefaultDataSource`）
- 对应单元测试 `HlsMediaSequenceFixUtilTest`
- 首页「设置」分类：可点击的快捷行（`SettingsShortcutEntry` / `SettingsShortcutPresenter`），仅确认后打开设置，避免焦点路过即弹出
- 设置帮助拆分为子项：媒体信息、帮助说明、关于应用；关于中展示构建时间（`BuildConfig.BUILD_TIME_MILLIS`）
- 频道列表长按切换该行收藏（`ChannelListAdapter`）
- 超时换源偏好可在非播放页设置（`MainActivity` / `SettingsActivity` 等展示该分组）

### Changed

- Media3 升级至 **1.10.0-rc01**；`minSdk` 提升至 **23**（与 Media3 1.9+ / AndroidX 对齐）
- 首页设置浮层改为锚定窗口**左下角**；`MainActivity` `onPause` 时收起设置层，避免后台 Activity 仍挂起可见浮层
- 无 M3U 源时空状态：隐藏 Browse 根视图并为「刷新」请求焦点，避免 Leanback 抢走焦点
- 设置抽屉内从**左侧子菜单**按 **右键** 显式回到**右侧主菜单**当前分类，修复切换线路后 `notifyDataSetChanged` 导致焦点链断裂、无法返回父菜单的问题

### Fixed

- `SettingsCollapsibleFragment`：切换播放线路后子菜单刷新，方向键右键无法回到主菜单

[1.1.0]: https://github.com/whyun-android/witv/compare/v1.0.2...v1.1.0
[1.2.0]: https://github.com/whyun-android/witv/compare/v1.1.0...v1.2.0

## [1.0.2] - 2026-03-21

### Added

- 直播 HLS：`BehindLiveWindowException` 时在同源上 `seekToDefaultPosition` + `prepare` 恢复，减少误换源
- 直播稳定性：`MediaItem.LiveConfiguration`（目标/最小离边距离）、`DefaultLoadControl` 缓冲参数上调
- 网络：`DefaultHttpDataSource` 连接/读取超时 + `DefaultMediaSourceFactory`；HTTP `User-Agent` 固定为 `stagefright/1.2 (Linux;Android 7.1.2)`
- EPG 信号信息：将 `avc1` / `mp4a` 等原始 `codecs` 显示为通俗中文说明
- `MediaInfoFormatter` 单元测试；`PlayerManager` 补充 `BehindLiveWindow` 识别相关测试
- 文档：`docs/playback-and-ui-changes.md`（播放与界面改动说明）

### Changed

- 播放页设置抽屉打开时，方向键与确认键用于菜单导航，不再换台或打开频道列表；遮罩不设为可聚焦
- EPG 浮层信号信息去掉视频/音频码率与帧率，仅保留分辨率、编码、采样率、声道

## [1.0.1] - 2026-03-21

### Added

- 播放页菜单键 / F6：右侧设置抽屉（遮罩 + 面板），不离开播放界面
- 设置主菜单在右、子菜单在左：地址管理、切换源（仅播放页）、EPG、播放选项；帮助打开独立弹窗
- `SettingsCollapsibleFragment` 与 `SettingsPanelHost`，播放页与独立设置页共用同一套设置 UI
- 帮助弹窗内单独一行展示应用版本号（`versionName`）
- 应用 Logo：添加 witv 水墨风格图标（mipmap 多密度）及 Android TV banner

### Changed

- `SettingsActivity` 改为全屏承载设置 Fragment；原左右分栏布局移除
- 切换源子菜单仅显示「线路 x」，不展示播放 URL
- 设置抽屉总宽度约 480dp，便于双栏主/子菜单

### Fixed

- 播放器切换频道后陈旧回调干扰新频道播放的问题
- 所有播放源失败后未停止播放器，残留错误状态影响后续频道切换
- 切换频道时旧超时计时器未取消，可能误触发源切换
- 空源列表未清理旧播放状态

### Tests

- 新增 7 个 PlayerManager 单元测试，覆盖频道切换状态隔离、陈旧回调防护、全部失败后恢复等场景

## [1.0.0] - 2026-03-20

### Added

- 项目初始化：M3U 播放源解析与频道列表展示
- ExoPlayer 视频播放，支持 HLS / DASH / TS 流媒体
- 多播放源自动切换（超时 15 秒自动尝试下一个源）
- 手动切换播放源
- 频道上下键切换、数字键直接跳转
- 频道收藏功能（添加 / 取消收藏）
- 启动时自动播放上次观看的频道
- 首次进入频道列表自动播放第一个频道
- EPG 节目信息展示（当前播出 / 即将播出）
- 内置 Web 服务器，支持浏览器管理 M3U 播放源
- Android TV Leanback 支持
- GitHub Actions CI/CD 自动构建与签名发布

### Fixed

- 收藏状态切换后未读取数据库实际状态，导致显示不一致
- 自动播放在 Activity 重建时重复触发
- 刷新播放源时频道 ID 重建导致收藏记录级联删除

[1.0.2]: https://github.com/whyun-android/witv/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/whyun-android/witv/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/whyun-android/witv/releases/tag/v1.0.0
