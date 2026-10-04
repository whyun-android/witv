# UDP / RTP 组播播放

WiTV 支持播放 `udp://` 和 `rtp://` 组播频道，并可通过 udpxy 代理把组播改写为 HTTP 单播。

## 两种使用方式

### 方式一：udpxy 代理（推荐，覆盖面最广）

绝大多数家庭网络**收不到**运营商组播：IPTV 组播通常跑在独立 VLAN 里，普通路由器 LAN 口拿不到；
即使拿得到，WiFi 下的组播丢包也很严重。

在路由器 / 软路由上跑 udpxy 后，在 WiTV 里填入它的地址，播放时会自动改写：

```
rtp://239.1.1.1:1234  →  http://192.168.1.1:4022/rtp/239.1.1.1:1234
udp://239.1.1.1:1234  →  http://192.168.1.1:4022/udp/239.1.1.1:1234
```

配置入口（两处等价，改哪边都生效）：

- **TV 端**：设置 → 组播 / UDP → 填写 udpxy 地址 → 保存
- **Web 管理页**：浏览器打开 `http://<设备IP>:9979` → 设置 → 组播转单播代理 (udpxy)

遥控器输入 URL 很痛苦，建议用 Web 管理页填。

地址会被自动规范化：补 `http://`、去掉末尾的 `/`，以及用户习惯性粘贴的 `/udp`、`/rtp` 后缀。
所以 `192.168.1.1:4022`、`http://192.168.1.1:4022/`、`http://192.168.1.1:4022/rtp/` 都会被
归一成 `http://192.168.1.1:4022`。

### 方式二：直接收组播

把代理地址留空即可。前提：

- 设备所在网络能收到目标组播（通常需要接在 IPTV VLAN / 运营商机顶盒口）；
- **强烈建议走网线**。WiFi 组播靠速率最低的基础速率广播，丢包率远高于有线；
- 应用会自动申请 `MulticastLock`（`CHANGE_WIFI_MULTICAST_STATE` 权限）。没有这个锁，
  WiFi 驱动会在网卡层直接丢弃目的 MAC 非本机的组播帧，一个包都收不到。

## 地址写法

m3u 里组播地址写法很杂，以下写法都会被规范化后正常播放：

| m3u 中的写法 | 规范化结果 |
|---|---|
| `udp://239.1.1.1:1234` | `udp://239.1.1.1:1234` |
| `udp://@239.1.1.1:1234`（VLC 风格） | `udp://239.1.1.1:1234` |
| `udp://@@239.1.1.1:1234` | `udp://239.1.1.1:1234` |
| `rtp://@192.168.1.1@239.1.1.1:1234`（SSM 源地址） | `rtp://239.1.1.1:1234` |
| `RTP://239.1.1.1:1234` | `rtp://239.1.1.1:1234` |

SSM 的源地址段会被丢弃——`MulticastSocket` 的简单 join 接口不支持指定源。

## 实现要点

| 文件 | 职责 |
|---|---|
| `MulticastUrlUtil` | 地址规范化、udpxy 改写（纯函数，可测） |
| `MulticastDataSource` | UDP/RTP 收流数据源，独立收包线程、可配 `SO_RCVBUF`、按活动网卡 join、自动持锁 |
| `PacketRingBuffer` | 收包线程与消费线程之间的有界环形缓冲，满了丢最旧的 |
| `MulticastStallPolicy` | 断流时重新入组的次数预算 |
| `RtpPacketUtil` | RTP 固定头解析（CSRC / 扩展头 / 尾部 padding） |
| `RtpReorderBuffer` | RTP 乱序重排窗口，收包路径零分配 |
| `MulticastLockHolder` | WiFi 组播锁，引用计数 |
| `MulticastAwareDataSourceFactory` | 按 scheme 分流，`udp`/`rtp` 走组播源，其余走原 HTTP/HLS 链路 |
| `SwitchableLoadControl` | 组播档与 HLS 档两套缓冲参数，换源时切换，不重建播放器 |

### 为什么不直接用 Media3 自带的 `UdpDataSource`

Media3 的 `DefaultDataSource` 确实已经把 `udp://` 路由到 `UdpDataSource`，但它是 `final` 的，
且缺了直播必需的几项能力：

- **没有暴露 `SO_RCVBUF`**。默认内核接收缓冲在高码率（4K 20Mbps+）组播下必然丢包花屏；
  `MulticastDataSource` 默认申请 4MB。
- **不选网卡**。盒子常同时有 `eth0` 和 `wlan0`，join 错网卡就一个包收不到；
  `MulticastDataSource` 按 `ConnectivityManager` 的活动网络选网卡 join，失败再回退系统路由。
