# 播放与界面相关改动说明

本文档汇总近期在 **播放内核（PlayerManager / ExoPlayer）**、**播放页交互** 与 **EPG 浮层信息展示** 方面的实现要点，便于维护与调参。

---

## 1. 直播 HLS：`BehindLiveWindowException` 恢复

**背景**：直播 m3u8 使用滑动窗口，长时间卡顿后播放位置可能落在已删除分片之外，Exo 抛出 `BehindLiveWindowException`（`ERROR_CODE_BEHIND_LIVE_WINDOW`）。

**实现**（`PlayerManager`）：

- 在 `onPlayerError` 中通过 `isBehindLiveWindowError(PlaybackException)` 识别（错误码或 `cause` 链上的 `BehindLiveWindowException`）。
- 命中后 **不换源**：`seekToDefaultPosition()` → `prepare()` → `setPlayWhenReady(true)`，并重新 `startTimeout()`。
- 其它错误仍走原有 `switchToNextSource`。

**测试**：`PlayerManagerTest` 中对 `isBehindLiveWindowError` 有单元测试。

---

## 2. 稳定性取向：Live 配置 + 缓冲 + HTTP

目标：**更稳、少出错**（可接受略增延迟）。

### 2.1 `MediaItem.LiveConfiguration`（仅 m3u8 / HLS 分支）

在 `buildMediaItem` 中，对识别为 HLS 的地址设置：

| 参数 | 值 | 说明 |
|------|-----|------|
| `targetOffsetMs` | `10_000` | 相对 live edge 目标距离约 10s，少贴边 |
| `minOffsetMs` | `5_000` | 不允许离边过近 |
| `maxOffsetMs` | `C.TIME_UNSET` | 不限制最大落后距离 |

常量：`LIVE_TARGET_OFFSET_MS`、`LIVE_MIN_OFFSET_MS`（`PlayerManager` 顶部可调）。

### 2.2 `DefaultLoadControl`

| 参数 | 调整后（约） |
|------|----------------|
| minBufferMs | 20_000 |
| maxBufferMs | 55_000 |
| bufferForPlaybackMs | 3_000 |
| bufferForPlaybackAfterRebufferMs | 9_000 |

### 2.3 `DefaultHttpDataSource` + `DefaultMediaSourceFactory`

- `connectTimeoutMs`：`12_000`
- `readTimeoutMs`：`45_000`
- `User-Agent`：固定为 `stagefright/1.2 (Linux;Android 7.1.2)`（常量 `HTTP_USER_AGENT`）
- 经 `DefaultDataSource.Factory(context, httpDataSourceFactory)` 注入 `ExoPlayer.Builder.setMediaSourceFactory(...)`

常量：`HTTP_CONNECT_TIMEOUT_MS`、`HTTP_READ_TIMEOUT_MS`。

---

## 3. 播放页：设置浮层打开时的方向键

**问题**：设置抽屉可见时，上下键仍触发换台。

**实现**：

- `PlayerActivity.onKeyDown`：在 `isSettingsPanelVisible()` 时，对 **上下左右、确定、Enter** 交给 `SettingsCollapsibleFragment.dispatchDrawerKey(...)`，并 `return true`，不再走换台/频道列表逻辑。
- `SettingsCollapsibleFragment.dispatchDrawerKey`：焦点不在 `settings_panel_content` 内时调用 `refreshAndFocus()`；方向键用 `FocusFinder.findNextFocus` 在面板内移动；确定键 `dispatchKeyEvent` 到当前焦点。
- `activity_player.xml`：`settings_scrim` 改为 `focusable="false`、`clickable="true`，避免焦点落在遮罩上。

---

## 4. EPG 浮层：信号信息精简

**位置**：播放页底部 EPG  overlay 右侧「信号信息」（`MediaInfoFormatter` + `PlayerActivity.updateMediaInfoText`）。

**移除展示项**：

- 视频码率、帧率  
- 音频码率  

**保留**：分辨率、视频编码、音频编码、采样率、声道。

**编码通俗名**：`MediaInfoFormatter` 将 `avc1.640029`、`mp4a.40.2` 等原始 `codecs` 解析为中文说明（如「H.264 · 高级档次 · 4.1 等级」「AAC（常用，LC）」）；无 `codecs` 时仍按 MIME 回退为简短中文/通用名。

已删除未再使用的字符串资源键（如 `media_info_video_bitrate` 等）。

**说明**：右上角「加载速度」浮层仍使用带宽估计，与本次精简无关。

---

## 5. 维护清单（相关文件）

| 区域 | 主要文件 |
|------|-----------|
| 播放 / Exo | `app/.../player/PlayerManager.java` |
| 播放页按键 / 设置 | `app/.../ui/PlayerActivity.java`、`SettingsCollapsibleFragment.java`、`res/layout/activity_player.xml` |
| 信号信息文案 | `app/.../ui/MediaInfoFormatter.java`、`PlayerActivity.java`、`res/values/strings.xml` |
| 单测 | `app/.../test/.../PlayerManagerTest.java` |

---

## 6. 调参建议

- **仍频繁 `BehindLiveWindow`**：可适当增大 `LIVE_TARGET_OFFSET_MS` / `LIVE_MIN_OFFSET_MS`，或略增 `bufferForPlaybackAfterRebufferMs`；同时确认网络与源质量。
- **慢源被误判为失败**：可适当增大 `HTTP_READ_TIMEOUT_MS`（与 `SOURCE_TIMEOUT_MS` 配合考虑）。
- **点播 m3u8 若受 Live 配置影响**：可按 URL 规则拆分分支，仅对直播类地址设置 `LiveConfiguration`（当前为凡 HLS 即设置，一般点播可忽略）。

---

*文档随实现更新；若与仓库实际代码不一致，以代码为准。*
