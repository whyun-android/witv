# WiTV

Android TV M3U 直播播放器，使用原生 ExoPlayer 播放，支持多源自动切换、EPG 节目预告、局域网 Web 管理。

## 功能

- **M3U 播放源管理** — 支持添加多个 M3U/M3U8 播放源地址，在历史源之间快速切换
- **多源自动切换** — 同一频道聚合多个播放地址，播放失败时自动尝试下一个源
- **EPG 节目预告** — 自动读取 M3U 中的 `x-tvg-url` 属性，也支持手动配置 XMLTV 地址
- **局域网 Web 管理** — 内置 HTTP 服务器（端口 9978），通过手机/电脑浏览器管理播放源和设置
- **遥控器适配** — 完整的 D-Pad 导航支持，数字键直接跳转频道

## 遥控器操作

| 按键 | 功能 |
|------|------|
| 确认键 / OK | 显示频道列表（不自动展开 EPG 信息栏）；列表在焦点停留无操作约 10 秒后自动关闭 |
| 上 / 下键 | 切换上一个 / 下一个频道（可在设置中反转方向） |
| 信息键 / 空格 | 显示 / 隐藏 EPG 与信号信息面板 |
| 右键 / 返回键 | 关闭频道列表和信息面板 |
| 数字键 0-9 | 输入频道号直接跳转 |
| 菜单键 | 打开设置面板；首页左侧选中「设置」分类即打开设置层（右侧不再显示设置按钮） |

## 技术栈

| 组件 | 技术 |
|------|------|
| 播放器 | Media3 ExoPlayer 1.10.0-rc01 |
| TV 界面 | Leanback 1.2.0 |
| 数据库 | Room 2.6.1 |
| HTTP 服务器 | NanoHTTPD 2.3.1 |
| 网络请求 | OkHttp 4.12.0 |
| JSON | Gson 2.10.1 |
| 图片加载 | Glide 4.16.0 |
| 语言 | Java |
| 最低支持 | Android 5.0 (API 21) |

## 构建

### 环境要求

- JDK 17
- Android SDK（API 36）

### 本地构建

```bash
# Debug
./gradlew assembleDebug --no-daemon

# Release
./gradlew assembleRelease --no-daemon
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### CI 构建

项目配置了两个 GitHub Actions：

- **Build** — 推送到 main/master 或提交 PR 时自动构建 Debug APK
- **Release** — 推送 `v*` 格式的 tag 时自动构建、签名并发布 Release APK

发布需要在仓库 Settings → Secrets 中配置：

| Secret | 说明 |
|--------|------|
| `SIGNING_KEY` | Keystore 文件的 Base64 编码 |
| `ALIAS` | 密钥别名 |
| `KEY_STORE_PASSWORD` | Keystore 密码 |
| `KEY_PASSWORD` | 密钥密码 |

## 使用方式

1. 安装 APK 到 Android TV 设备
2. 启动应用，主界面会显示局域网管理地址（如 `http://192.168.1.100:9978`）
3. 在手机或电脑浏览器中打开该地址
4. 添加 M3U 播放源地址
5. 返回 TV 端即可看到频道列表，选择频道开始播放

## Web 管理 API

内置 HTTP 服务器提供以下 REST API：

```
GET    /api/sources              获取所有播放源
POST   /api/sources              添加播放源 { name, url }
DELETE /api/sources/:id          删除播放源
POST   /api/sources/:id/activate 切换激活播放源
POST   /api/sources/:id/reload   重新加载播放源
GET    /api/sources/:id/channels 获取频道列表
GET    /api/settings             获取设置
PUT    /api/settings             更新设置 { epgUrl }
POST   /api/epg/reload           刷新 EPG 数据
```

## 项目结构

```
app/src/main/java/com/whyun/witv/
├── WiTVApp.java                 # Application 入口
├── data/
│   ├── db/                      # Room 数据库、Entity、DAO
│   ├── parser/                  # M3U 和 XMLTV 解析器
│   └── repository/              # 数据仓库层
├── player/
│   └── PlayerManager.java       # ExoPlayer 封装 + 多源故障转移
├── server/
│   └── WebServer.java           # NanoHTTPD HTTP 服务
└── ui/                          # TV 界面 Activity 和适配器

app/src/main/assets/web/         # Web 管理前端页面
```

## License

MIT