- **不支持 `rtp://`**。Media3 的 RTP 实现耦合在 RTSP 会话里，没有独立的 `rtp://` scheme 支持，
  而国内运营商 m3u 里 `rtp://` 比 `udp://` 更常见。
- 默认 8 秒收包超时对直播偏长，断流后换源太慢；这里用 3 秒。

### RTP 处理

`rtp://` 的载荷要剥掉 12 字节固定头（含 CSRC 列表、扩展头、尾部 padding）才是 MPEG-TS。
重排窗口默认 32 包：

- 期望序号已到达 → 立即吐出，零额外延迟；
- 有空洞但积压不足半窗 → 先等，给乱序包到达时间；
- 积压达到半窗仍没补上 → 判定丢包，跳到最近的已缓冲包；
- 序号跳变超过一个窗口（换台、信号中断）→ 丢弃积压重新起点。

部分源标了 `rtp://` 但实际推的是裸 TS。检测到首字节是 TS 同步字节 `0x47` 时会自动回退为透传
（裸 TS 的 `0x47` 对应 RTP 版本位 `01`，与合法的版本 2 不冲突，不会误判）。

关流时若有丢包会打一条统计日志：

```
MulticastDs: RTP stats - lost=12 late=3 discontinuities=0 malformed=0
```

### 缓冲策略

组播是实时推流，服务端没有可回拉的缓冲，缓冲再厚也只是单纯增加开播等待：

| | minBuffer | maxBuffer | 起播门槛 | 卡顿后恢复门槛 |
|---|---|---|---|---|
| HTTP / HLS | 35s | 90s | 6.0s | 15s |
| UDP / RTP（含经 udpxy） | 8s | 30s | 1.5s | 3s |

`SwitchableLoadControl` 让两档共用同一个内存池，因此换台时只切阈值、不重建 ExoPlayer。

## 排查

播放不出来时按这个顺序看 logcat：

```bash
adb logcat -s MulticastDs:V MulticastLock:V PlayerManager:V
```

| 日志 | 含义 |
|---|---|
| `Multicast lock acquired` | 组播锁正常 |
| `Failed to acquire multicast lock` | 缺权限或厂商 ROM 异常；有线网络可忽略 |
| `Opened udp://... (rtp=false, rcvbuf=..., iface=eth0)` | socket 已就绪，看 `iface` 是否是预期网卡 |
| `SO_RCVBUF ... rejected` | 内核拒绝了 4MB 接收缓冲，高码率下可能花屏 |
| `joinGroup on ... failed, falling back` | 指定网卡 join 失败，已回退系统路由 |
| `Active interface ... does not support multicast` | 当前网卡不支持组播 |
| `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` | 3 秒没收到任何包——通常就是收不到组播，改用 udpxy |
| `Stream declared rtp:// but carries raw TS` | 源标错了协议，已自动透传，不影响播放 |
| `Receiving ...: N pkt/s, M kbps` | 每 10 秒一条的收流心跳。断流时它会先停，再出现下面的告警 |
| `No packet for Nms on ... - stream stalled` | socket 还在、组还在，但上游不再转发。常见于 IGMP 成员被剪、源停推、网络切换 |
| `SO_RCVBUF clamped by kernel: requested X, got Y` | 内核把接收缓冲压到了 `net.core.rmem_max`，由环形缓冲兜底 |
| `ring=N/M overflow=K` | 环形缓冲占用与挤出计数。`overflow` 持续增长说明消费侧长期跟不上收包速度 |
| `Closing after Nms: X packets / Y bytes, last packet Zms ago` | 关流总结。`last packet` 很大说明是先断流后关闭 |

| `Rejoining multicast group X (attempt N/M)` | 断流后自动重新入组，刷新 IGMP 成员关系 |
| `Stream recovered after rejoin` | 重入组奏效，流已恢复 |

### 区分「收流有问题」和「解码跟不上」

这两件事表现都是卡顿，但处理方向完全相反。看两组数字：

| 收流统计 | EventLogger | 结论 |
|---|---|---|
| `rtpLost`/`disc` 持续增长 | — | 网络侧丢包，查 IGMP/网络 |
| `rtpLost=0` 但 `overflow` 增长 | — | 消费侧长期跟不上收包速度 |
| `rtpLost=0 overflow=0` | `droppedFrames` 反复出现 | **设备解码能力不足**，播放器无能为力 |

`droppedFrames` 每累计 50 帧报一次，用相邻两次的时间差即可估算掉帧率：
间隔 3 秒报一次 50 帧 ≈ 每秒掉 17 帧，对 25fps 的流就是掉了约 70%。
这种情况换源、调缓冲都没用，只能换解码方式或换设备。

**「播一会儿就卡住」怎么判**：卡住时看有没有 `No packet for ...ms`。

