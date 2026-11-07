# F-Droid 上架

`com.whyun.witv.yml` 是提交到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 的构建配置草稿，
提交时放到 `metadata/com.whyun.witv.yml`。

## 提交前要替换的占位符

| 占位符 | 填什么 |
|---|---|
| `NEXT_VERSION_NAME` / `NEXT_VERSION_CODE` | 第一个包含可复现构建改动的发布版本（1.5.1 及更早的 tag 不行） |
| `SIGNING_CERT_SHA256` | 发布签名证书的 SHA-256，小写、不带冒号 |

取证书指纹（对任意一个 CI 签名过的 APK）：

```sh
apksigner verify --print-certs witv_vX.Y.Z.apk | grep "SHA-256"
```

## 为什么这样配

- `Binaries` + `AllowedAPKSigningKeys`：F-Droid 用自己的服务器编出未签名 APK，
  和 GitHub Release 里的 APK 逐字节比对，一致就直接发布我们签名的版本。
  用户从 F-Droid 和 GitHub 装的版本可以互相覆盖升级。
- 能比对一致依赖三件事，改动时要保持：
  - `app/build.gradle` 里的构建时间取 git 提交时间，不是编译时的系统时间
  - 关闭 `dependenciesInfo`（AGP 默认塞进 APK 的 Google 加密依赖清单）
  - CI 用 `apksigner --alignment-preserved` 签名，签名前不重新对齐
- 发布签名方式改了以后，可以在 Actions 里手动运行 Release APK，
  输入已有的 tag 重新编译、签名并替换 Release 里的 APK。
