# APK 构建·打包·验证·推送发布全流程手册

> 本项目（moaradc/android-wifi-pojie，v3.x 分支）历轮本地构建经验的总沉淀。
> 覆盖第 3~14 轮实际发生的全部构建事故、解决措施与最终稳定打法，可作新一轮 SOP 复用。
> 单轮完整案例见 `docs/第十四轮-修改构建打包验证记录.md`。

---

## 一、流水线全景

```
环境预检 → 改码/改资源 → 源级校验(XML/残留grep) → 清陈旧产物
  → 分步构建(防OOM) → 签名流水线(zipalign+apksigner) → APK五层验证
  → git提交(修复污染) → push origin v3.x → CI双工作流自动触发
  → CI全绿后跑 release_sync 脚本(换回本地构建资产) → 公开下载md5端到端比对 → 闭环
```

核心原则：**交付给用户的 APK 必须是本地经过深度验证的那一份**。CI 产物只作管线健康度指示，最终 Release 资产要换回本地构建——CI 与本地环境构建字节天然不同，而本地版做过 arsc 文案比对与签名证书核对。

## 二、构建环境（4GB 内存沙箱）

### 2.1 环境构成

| 组件 | 位置 | 说明 |
|---|---|---|
| JDK 17 (Temurin) | `/home/z/jdk` | 系统 JDK21 是**纯 JRE 无 javac**（报 `Toolchain does not provide [JAVA_COMPILER]`），必须自装用户级 JDK17 |
| Android SDK | `/home/z/android-sdk` | cmdline-tools + platform-tools + `platforms;android-36` + `build-tools 36.0.0` |
| Gradle 分发 | `~/.gradle/wrapper/dists` | 项目 gradlew 自动拉取（9.3.0），被回收后首轮自动重下 |
| 签名密钥 | `/home/z/my-project/keystores/` | PKCS12/RSA4096，凭据记录于 `KEYSTORE_CREDENTIALS.txt` |
| 持久化脚本 | `/home/z/my-project/scripts/` | 构建/签名/发版/字符串注入脚本，历轮复用迭代 |

### 2.2 沙箱两大特性（决定所有打法的根因）

1. **内存只有 4GB**：多 JVM 叠加极易 OOM（详见第四节）；
2. **工具调用间隔会收割后台进程**：nohup 启动的进程秒死、setsid 存活约 5 分钟被收割（实测日志停在 daemon fork 处）——**任何依赖后台存活的方案都不可行**，构建必须前台分步执行。

### 2.3 环境整体回收事故（已发生 3 次）

第 8、13 轮回收过 `/home/z/jdk` 与整个 Android SDK（构建工具链消失，被迫全量重建）；本次（第十五轮前）又回收到更深一层——本地 git 仓库都回退到旧提交、工作区残留陈旧改动，但**远端提交链完好无损**。

应对方案（已验证两次的成熟路径）：

```bash
git fetch origin && git reset --hard origin/v3.x   # 远端是唯一真相源，本地陈旧改动全弃
# 环境重建（脚本化沉淀为 scripts/setup_android_sdk.sh）：
# 1. Temurin JDK17 下载解压至 /home/z/jdk
# 2. cmdline-tools → sdkmanager 装 platform-tools + platforms;android-36 + build-tools 36.0.0
# 3. gradle dists 首轮自动重下（compileReleaseKotlin 从 1m 涨到 5m 即此因，正常）
```

因此**每轮构建前必做环境预检**（3 条 ls，成本 1 秒）：

```bash
ls /home/z/jdk/bin/javac && ls /home/z/android-sdk/platforms/ && ls /home/z/android-sdk/build-tools/
git status --short | wc -l   # 工作区应为 0 改动
```

## 三、打包构建实操

### 3.1 构建命令（最终稳定形态）

```bash
cd /home/z/my-project/android-wifi-pojie
export JAVA_HOME=/home/z/jdk
export ANDROID_HOME=/home/z/android-sdk

./gradlew :app:compileReleaseKotlin --no-daemon \
  -Dkotlin.daemon.jvmargs=-Xmx896m --max-workers=2

./gradlew :app:assembleRelease  --no-daemon -Dkotlin.daemon.jvmargs=-Xmx896m --max-workers=2
./gradlew :app:assembleDebug    --no-daemon -Dkotlin.daemon.jvmargs=-Xmx896m --max-workers=2
```

`gradle.properties` 常态配置：`org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m`、`kotlin.daemon.jvmargs=-Xmx1024m`（命令行 `-Dkotlin.daemon.jvmargs=-Xmx896m` 为构建时更保守的覆盖值，实测最稳）。