- **有** → 确实没有包进来，是网络侧问题。应用会自动重新入组，正常情况下几秒内恢复
  （看到 `Stream recovered after rejoin`）。
- **没有** → 包还在进但播放器没消化，属于播放器侧问题，由重缓冲看门狗接管（见下）。

### 重缓冲看门狗（数据在进、播放器不动）

**症状**：`Receiving` 心跳一直正常（码率、包速率稳定，`rtpLost` 不再增长），但播放画面卡住、
日志停在 `Buffering...`。常见诱因是突发丢包导致 TS 时间戳不连续，播放器算不出缓冲时长。

**处理**：起播成功后再次进入缓冲会武装看门狗，超时（沿用「超时换源」设置，默认 15 秒）后：

1. 先**原地重开当前线路** —— 新的解复用器、新的采样队列，时间戳重新对齐；
2. 连续 2 次仍救不回来才换源 —— 单线路频道换源就等于放弃播放，不应是第一选择；
3. 重开后若迟迟起播不了，由起播超时照常接管换源。

看门狗只在**成功起播过之后**才武装：起播前的缓冲归起播超时管，两者互斥，
否则同一时刻会各做一次动作。

对应日志：

```
PlayerManager: Stalled in BUFFERING for 15000ms with source 1/1 - restarting playback (1/2)
```

### 自动重新入组（IGMP 成员超时）

**症状**：组播播放 2~5 分钟后画面卡住，退出频道重进立刻恢复。

**原因**：组播转发依赖 IGMP 成员关系。上游路由器每隔约 125 秒发一次 General Query，
主机必须回 Membership Report，否则交换机的 IGMP snooping 会把这个组剪掉、停止转发。
Query/Report 任一方向丢失（Wi-Fi 省电、AP 不转发组播管理帧、虚拟网络）就会出现这个现象。
「重进频道就好了」之所以有效，正是因为它重新 `joinGroup` 了一次，等于补发了一个 Membership Report。

**处理**：收包超时时 `MulticastDataSource` 会原地离组再入组（socket 和绑定端口都不变，
只丢失微秒级窗口内的包），由 `MulticastStallPolicy` 控制次数：

- 健康的流永远不会触发——只有连续 3 秒一个包都没有才会；
- 默认最多重入组 2 次，即约 9 秒内仍无数据才判定断流、报错换源，
  不会让频道无限挂住；
- 收到任意数据即重置预算，下次断流重新获得完整次数。

仍然不稳的话改走 udpxy 代理：它是普通 HTTP 单播，完全不依赖 IGMP。

### 模拟器上的组播

官方文档写明模拟器**不支持 IGMP**，但这指的是传统的 QEMU user-mode（slirp）网络。
较新的模拟器（36.5+）改用虚拟 Wi-Fi 网络栈后，实测**组播可以收到**
（日志里会看到 `iface=wlan0`）。所以别直接假定模拟器测不了，先看日志里有没有包进来。

需要注意的是组播在模拟器上**可能只能维持几分钟**：组播转发依赖 IGMP 成员关系，上游路由器
每隔约 125 秒发一次 General Query，主机必须回 Membership Report，否则交换机的 IGMP snooping
会把这个组剪掉。Query/Report 要穿过虚拟网络，这一环不可靠时就表现为「能播 2~5 分钟后卡住」。

判别方法见下面的排查表：看 `No packet for ...ms` 是否出现，以及退出频道重进能否恢复
（能恢复 = 重新 join 刷新了成员关系 = IGMP 成员超时）。

如果直收不稳，模拟器上更可靠的是 **udpxy 代理路径**（改写后是普通 HTTP，不依赖 IGMP）：
在宿主机跑 udpxy，模拟器里把代理地址填成 `http://10.0.2.2:4022`
（`10.0.2.2` 是模拟器访问宿主机的固定地址）。

无论如何，最终结论要以真实设备为准。

`PlayerManager` 会同时打印原始地址与改写后的地址：

```
Trying source 1/2: rtp://239.1.1.1:1234 (via udpxy: http://192.168.1.1:4022/rtp/239.1.1.1:1234)
```

## 解码方式（FFmpeg 软解）

组播 TS 常见 MPEG-2 视频与 MP2/AC3 音频，不少电视盒子的 MediaCodec 要么不支持，要么声称支持
但解出来黑屏/无声。应用内置了 NextLib 的 FFmpeg 软解作为兜底。

设置 → 解码方式：

