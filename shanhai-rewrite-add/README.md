# shanhai-rewrite-add（山海：扩展）

这里是「山海扩展」mod 的工程目录。**2026-10-18 起它不再是空占位：阶段 1（只建骨架）已落地** ——
一个能独立构建的空壳 mod 工程，依赖本体、强制 `AFTER`、一个功能都没搬。
⚠️ 本目录里的东西全是新增的；`shanhai-rewrite\`（本体）**一个文件都没改**。

## 它将来放什么

| 归属 | 内容 |
|---|---|
| **本体** `shanhai-rewrite`（`/shanhai`） | 机器 / 物品 / 配方类型 / 材质 / JEI / 创造栏、配方编辑器、物品三件套、全息菜单、现实物品；免死只做「事件层 + 每 tick 直写 `setHealth(max)`」（不注入 mixin）；清除实体 = 执行 `kill @e`（排除玩家） |
| **扩展** 本目录（`/shanhai_add`） | 世界时钟（暂停 / 倒流 / 加速）、重力调制、额外防护手段（mixin 拦 `Entity#setRemoved`、`LivingEntity#setHealth` 注入、攻击端逐级抹除、以后的字节码手段）、逐级版清除实体 |

## 已定的硬口径

- 依赖**单向**：add 强制依赖 body；body **不依赖** add（`ModList.isLoaded` + 桥接口）。没装 add 时，相关功能入口隐藏/标灰，游戏照常。
- 命令两个根：`/shanhai`（本体）与 `/shanhai_add`（扩展）——避开 Brigadier 同名根冲突。
- 拆 mod **挡不住** mixin 失败导致的启动 FATAL；它的价值是「玩家可以不装」「出问题能一刀切定位」。
- 落地顺序（设计 §11 待确认 9）：**① 骨架 → ⑤ 桥 + 简单抹除 → ④ 逐级 + 注入 → ② 时钟 → ③ 重力 → ⑧ 全息能力位**。
  现在完成的是 **①**。

---

## 阶段 1 已落地（2026-10-18）

### 交付了什么

