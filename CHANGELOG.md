# Changelog

本项目所有重要变更均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added

- **UDP / RTP 组播播放**：新增 `udp://` 与 `rtp://` 频道支持（`MulticastDataSource`），
  自建数据源可配置 `SO_RCVBUF`（默认 4MB，避免高码率组播丢包花屏）、按 `ConnectivityManager`
  的活动网络选择网卡 join（多网卡盒子 join 错网卡会收不到流）、自动申请 `MulticastLock`
  （新增 `CHANGE_WIFI_MULTICAST_STATE` 权限）
- **RTP 剥头与乱序重排**：`RtpPacketUtil` 解析 RTP 固定头（CSRC / 扩展头 / 尾部 padding），
  `RtpReorderBuffer` 提供 32 包重排窗口且收包路径零分配；源标注 `rtp://` 但实际推裸 TS 时自动回退透传
- **组播转单播代理（udpxy）**：设置「组播 / UDP」分类与 Web 管理页均可配置代理前缀，
  配置后 `rtp://239.1.1.1:1234` 自动改写为 `http://<proxy>/rtp/239.1.1.1:1234`；
  这是绝大多数家宽环境下唯一可行的组播方案
- **组播地址规范化**：`MulticastUrlUtil` 统一处理 VLC 风格 `udp://@`、`udp://@@` 与
  SSM 源地址段 `rtp://@src@group:port` 等写法
- 新增单元测试 `MulticastUrlUtilTest`、`RtpPacketUtilTest`、`RtpReorderBufferTest`、
  `SwitchableLoadControlTest`
- 新增文档 `docs/multicast-udp-rtp.md`（配置方式、地址写法、实现要点与排查手册）
- **FFmpeg 软解**：引入 `io.github.anilbeesetti:nextlib-media3ext:1.10.0-0.12.1`，
  补上电视盒子硬解常缺的编码——组播 TS 高频的 MPEG-2 视频与 MP2/AC3/DTS 音频。
  版本号中的 `1.10.0` 必须与 `media3_version` 一致，升级 Media3 时需同步
- **解码方式设置**（`PlaybackDecoderMode`）：硬解优先（默认）/ 软解优先 / 仅硬解。
  切换后立即重建播放器并续播当前频道（`RenderersFactory` 只能在 ExoPlayer 构建时指定）；
  原生库不可用时设置页会给出提示
- 全局开启 `setEnableDecoderFallback(true)`：某个 MediaCodec 解码器 configure 失败时
  依次尝试同一渲染器里的其它 MediaCodec 解码器。注意它不会退到 FFmpeg 渲染器
  （渲染器在 supportsFormat 阶段即已选定），硬解整体不可用时需切到「软解优先」档
- 新增单元测试 `PlaybackDecoderModeTest`

### Changed

- 缓冲策略按流类型分档（`SwitchableLoadControl`）：HTTP/HLS 维持原有 35s/90s/6s/15s；
  UDP/RTP（含经 udpxy 代理的）改为 8s/30s/1.5s/3s。组播是实时推流，服务端没有可回拉的缓冲，
  厚缓冲只会单纯增加开播等待。两档共用同一内存池，换台时只切阈值、不重建 ExoPlayer
- 直播 TS 解复用调参（`TsExtractor.MODE_SINGLE_PMT` + `FLAG_ALLOW_NON_IDR_KEYFRAMES`）
  经 `MulticastAwareExtractorsFactory` **只作用于 `udp://` / `rtp://`**，起播更快；
  HTTP 直连 `.ts` 等保持 Media3 默认——`MODE_SINGLE_PMT` 的「只有一个 PMT」假定
  对来源不明的 TS（多节目 MPTS、中途重发 PMT）不成立，会导致 PID 映射走样
  （症状为 `PesReader: Unexpected start code prefix`）。HLS 走
  `DefaultHlsExtractorFactory`，两档都不影响它
- 新增单元测试 `MulticastAwareExtractorsFactoryTest`
- debug 构建挂载 ExoPlayer 官方 `EventLogger`（tag `PlayerManager-ev`），
  可看到掉帧数、渲染器就绪状态、解码器初始化与状态变化原因；
  并新增重缓冲计数日志（`Rebuffer #N ... after only Xms of playback`），
  用于区分「缓冲慢慢耗干」与「起播后几十毫秒就又卡」这两种完全不同的故障
