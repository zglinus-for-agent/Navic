# 失败实验：让鸿蒙媒体卡片点亮红心

> 这个分支**不是主线**，是 2026-11-17 的一次尝试，结论为失败。
> 主线（master）停在第一次成功构建的 `11000400`，不含本分支任何改动。

## 想做什么

让 Navic 出现在鸿蒙媒体卡片上时，卡片里的「收藏」键能点亮红心，
像网易云音乐、QQ 音乐那样。

## 试了什么

| 提交 | 做法 | 结果 |
|---|---|---|
| `f533d476` | 探针：把会话收到的自定义命令与 rating 全打日志 | 仅取证，无功能 |
| `91b86534` | 走 `MediaSession` 的 rating 通道：`onSetRating` → `starSong/unstarSong` | 卡片无变化 |
| `58c73b27` | 自绘 `CommandButton` 心形键，加进 custom layout | 通知里看不到 |
| `6c418be2` | 把心形键排到自定义按钮第一位（怀疑被 5 个上限截断） | 仍看不到 |
| `d1882045` | 换成 QQ 音乐的包名与版本做对照 | 卡片按钮无任何变化 |

## 为什么失败

**卡片上的按钮不由我们决定，除非我们整张通知自己画。**

证据：

- 网易云 9.6.22 的 `res/layout-v16/notification_expanded.xml` 里五个 `ImageView`，
  资源 id 反解为 `playNotificationStar` / `playNotificationLyric` /
  `playNotificationPre` / `playNotificationToggle` / `playNotificationNext`；
  其 dex 里 `RemoteViews` ×25、`setCustomContentView` ×4、`setCustomBigContentView` ×2。
  它是**自己画整张通知**。
- Navic 走 media3 的 `DefaultMediaNotificationProvider`（`MediaStyle` + `addAction`），
  按钮由系统模板渲染，数量与取舍都不归应用管。`RemoteViews` 用量：0。
- 华为公开文档里 Android 侧只规定"按 MediaSession 做播放控制"，没有收藏/歌词的接入规范；
  收藏/歌词的 `toggleFavorite`、`AVMetadata.lyric` 只在 AVSession（ArkTS 原生）里存在。
- GitHub 上没有任何 Android 应用接入鸿蒙播控卡片的开源实现。

## 下一步（未做）

替换 `DefaultMediaNotificationProvider`，实现自己的
`MediaNotification.Provider` + 自绘 `RemoteViews` 布局，收藏键由自己摆、自己接。
这是网易云/QQ 音乐的实际做法，也是唯一可控的路径。