产物体积参考：release（R8 混淆 + 资源裁剪）约 4.4~4.5MB，debug 约 27~29MB（debug 体积波动属多 dex 分布变化，非陈旧产物——第 12 轮 debug +1.27MB 经解包查 classes3.dex 含新字段证实行）。

### 3.2 为什么分三步而不是一条 assembleRelease

1. **工具单命令 10 分钟硬超时**：assembleRelease 约 8~9.5 分钟，逼近上限，与其他任务合并极易被杀；
2. **被杀后可续跑**：compileReleaseKotlin 先行（1~5 分钟）验证编译健康，失败不必等 R8 跑一半才发现；
3. **每步独立留产物留日志**：失败定位粒度细，重跑成本低（增量）。

历轮耗时数据（供预估）：

| 轮次 | compileReleaseKotlin | assembleRelease | assembleDebug |
|---|---|---|---|
| 8（全量重建后） | — | 9m25s | 6m01s |
| 11 | 1m03s | 8m17s | 2m33s |
| 12 | 1m39s | 8m41s | 2m07s |
| 13（dist 重下） | 5m07s | 9m41s | 5m58s |
| 14 | 1m04s | 8m25s | 1m20s |

### 3.3 构建前必做：清陈旧产物

```bash
rm -rf app/build/outputs/apk
```

**血泪史**：第 11 轮构建命令 600s 超时被杀、未跑到打包阶段，`app/build/outputs/apk` 里残留**上一轮** APK——不清理会把陈旧产物误当新构建交付（md5 与上轮相同才暴露）。第 8 轮更隐蔽：残留 Alpha-10 产物，靠 aapt2 读出版本号不对才识破。**此后每轮必清**。

### 3.4 构建后必做：还原自动递增文件

```bash
git checkout -- app/build.properties   # BUILD_COUNT 构建时自动 +1，不还原会污染提交
```

## 四、内存不足专题（本项目最高频事故）

### 4.1 事故编年

| 轮次 | 现象 | 根因 | 解法 |
|---|---|---|---|
| 5 | release 构建 OOM | **双 Kotlin daemon 并存 + Gradle daemon = 3 个 JVM 叠加**（旧 Kotlin daemon 未退出，新构建又拉一个） | gradle.properties 降配（Gradle 1536m / Kotlin 1024m）+ 构建时 Kotlin daemon 压到 896m |
| 6 | Gradle daemon 直接被系统杀 | 残留 JVM 占着内存，新 daemon 无额可用 | 清残留 JVM 进程后重试 |
| 11+ | 进程无声消失 | 不是 OOM，是沙箱收割后台进程（Gradle daemon 是常驻后台） | `--no-daemon` 单次构建（用完即焚）+ 前台分步执行 |

### 4.2 沉淀出的内存配方（4GB 环境万能组合）

```bash
--no-daemon                          # 不留常驻 daemon，杜绝叠加与被收割
-Dkotlin.daemon.jvmargs=-Xmx896m     # Kotlin 编译 daemon 上限压到 896m
--max-workers=2                      # 限制并行任务数，削内存峰值
# gradle.properties: Gradle JVM 1536m / Metaspace 512m
```

### 4.3 判断是 OOM 还是沙箱收割

- **OOM**：Gradle 日志有 `OutOfMemoryError` / `Daemon disappearing` / heap 报告 → 用 4.2 配方；
- **收割**：日志**戛然而止**无任何错误（常停在 daemon fork 一行）、后台方式跑必死 → 改前台 `--no-daemon` 分步跑。

## 五、签名流水线

### 5.1 脚本化（scripts/sign_r11_release.sh，历轮复用）

```bash
BT=/home/z/android-sdk/build-tools/36.0.0
KS=/home/z/my-project/keystores/wifi-toolbox-release.keystore   # PKCS12/RSA4096

"$BT/zipalign"  -f -p 4 app-release-unsigned.apk app-release-aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-key-alias wifitoolbox \
  --ks-pass pass:*** --key-pass pass:*** --out signed.apk aligned.apk
"$BT/apksigner" verify --print-certs signed.apk     # 证书 DN + SHA-256 打印核对
cp signed.apk "/home/z/my-project/download/wifi工具箱-v3.0.0_Alpha-07-release.apk"
```

### 5.2 密钥体系的坑与决策