- 组播收流新增诊断日志，用于区分「断流」与「播放器卡住」：每 10 秒一条吞吐心跳
  （包速率/码率/累计丢包）、收包超时时打印距上一个包的时长与会话累计量、
  `SO_RCVBUF` 被内核压缩时显式告警、关流时输出会话总结
- **组播断流自动重新入组**（`MulticastStallPolicy`）：修复「组播播放数分钟后卡住、
  退出频道重进才恢复」。成因是 IGMP 成员关系被上游交换机剪掉，重进之所以有效是因为
  它重新 `joinGroup` 补发了 Membership Report。现在收包超时会原地离组再入组
  （socket 与端口不变），健康的流永不触发，默认最多 2 次、约 9 秒后仍无数据才报错换源。
  新增单元测试 `MulticastStallPolicyTest`
- `PlayerManager` 新增 `getCurrentResolvedPlaybackUrl()`，与原始配置地址区分，便于排查组播改写
- release 包限定 ABI 为 `armeabi-v7a` + `arm64-v8a`（Android TV 盒子全是 ARM），
  避免 FFmpeg 原生库把 x86/x86_64 一起带上；debug 包保留全部 ABI 以便模拟器调试。
  release APK 由约 7.7MB 增至约 18.9MB，其中原生库约 10.3MB

### Fixed

- **修复「起播成功后卡在缓冲再也不恢复」**：起播超时在 `STATE_READY` 时被取消后从未重新武装，
  之后再进入 `STATE_BUFFERING` 就完全没有定时器看管。实测遇到过组播数据以满码率持续进来
  （8Mbps、`rtpLost` 不再增长）、播放器却永远停在 BUFFERING 的情况。
  新增重缓冲看门狗：超时后先原地重开当前线路（新解复用器、新采样队列、时间戳重新对齐），
  连续 `MAX_STALL_RECOVERIES`（2）次无效才换源——单线路频道换源等于放弃，应优先原地恢复。
  看门狗只在成功起播过之后才武装，与起播超时互斥，避免同一时刻两个定时器各做一次动作
- **组播收包改为独立线程 + 环形缓冲**（`PacketRingBuffer`）：原先由 ExoPlayer 的 Loader 线程
  直接 `receive()`，而那条线程还要做 TS 解复用、写采样队列、分配内存。UDP 是推模式，
  内核缓冲（实测被夹到 512KB，8Mbps 下仅约 0.5 秒）一满就永久丢包，于是解码器一挣扎或 GC
  一停顿就出现突发丢包（实测一次卡顿期间 `rtpLost` 由 0 涨到 71、`disc=11`），
  坏数据又让解码更糟，形成恶性循环。现在收包线程只负责把 socket 抽干，
  默认 2048 个数据报（约 4MB / 8Mbps 下 2.7 秒）的缓冲吸收消费侧抖动；
  缓冲写满时丢最旧的并计入 `ringOverflow` 统计。新增单元测试 `PacketRingBufferTest`
- 修复频道列表惯性滚动时按分组切换导致的崩溃
  （`IllegalStateException: Cannot call removeView(At) within removeView(At)`）。
  频道列表回收掉带焦点的行时，`ViewGroup.removeViewInternal` 会触发 `rootViewRequestFocus()`，
  焦点从根节点重新分发并落到分组列表项上，同步回调 `onGroupFocused` →
  `setAdapter()`，对正在回收中的同一个 RecyclerView 造成重入。
  新增 `RecyclerViewUpdateGate`，在任一列表处于布局/滚动计算中时把更新推迟到下一帧；
  `updateSelectedGroup` 里的 `notifyDataSetChanged` 也受同一道闸门保护
- 新增单元测试 `RecyclerViewUpdateGateTest`

## [1.2.1] - 2026-04-13

### Changed

- Media3 从 `1.10.0-rc01` 升级到正式版 `1.10.0`，与官方稳定发布对齐
- 对当前项目执行 `testDebugUnitTest` 与 `assembleDebug` 验证，确认现有播放器链路可正常编译并通过单元测试

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
[1.2.1]: https://github.com/whyun-android/witv/compare/v1.2.0...v1.2.1
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
