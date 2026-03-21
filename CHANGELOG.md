# Changelog

本项目所有重要变更均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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

[1.0.1]: https://github.com/whyun-android/witv/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/whyun-android/witv/releases/tag/v1.0.0