- **旧 keystore 与新 JDK 不兼容**：报 `Tag number over 30`（旧 PKCS12 加密格式）→ 新建 keystore（RSA4096/PKCS12，密码随机生成记录于 `KEYSTORE_CREDENTIALS.txt`），证书指纹 `CN=WiFi Toolbox, SHA-256 e405bb91...`，此后历轮不变，**保证已装用户可直接覆盖升级**；
- **CI 与本地同密钥**：4 个 Actions secrets（KEYSTORE_BASE64 / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）配置为与本地同一 keystore → CI 签名产物与本地升级兼容；
- **CI 回退签名的坑**：原 release.yml 签名失败时回退产物是**未签名**（不可安装）→ 改为回退时用 debug 密钥 zipalign+apksigner 重签；
- v1 签名已关闭（minSdk 24 起系统只校验 v2/v3，v1 块约 280KB 死重，包体积优化轮实测移除）。

## 六、APK 五层验证体系

| 层 | 命令/手段 | 验证什么 |
|---|---|---|
| 1 源码 | `ElementTree` 解析各 strings.xml + 残留关键词 grep | 改动完整、无漏网 |
| 2 元数据 | `aapt2 dump badging xxx.apk` | versionCode / versionName 正确（也是识破陈旧产物的利器） |
| 3 资源池 | 解包 APK 的 `resources.arsc` 二进制搜索（**UTF-16LE 与 UTF-8 双编码**） | 用户下载的包里文案真的换了（源码改了≠包里换了；中文在 arsc 常存 UTF-16LE） |
| 4 签名 | `apksigner verify --print-certs` | 证书有效且 SHA-256 与历史一致 |
| 5 端到端 | curl 公开 Release 链接下载回比 md5 | GitHub 上资产=本地验证版，一字节不差 |

## 七、推送与发布流程

### 7.1 提交纪律

```bash
git status --short                    # 逐项核对，只 add 本轮目标文件
git add CHANGELOG.md app/src/main/res/values*/strings.xml   # 显式列举，防污染
git checkout -- app/build.properties  # 还原 BUILD_COUNT 自动递增
git commit -m "v3.0.0_Alpha-07(重发·第N轮): ..."   # 长中文描述：需求→实现→边界→连带清理
git push origin v3.x                  # （历史回滚用过 force-with-lease）
```

**两个真实污染事故**：①python 脚本跑完把 `CHANGELOG.md` 权限位 chmod 644→755（连带 7 个无关文件）→ amend 修复 + `git checkout` 还原；②构建递增的 `build.properties` 混入提交。此后提交前必 `git status` 逐项核对。

### 7.2 CI 触发机制（关键认知）

两个工作流（`构建发行版APK` / `更新 GitHub Pages 上的 CHANGELOG.md`）**均只由 `CHANGELOG.md` 路径触发**：

- 每轮交付都改 CHANGELOG（新条目置顶）→ 推送即触发双工作流；
- **纯文档/纯源码不改 CHANGELOG 的推送不触发 CI**（如第十四轮后单独推 docs 不触发，Release 资产不受影响）；
- `workflow_dispatch` 已补，可手动触发。

### 7.3 CI 侧历史故障修复（一次性投入）

| 故障 | 根因 | 修复 |
|---|---|---|
| GITHUB_TOKEN 无法建 Release | 仓库 Actions 默认权限 read | PUT actions/permissions 改 write |
| Pages 404 | 未启用 Pages | 启用（build_type=workflow） |
| CI 签名产物不可装 | 签名失败回退产物未签名 | 回退改 debug 密钥 zipalign+apksigner 重签 |
| workflow 失败 | 引用未开启的功能 | 去掉 discussion_category |

### 7.4 发布收尾：资产同步脚本（release_sync_roundN.py 模式）

CI 全绿后会用**自己的产物**覆盖 Release 资产，必须重跑同步脚本换回本地构建：

```python
# 脚本模式（幂等，可重复跑）：
# 1. PAT 从 remote URL 提取（https://<pat>@github.com/...）
# 2. changelog_body(): 取 CHANGELOG.md 顶段（首个 # 标题下的非空行）
# 3. GET /releases/tags/{tag} → rel_id
# 4. PATCH /releases/{rel_id}  body=顶段正文（含本轮断言关键词）
# 5. 逐个 DELETE /releases/assets/{id}（删 CI 资产）
# 6. POST uploads.github.com/.../assets?name=xxx.apk 上传本地双 APK
#    （资产名用 ASCII：wifi-toolbox-<tag>-{release,debug}.apk）
# 7. 复查资产列表 state=uploaded + 正文含断言关键词
```