| 选项 | 音频 | 视频 | 适用 |
|---|---|---|---|
| 硬解优先（默认） | 硬解优先 | 硬解优先 | 绝大多数情况 |
| **音频软解 + 视频硬解** | **软解优先** | 硬解优先 | **杜比声道没声音、但视频正常**的设备 |
| 全部软解优先 | 软解优先 | 软解优先 | 硬解声称支持但实际黑屏的盒子；CPU 占用高 |
| 仅硬解 | 不加载 | 不加载 | 排查软解自身问题 |

音视频分开取值由 `WiTVRenderersFactory` 落实——`DefaultRenderersFactory` 本身只有一个全局的
`extensionRendererMode`，而音视频的最佳取舍常常相反。

### 杜比没声音怎么办

盒子声称支持 AC-3/E-AC-3 **直通**时，`MediaCodecAudioRenderer` 会以直通方式胜出、
压根不解码，把原始码流丢给 HDMI；下游电视/功放解不了就是完全没声音，
而且这条路径下 FFmpeg 永远没机会出手。

选 **「音频软解 + 视频硬解」**：`FfmpegAudioRenderer` 排到 `MediaCodecAudioRenderer` 之前，
AC-3 被解成 PCM，任何 HDMI 设备都能出声；视频仍走硬解，不会因软解 1080p 跟不上而卡顿。

不要为此选「全部软解优先」——那会把视频一起转软解，老盒子上必然掉帧。

另外无论选哪档都开启了 `setEnableDecoderFallback(true)`：某个 MediaCodec 解码器 `configure`
失败时，依次尝试**同一渲染器里的其它 MediaCodec 解码器**（例如 `c2.android.avc.decoder`
失败后试 `c2.qti.avc.decoder`）。

**注意它不会退到 FFmpeg 渲染器**——渲染器在 `supportsFormat` 阶段就已经选定，之后的 fallback
只在 MediaCodec 解码器之间进行。所以「硬解整体起不来」只能靠切到**软解优先**档解决。
典型日志长这样：

```
W MediaCodecRenderer: Failed to initialize decoder: c2.android.avc.decoder
E MediaCodecVideoRenderer: Video codec error
    DecoderInitializationException: Decoder init failed: c2.android.avc.decoder, Format(...)
D CCodec: allocate(c2.qti.avc.decoder)        ← fallback 在试下一个 MediaCodec 解码器
E SurfaceUtils: Failed to connect to surface ..., err -22
E MediaCodec: configure failed with err 0xffffffea, resetting...
```

`Failed to connect to surface ... err -22` / `connect: already connected` 是上一个解码器
configure 失败后没有干净地从 Surface 断开，后一个解码器再去连就被拒。属于解码器实现问题，
模拟器镜像尤其容易触发。遇到这种循环直接切「软解优先」绕开 MediaCodec。

切换解码方式会**立即重建播放器并续播当前频道**——`RenderersFactory` 只能在 `ExoPlayer` 构建时
指定，之后改不了。在非播放页（独立设置页）修改则下次起播生效。

### 相关日志

```
PlayerManager: Decoder mode: auto (extensionRendererMode=1), ffmpeg=6.1.1
NextRenderersFactory: Loaded FfmpegVideoRenderer.
NextRenderersFactory: Loaded FfmpegAudioRenderer.
```

`ffmpeg=unavailable` 或 `load-failed:` 表示原生库没加载起来（通常是 ABI 被裁掉），
此时设置页的「解码方式」分类顶部也会显示提示，三个选项实际都等同于仅硬解。

### 版本约束

`io.github.anilbeesetti:nextlib-media3ext` 的版本号是 `<media3 版本>-<nextlib 版本>`，
**必须与项目的 `media3_version` 严格一致**（当前 `1.10.0-0.12.1` 对应 Media3 1.10.0）。
不一致会在运行时因 Media3 内部 API 变动抛 `NoSuchMethodError`。升级 Media3 时必须同步升级它。

### 已启用的软解编码

来自 NextLib 的 FFmpeg 构建配置（README 上的清单是过时的，以构建脚本为准）：

- **视频**：h264、hevc、mpeg2video、mpegvideo、vp8、vp9
- **音频**：aac、ac3、eac3、mp3（含 MPEG-1/2 Layer I/II，即组播常见的 MP2）、dca(DTS)、
  truehd、mlp、flac、alac、vorbis、opus、amrnb、amrwb、pcm_mulaw、pcm_alaw

## 已知限制

- **不支持 SSM（指定源组播）**，m3u 里的源地址段会被忽略。
- 组播频道的多线路自动切换、超时换源与 HTTP 频道一致，但组播断流时 3 秒即判超时，
  比 HTTP 的默认行为更激进。
- 发布包只带 `armeabi-v7a` 和 `arm64-v8a`（见 `app/build.gradle` 的 release `abiFilters`）。
  debug 包保留全部 ABI，可在 x86_64 模拟器上调试。
