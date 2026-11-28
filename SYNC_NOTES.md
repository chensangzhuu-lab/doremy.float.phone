# 上游同步记录（doremy1203 → 本仓库）

> 本文记录 2026-11-27/28 一次完整同步的过程、冲突处置与踩坑，供后续同步参考。

## 一、仓库关系

- 本仓库 `chensangzhuu-lab/doremy.float.phone` 是从 `doremy1203/doremy.float.phone` **Fork** 出来的。
- GitHub 网页上的 **Sync fork** 按钮和**跨仓库对比**都不可用（当初大概是 Import/Copy 方式建立，GitHub 不认这层 fork 血统），报错表现为：
  - 首页无 `forked from` 灰字、无 `X commits behind` 横幅；
  - compare 页面 head repository 下拉选不到 `doremy1203`，提示 `Failed to load repositories`；
  - 工坊 `sync_fork_with_upstream` 返回 `422: 连接的仓库不是官方项目的 fork`。
- **但两个仓库有真正的共同祖先**（`git fetch` 时输出 `completed with N local objects` 可佐证），因此在本地用 git 做三方合并是完全可行的，不会出现「历史无关、全文件冲突」的最坏情况。

## 二、本次同步规模

| 项 | 值 |
|---|---|
| 合并提交 | `ca89ca1`（merge commit） |
| 上游提交数 | 49 commits |
| 文件变更 | **490 files changed, 89894 insertions(+), 6976 deletions(-)** |
| 冲突 | 4 个文件、共 7 处冲突块，全部手工处置 |
| 最终 CI | 13 项检查全绿（含 `Build Android Shell APK` 编译通过） |

## 三、标准流程

```bash
# 0. 备份（重要！）
git tag pre-sync-$(date +%Y%m%d)          # 本地回退点
# 远程另建备份分支亦可：backup/pre-sync-<日期>

# 1. 拉取上游
git remote add src https://github.com/doremy1203/doremy.float.phone   # 只需首次
git fetch src

# 2. 先看差异，心里有数
git merge-base main src/main                  # 确认有共同祖先（有 sha 输出）
git log --oneline main..src/main              # 上游多了哪些提交
git --no-pager diff --stat main src/main      # 改了哪些文件（+新增/删除、+-混合=冲突候选）

# 3. 三方合并（先不提交）
git checkout -b try-sync
git merge --no-commit --no-ff src/main

# 4. 有冲突则逐个处理（见下节），处理后
git add <每个文件>
git diff --name-only --diff-filter=U          # 确认为空 = 无未解决冲突

# 5. 提交并推送临时分支
git commit -m "merge: 同步 doremy1203 main（冲突处置：…）"
git push origin try-sync

# 6. 开 PR 让 CI 跑，绿了再合并进 main
```

## 四、本次冲突清单与处置原则

冲突集中在**推送链路 + 安卓壳配置**（正好是本仓库此前自己改过的区域）。

| 文件 | 冲突原因 | 处置原则 |
|---|---|---|
| `android-shell/app/build.gradle` | 两边都用到版本号 `1.0.13`：本仓库是「推送诊断」，上游是 `1.0.13~1.0.29` | **版本号必须严格递增**（否则 Android 拒绝覆盖安装）。保留上游完整版本序列，本仓库功能重编为 `versionCode 29 / versionName "1.0.30"` |
| `lib/server/push-service.ts` | 本仓库把 `broadcastShellNotify` 返回值改为 `ShellBroadcastResult`（带失败详情）；上游给消息体加了 `kind/sessionId/sceneId/characterName/avatar` 五字段 | **两边都留**：保留返回类型改造 + 接收上游五字段，并在 `PushMessage` 类型、`payload.data`、广播 payload 中真正传递出去 |
| `android-shell/app/src/main/AndroidManifest.xml` | 两边各加了新组件（本仓库 `PushDebugServer`；上游 `PushWatchdogReceiver`/`AmbientLightMonitorService`/`NativeRealityBridgeReceiver` 等） | **全部保留**；本仓库原有的 `PushService`(dataSync)、浮窗服务、无障碍、深链、Health Connect 入口一个都不能删 |
| `android-shell/app/src/main/java/app/floatphone/shell/PushService.kt` | 上游重写了整个类，本仓库有诊断轨迹与 `requestReconnect` 等改动（7 处冲突） | 见下方逐条表 |