| 文件 | 用途 |
|---|---|
| `settings.gradle` | 独立构建（不 include 本体、不做 composite），`rootProject.name = mod_id` |
| `gradle.properties` | `mod_id=shanhai_add`、`mod_version=0.1.0`、`body_version=0.1.0`、MC/Forge/mappings 版本（**纯 ASCII**，Java properties 按 ISO-8859-1 读） |
| `build.gradle` | 与本体同构：`toolchain(21)` + `options.release=17`、`compileOnly` 依赖、**refmap 那 96 行机制**（输出 `shanhai_add.refmap.json`）、两个 fail-fast 校验任务 |
| `src\main\resources\META-INF\mods.toml` | `modId=shanhai_add`、`displayName="山海：扩展"`、`[[dependencies.shanhai_add]]` 声明对 `shanhai` 的 `mandatory=true` + `versionRange` + `ordering="AFTER"` |
| `src\main\java\com\shanhai\add\ShanhaiAdd.java` | 空壳入口：`@Mod("shanhai_add")`，构造器只打一行 `[SHANHAI-ADD] loaded` |
| `src\main\resources\shanhai_add.mixin.json` | 主 mixin 配置，`package=com.shanhai.add.mixin`，**entries 为空**、`required:false`、`refmap=shanhai_add.refmap.json` |
| `src\main\resources\shanhai_add.gtlcore.mixin.json` | gtlcore 那一条（设计 §2.3）的配置，`package=com.shanhai.add.mixin.gtlcore`，同样为空 / fail-soft |
| `src\main\resources\pack.mcmeta` | 最小的资源包声明（`pack_format=15`）。**不在任务书的 6 项里、属额外加的**：本体 `libs\` 里 4 个「零 assets」的真实 mod（mixinextras / rhino / architectury / glodium）**都带** `pack.mcmeta`；少了它，mod jar 作为资源包加载时至少会吃一行 WARN。不要就删掉，不影响编译 |
| `.gitignore` | 忽略 `build/`、`.gradle/`、`libs/*`（`libs/` 本工程**不建**，直接用本体的） |

**依赖怎么取**（全部相对本工程目录，不写死绝对路径）：

| 什么 | 怎么拿 | 为什么不进 add 的 jar |
|---|---|---|
| 本体 jar | `compileOnly files('../shanhai-rewrite/build/libs/shanhai-<body_version>.jar')` | `compileOnly` ⇒ 本体几千个类不进 add 产物；运行期由 Forge 先加载本体（`mandatory=true` + `AFTER`） |
| 本体 `libs\`（34 个 vendored jar） | `compileOnly fileTree(dir: '../shanhai-rewrite/libs', include: '*.jar')` | ① `jar` 任务只打 `sourceSets.main.output`，没有 shadow/jarjar 就不内联依赖；② `compileOnly` 连运行时 classpath 都不进 |
| mixin 注解处理器 | `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'` | 只用来生成 refmap |

### 构建命令

```powershell
# 顺序不能反：add 只吃本体的 jar（不编它的源码）
cd <仓库根>\shanhai-rewrite        ;  gradle build --no-daemon     # 产物 build\libs\shanhai-0.1.0.jar
cd <仓库根>\shanhai-rewrite-add    ;  gradle build --no-daemon     # 产物 build\libs\shanhai_add-0.1.0.jar
```

- 仓库没有 wrapper，用本机那份 Gradle 8.8（命令形态与本体完全一致；`JAVA_HOME` 也照本体那条来，
  `toolchain 21` 会自己找 JDK 21 编、`options.release=17` 出 Java 17 字节码）。
- ⚠️ add 编的是**本体 jar 里的 API**，不是本体源码：本体源码改过就必须先重建本体
  （`verifyBodyJar` 会 `WARN`「本体 jar 比本体源码旧」，但不会替你重建 —— 版本号没变时它抓不到内容变化）。
- 两个构建期校验（任一不过就 **fail**，不许"编过了但跑起来没桥"）：
  - `verifyBodyJar`：本体 `gradle.properties` 在不在、`mod_version` / `body_version` / 本体 jar 内
    `mods.toml` 的 `version` **三处必须相等**、本体 jar 必须存在。`files()` 对显式给出的缺失文件
    不做存在性校验，所以这道检查不能省。
  - `verifyRefmap`：**阶段 1 没有 mixin 源码 ⇒ 跳过**（打一行日志）；一旦
    `src\main\java\com\shanhai\add\mixin\` 下出现 `.java`，就变成硬校验（refmap 必须在、且含 `"mappings"`）。

### 只装本体（玩家不装扩展）

- 本体**不认** add：本体的 `mods.toml` 没有、也不许有 `shanhai_add` 依赖，本体代码里也不许 `import com.shanhai.add.*`。
- 所以：`mods\` 里只放 `shanhai-0.1.0.jar`，**不要**放 `shanhai_add-*.jar`，游戏照常起。
- 反向不成立：只放 `shanhai_add-*.jar` 时，FML 会以「缺少强制依赖 shanhai」拒绝加载 add（这是**对的**，
  看到的是缺依赖界面，而不是 `NoClassDefFoundError`）。

### 成对部署（开发/测试实例）

```powershell
# 两个 jar 必须【同号】，且两条日志最后一行都是 end ok=True，再启动游戏
& <仓库根>\temp\tools\deploy-watch.ps1 -Src '<仓库根>\shanhai-rewrite\build\libs\shanhai-0.1.0.jar'     -Dst '<实例>\mods\shanhai-0.1.0.jar'     -Expect <sha256前16位>
& <仓库根>\temp\tools\deploy-watch.ps1 -Src '<仓库根>\shanhai-rewrite-add\build\libs\shanhai_add-0.1.0.jar' -Dst '<实例>\mods\shanhai_add-0.1.0.jar' -Expect <sha256前16位>
```

- 两条顺序无所谓（各锁不同目标文件），但**必须都 ok 再启动**：只换本体、add 还是旧版时，
  add 的 `versionRange="[<body_version>,)"` 可能拒载 ⇒ 表现为"扩展凭空消失"，比崩更难查（设计 §8.1）。
- **回滚 = 删掉 `mods\shanhai_add-*.jar`**（这就是拆分的核心价值）。本体出问题则从同一个
  `deploy-snap-<stamp>\` 里**成对恢复**两个 jar，避免版本错配。

### 命名：`gtlcore` 而不是 `gt`

任务书写的是 `shanhai_add.gt.mixin.json` / `com.shanhai.add.mixin.gt`；**落地按设计文档 §2.3 用了
`shanhai_add.gtlcore.mixin.json` / `com.shanhai.add.mixin.gtlcore`**，理由：
本体已经有一份对称的 `shanhai.gtlcore.mixin.json`（`package: com.shanhai.mixin.gtlcore`，装
`ShanhaiGtlcoreSmoothTickClockMixin`）。阶段 4（时钟，设计 §11 待确认 9 的 ②）搬这条 mixin 时就是
"整个目录照搬"，不用同时改文件名、`package`、MANIFEST 三处。**要改成 `gt` 说一声**（改 3 处：两个文件名 + 两个 `package` + `build.gradle` 的 `MixinConfigs`）。

### 阶段 1 明确**没做**的事

- 没有命令（`/shanhai_add` 还没注册）、没有配置 `ShanhaiAddConfig`、没有桥（`com.shanhai.api.*` 在 body 侧、
  由阶段 2 新增；本工程一行都没碰 body）、没有 mixin 类、没有 `assets`/lang（只有一份最小的 `pack.mcmeta`）、
  没有 `runs`（不给 add 配 dev-run，验证走 `temp\smoke-rig` 的专用服务器，设计 §11 待确认 7）。
- 没有跑过 `gradle build`（本次只做静态自检：JDK 21 `javac --release 17` 编译通过 + JSON/TOML 结构核对）。

## 下一步（阶段 2）

按设计 §11 待确认 9 的落地顺序，**阶段 2 = 第 ⑤ 项「桥 + 简单抹除」**，而且它**先动本体、不动本目录的功能**：

| 次序 | 做什么 | 动哪里 |
|---|---|---|
| 2-a | 新增 `com.shanhai.api.ShanhaiAdd`（接口）+ `ShanhaiAddHolder`（`present()` / `install()` / `get()`），body 侧只认接口 | `shanhai-rewrite\src\main\java\com\shanhai\api\`（**新文件**，由主代理统一做，本目录不管） |
| 2-b | 新增 `ShanhaiPurgeSimple`（逐维度 `kill @e[type=!player]`，设计 §3.3 方案 B）+ `ShanhaiGuardCommand` 的 `purge` 改调它 | 同上（body 侧） |
| 2-c | add 侧接桥：`com.shanhai.add.AddBridge implements com.shanhai.api.ShanhaiAdd`，在 `ShanhaiAdd` 构造器里 `ShanhaiAddHolder.install(...)` —— **这是本目录第一次真正出现业务类** | `shanhai-rewrite-add\src\main\java\com\shanhai\add\` |

**第一个真正"从本体搬进 add"的包是阶段 3 的 `com.shanhai.common.guard` 的 add 半份**（设计 §1.2）：
`ShanhaiPurge` / `ShanhaiGuardSuppression` / `ShanhaiGuardSuppressWindow` / `ShanhaiGuardCallerTrace` /
`ShanhaiGuardEntityGuard` 五个类 + 4 个免死 mixin（搬到 `com.shanhai.add.mixin`）+ `ShanhaiGuardCommand` 的
add 半份（`/shanhai_add purge|suppress`）。⚠️ 那一刻起 `verifyRefmap` 会从"跳过"变成**硬校验**，
必须同时确认 jar 根有非空的 `shanhai_add.refmap.json`。

## 待验（只有真跑一次构建/启动才知道）

1. `gradle build` 能否成功（`verifyBodyJar` 是否会因为本体 jar 与 `body_version` 不一致而先红）。
2. 零 mixin 源码时，mixin 注解处理器会不会产出（空）`build\mixinrefmap\shanhai_add.refmap.json`；
   `jar` 里的 `from {}` 是延迟求值、缺失也不报错，`verifyRefmap` 此时应当**跳过**。
3. MANIFEST 里列了两份 mixin 配置但都 `required:false` 且 entries 为空 —— Mixin 大概率只打一行 WARN，
   需在真启动日志里确认没有 `Mixin` 级别的 ERROR。
4. `[[dependencies.shanhai_add]]` 的 `mandatory=true` + `ordering="AFTER"` 在冒烟服务器上的真实表现：
   装 add 不装 body 时应出现「缺少强制依赖 shanhai」，而不是类加载错误（设计 §9 C2）。
5. **本体 jar 新鲜度**：`verifyBodyJar` 会 `WARN`（不是 fail）「本体 jar 比本体源码旧」。这个坑真的会踩：
   另一条线在 2026-10-18 13:03 还在往本体加 `shanhai.ae2.mixin.json`（`build.gradle` 的 `MixinConfigs`
   从 5 个变 6 个），而 `build\libs\shanhai-0.1.0.jar` 是更早的产物 —— **版本号不变，所以版本校验抓不到**。
   阶段 2 之后 add 要 `import com.shanhai.api.*`，拿旧 jar 编就是 `cannot find symbol`。⇒ 每次编 add 前先重建本体。

## 参考

- 拆分设计清单：`temp\modsplit\设计.md`（§1 归属表 / §2 mixin / §6 桥 / §7 Gradle 骨架 / §8 部署 / §9 冒烟）
- 倒流设计：`temp\reversal\设计.md`
- 时钟 / 重力 / 守卫现状：`shanhai-rewrite\src\main\java\com\shanhai\{common\time, common\gravity, common\guard}`