发布后**端到端验证**：curl 公开下载链接拉回两 APK，md5 与本地逐一比对一致，本轮闭环。

### 7.5 本项目的版本与 tag 惯例

- **重发系列**（第 8 轮起）：versionCode=6 / v3.0.0_Alpha-07 恒定不升，每轮覆盖同一 Release 的资产与正文（CHANGELOG 逐轮置顶记录差异）；
- **tag 不移动**：tag 停在早期提交（b8eed57，第二轮），历轮只经 API 更新 Release 内容——Release 正文承担版本语义，避免移动 tag 使已分发引用失效；
- 本地交付文件名用中文（`download/wifi工具箱-v3.0.0_Alpha-07-*.apk`），Release 资产名用 ASCII（上传兼容性）。

## 八、踩坑速查表

| 症状 | 真因 | 一招制敌 |
|---|---|---|
| BUILD SUCCESSFUL 但脚本退出码 1 | `set -e` + 尾部 `ls\|grep` 在产物目录缺失时无匹配 | 展示性命令加 `\|\| true`，成败只认 Gradle BUILD 行 |
| 构建后 APK md5 与上轮相同 | 超时被杀，残留上轮产物 | 构建前 `rm -rf app/build/outputs/apk` |
| aapt2 读出版本号是旧版 | 同上（更隐蔽） | 同上 + 每轮 aapt2 验证 |
| `Toolchain does not provide [JAVA_COMPILER]` | 系统 JDK 是纯 JRE | 自装 Temurin JDK17 并 export JAVA_HOME |
| git 仓库回退/工作区冒出陈旧改动 | 沙箱整体回收，快照错位 | `git fetch && git reset --hard origin/v3.x`（远端是唯一真相） |
| gradle 首轮编译突然 5 分钟 | dists 被回收重新下载 | 正常现象，无需处理 |
| 编译进程无声消失 | 沙箱收割后台进程 | `--no-daemon` 前台分步 |
| release OOM / daemon 被杀 | 多 JVM 叠加超 4GB | 第四节内存配方 |
| git status 冒出未知改动 | build.properties 递增 / python chmod 污染 | 提交前逐项核对 + checkout 还原 |
| 签名 verify 失败 `Tag number over 30` | 旧 PKCS12 与新 JDK 不兼容 | 新建 keystore + CI secrets 同步 |
| tag 指向不是本轮提交 | 重发系列惯例（API 更新 Release 不动 tag） | 无需处理，确认惯例即可 |

## 九、可复用资产清单（scripts/）

| 脚本 | 用途 |
|---|---|
| `setup_android_sdk.sh` | 环境被回收后的 SDK 全量重建 |
| `build_r14.sh`（模式） | 分步构建（compile→assembleRelease→assembleDebug，防 OOM 参数内嵌） |
| `sign_r11_release.sh` | 签名流水线（zipalign→sign→verify→交付 download） |
| `release_sync_roundN.py`（模式） | Release 正文 PATCH + 资产删除/上传（每轮 sed 派生断言关键词） |
| `inject_strings_*.py`（模式） | 多语言字符串注入（锚定插入 + XML 校验 + 键一致性检查） |
| `add_changelog_rN.py`（模式） | CHANGELOG 条目置顶注入（跑后核对文件权限位） |

## 十、一页速查（新一轮构建发布 SOP）

```
1. 环境预检: ls /home/z/jdk/bin/javac /home/z/android-sdk/{platforms,build-tools}
   （环境被回收: git fetch && git reset --hard origin/v3.x + setup_android_sdk.sh）
2. 改码/改资源 → 源级校验（XML 解析 + 残留 grep）
3. rm -rf app/build/outputs/apk
4. CHANGELOG 置顶新条目（必做——这是 CI 触发器）
5. bash build_rN.sh compileReleaseKotlin → assembleRelease → assembleDebug
   （--no-daemon -Dkotlin.daemon.jvmargs=-Xmx896m --max-workers=2）
6. bash sign_r11_release.sh → 交付 download/ 双 APK
7. 五层验证: aapt2 badging / arsc 二进制 / apksigner / （发布后补端到端）
8. git checkout -- app/build.properties；git status 逐项核对；显式 add；
   commit（长描述）→ push origin v3.x
9. 等 CI 双工作流全绿（约 8~12 分钟）
10. python3 release_sync_roundN.py（换回本地构建资产）
11. curl 公开下载 → md5 比对 → 更新 worklog → 闭环
```