### PushService.kt 七处冲突处置

| # | 位置 | 处置 |
|---|---|---|
| 1 | `requestReconnect()` / `scheduleWatchdog()` | 两边都保留 |
| 2 | `onCreate` 启动流程 | 看门狗 + `activeLoop` 赋值 + 诊断端口三者都留 |
| 3 | `fetchConfig()` | 保留本仓库诊断日志与 `lastConfigNote`；返回值改用上游 `return@runCatching cachedConfig()` 兜底（网络失败用缓存，真正未登录/未配置才返回 null） |
| 4 | 订阅注册 | 保留详细 `lastShellSubNote`，同时补上游 `shellSubRegistered` 标志 |
| 5 | `onMessage()` | 取本仓库版本（含 `phx_join` 回执判断、诊断轨迹、`broadcastCount`），上游简化分支删掉 |
| 6 | 通知发送 | 用上游 6 参数 `showMessageNotification`，保留本仓库 `notificationPosted` 日志分支 |
| 7 | `sleepSec()` | 取上游 `try/catch` 版本（配合同步保留的 `requestReconnect()` 的 `interrupt()`，否则打断被 `runCatching` 吞掉、重连失效） |

## 五、踩到的三个坑（CI 逐个报出）

1. **缺依赖 `androidx.webkit`**
   - 报错：`Unresolved reference: webkit / WebViewFeature / WebViewCompat`（`MainActivity.kt`）
   - 原因：手工整份覆盖 `build.gradle` 时漏掉了上游新增的 `implementation 'androidx.webkit:webkit:1.12.1'`
   - 修法：在 `dependencies {}` 补回该依赖

2. **`PushService.kt` 注释未闭合**
   - 报错：`Missing '}'` + `Unresolved reference: reconnectNonce` + `Unclosed comment`
   - 原因：合并时 `requestReconnect` 上方 KDoc 前多了一个孤立的 `/`，导致 `/* … */` 配错对，注释一路吃到文件末尾，把中间的 `}` 和成员声明都吞进注释 → 一串连锁假错
   - 修法：删掉多余的孤立 `/`，规整注释块

3. **变量未声明 `shellSubRegisteredUserId`**
   - 报错：`Unresolved reference: shellSubRegisteredUserId`
   - 原因：注册成功分支里写了赋值，但合并后该成员变量没声明（上游只有布尔 `shellSubRegistered`）
   - 修法：补 `private var shellSubRegisteredUserId: String? = null`

> 经验：**Kotlin 编译错误必须靠 CI 的 `android-shell` workflow 验证**，人眼审不出类型层面的问题。每次推送后等它跑完再判断。

## 六、校验命令速查

```bash
# 冲突标记是否残留（空输出=干净）
grep -rn "<<<<<<<\|=======\|>>>>>>>" <文件>

# Kotlin 注释配平（数字应相等）
grep -c '/\*' <file.kt>; grep -c '\*/' <file.kt>

# 花括号余额应为 0
python -c "s=open('<file.kt>',encoding='utf-8').read(); print(s.count('{')-s.count('}'))"

# 版本号只有一组
grep -n "versionCode\|versionName" android-shell/app/build.gradle

# push-dist 一致性
npm run check:push

# 未解决冲突文件
git diff --name-only --diff-filter=U
```

## 七、下次同步建议

1. **别隔太久**：这次差 490 文件 / 9 万行，手工合并成本极高。一两周跟一次，冲突会少很多。
2. **每次先打标签**：`git tag pre-sync-$(date +%Y%m%d)`，翻车好回退。
3. **保留远程备份分支** `backup/pre-sync-to-doremy1203`（指向 `31c93808f0`，合并前快照），确认新版稳定后再删。
4. **优先小步、按目录挑**：若上游 9 万行里实际只需要某几个模块（如 `android-shell/` 原生能力或某个 `tools/` 工具），可按目录挑，不必整包合。
5. 上游是「从源仓库 main 完整同步」的**整包覆盖式镜像**，每次差异都很大，注意其中可能夹带与本仓库无关的历史改动。
