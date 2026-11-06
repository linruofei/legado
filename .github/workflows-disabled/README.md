# 已停用的 GitHub Actions 工作流

2026-11-07 停用。GitHub 只识别 `.github/workflows/` 下的 `*.yml` / `*.yaml`，
所以把它们整体移到本目录即等于禁用；文件内容一字未改，随时可原样搬回。

| 文件 | 原用途 | 停用原因 |
|---|---|---|
| `test.yml` | Test Build: 每次 push 构建 release（原包名 + 共存）+ 桌面三端，成功后发 beta Release | 需要 4 个签名 secret（`RELEASE_KEY_STORE` 等），fork 上未配置时必然卡在 Gradle 签名阶段；且会向 LanZou/Telegram 推送 |
| `release.yml` | 每月 1/15 号定时正式发布 | 同上，且依赖 `sign` secret 才会跑 |
| `_publish.yml` | 上面两个工作流共用的发布逻辑（reusable workflow） | 仅被上面两者调用 |
| `_desktop.yml` | 桌面 MSI/zip/DEB/RPM/DMG（reusable workflow） | 仅被 `test.yml` 调用 |
| `ios.yml` / `ios-remote.yml` | iOS 真机/模拟器打包 | 需要 Apple 证书，当前不需要 |
| `web.yml` | 构建 Web 产物 | 当前不需要 |
| `sync-quickjs-ng.yml` | 同步 quickjs-ng 构建产物 | 维护性任务，当前不需要 |
| `stale.yml` | 自动关闭久未活动的 issue | 当前不需要 |
| `delete_actions.yml` | 定期清理旧 Actions 记录 | 当前不需要，且会删掉想留的证据 |

当前启用的只有一个：`.github/workflows/debug-apk.yml`（debug 变体，零 secret，上传 artifact）。

## 恢复某个工作流

```bash
git mv .github/workflows-disabled/test.yml .github/workflows/test.yml
```

`_desktop.yml` / `_publish.yml` 这类 reusable workflow 必须和调用方一起恢复，
单独搬回不会自动运行。

## 恢复前要准备的 secret

`Settings → Secrets and variables → Actions`：

- `RELEASE_KEY_STORE`：`base64 -w0 key.jks` 的完整输出
- `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` / `RELEASE_STORE_PASSWORD`：创建 keystore 时的对应值
- 可选：`LANZOU_ID` / `LANZOU_PSD` / `LANZOU_FOLDER_ID`、`BOT_TOKEN` / `CHANNEL_ID`

注意：secret 保存后无法读回，只能覆盖或删除；用新 keystore 签出的包无法覆盖安装旧包。
