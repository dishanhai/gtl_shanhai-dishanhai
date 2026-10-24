# shanhai-rewrite — 山海重构 阶段 1 工程

| 项 | 值 |
|---|---|
| mod id | `shanhai` |
| 包根 | `com.shanhai` |
| MC / Forge | 1.20.1 / 47.4.16 |
| 构建产物 | `build/libs/shanhai-0.1.0.jar` |
| 规格来源 | `../temp/docs/specs/phase-1-spec.md`（冻结 v1.0，冲突以它为准） |
| 接口契约 | `../temp/docs/specs/phase-1-interface-contract.md`（写代码时随手对照） |

本工程是**独立新工程**，不继承上游 `gtl_shanhai-dishanhai` 的代码；上游只作为行为参照
（见 `../temp/originals/upstream/gtl_shanhai-dishanhai/`）与 `libs/` 来源。

---

## 1. 一页构建（已实测）

```powershell
$env:JAVA_HOME  = '<你的 JDK 17 路径>'      # 必须 JDK 17，例如 ...\corretto-17\jdk17.0.19_10
$env:HTTPS_PROXY = 'http://127.0.0.1:6987'  # 可选：直连 GitHub / ForgeGradle 失败时才需要（换成你自己的代理地址）
$env:HTTP_PROXY  = 'http://127.0.0.1:6987'

cd <仓库路径>\shanhai-rewrite               # 即本 README 所在目录
& '<你的 Gradle 8.8 路径>\bin\gradle.bat' `
    build -x test --no-daemon
```

- 仓库**没有** `gradlew`，只能用本机已装的 Gradle 8.8（用绝对路径调它的 `bin\gradle.bat`）。
  不知道本机路径时：`Get-Command gradle` 看当前解析到哪个；或看 Gradle 发行包里的
  `wrapper\dists\gradle-8.8-bin\<哈希>\gradle-8.8\bin\gradle.bat`。
- 产物：`build\libs\shanhai-0.1.0.jar`。
- ⚠️ **运行**游戏用的是 **Java 21**（`<你的 JDK 21 路径>`），与构建 JDK 17 不是同一个，别搞混。

---

## 2. `libs/` —— 必须自建，已被 .gitignore 排除

`libs/` 不在版本控制里。重建方法：从参照实例的 `mods\` 目录（或上游工程的 `libs\`）拷进来。

**当前 33 个 jar / 99 MB**，关键几个：

| jar | 为什么需要 |
|---|---|
| `gtceu-1.20.1-1.4.4.jar` | 主框架：多方块 / `GTRecipeType` / `PartAbility` |
| `gtlcore-1.2.3.2.jar` | ⭐ **必需**。`IModularMachineHost` / `IModularMachineModule` 接口就在这里（**不在 gtladditions 里**） |
| `gtladditions-3.2.1Custom_SubSpace.jar` | 结构数据 `forge_of_the_antichrist.bin` + `MultiBlockStructure` 中枢类 |
| `Registrate-MC1.20-1.3.3.jar`、`mixinextras-forge-0.2.0.jar` | 从 gtceu jar 的 `META-INF/jarjar/` 解出来的 |

> 🔴 **只放一个 gtlcore**：上游 `libs/` 里还有一个 `[GTLCore]gtlcore-1.2.3.1-fix9.jar`，
> 本工程**故意没有拷**它 —— 两个 gtlcore 同时在类路径上会产生重复类。
> 如果要重新拷 libs，记得同样排除它。

---

## 3. 目录约定（t3 / t4 / t5 请按这里放代码）

```
src/main/java/com/shanhai/
├── ShanhaiMod.java                 # mod 入口（@Mod("shanhai")）—— t2 已建立
├── common/machine/…                # t3：青铜神锻主机
├── common/machine/module/…         # t4：原初模块框架 + 物质重组核心
└── registry/…                      # t5：17 个物质模块物品 + 核心物品注册

src/main/resources/
├── META-INF/mods.toml              # t2 已建立（依赖声明：forge/minecraft/gtceu/gtlcore/gtladditions）
├── pack.mcmeta                     # t2 已建立
├── assets/shanhai/lang/zh_cn.json  # t5
└── assets/shanhai/textures/…       # t5
```

- **注册入口**：t5 如需一个统一的 `DeferredRegister` 持有者，请放在 `com.shanhai.registry`；
  t3/t4 的方块/机器注册若依赖它，双方自行对齐（规格未指定，属实现细节）。
- 各人的类名请严格按规格：主机 `PrimordialOmegaEngineMachine`、模块基类 `PrimordialModuleBase`、
  模块 `PrimordialMatterRecombinatorCore`（规格 §3.2 / §5.2 已冻结）。
- 槽位几何请放到一个共享类（规格里叫 `ModuleSlotGeometry`），**t3 与 t4 都要用它**（F-1 / F-2 互为对偶），
  避免两边各写一份导致不匹配。

---

## 4. 已知构建坑（都真实炸过，来自 `../.dsh/skills/shanhai-build/SKILL.md`）

1. **改 `build.gradle` 不能用 `Set-Content -Encoding UTF8`** —— 会加 BOM，Gradle 报
   `Unexpected character: '?' @ line 1, column 1`。用无 BOM 的 UTF-8 写（本仓库的 .gradle 文件都是无 BOM）。
2. **`gradle.properties` 是 ISO-8859-1**（Java properties 规范），**不要在里面放中文**。
   中文显示串直接写在 `mods.toml` 里（`processResources` 的 `filteringCharset = 'UTF-8'` 保证正确）。
3. **PowerShell 路径含 `[]` 必须 `-LiteralPath`**：`libs/` 里就有 `[GTLCore]…jar` 这种名字，
   不加 `-LiteralPath` 时 `Test-Path` 会返回 False、`Copy-Item` 找不到文件。
4. **`.ps1` 脚本必须是纯 ASCII**：本机 pwsh 会把「UTF-8 无 BOM」的 `.ps1` 按 GBK 解码，
   中文字面量全乱、语法直接崩。内联 `-Command` 传中文没问题。
5. **改 mod jar 内部文件绝对不要用 .NET `ZipFile` 的 `Update` 模式**（会产出 Java 拒绝的 zip）。
   用 JDK 自带的 `jar.exe`，并且用 `JarInputStream.getNextJarEntry()` 校验条目数。

---

## 5. 本工程当前状态（t2 交付时）

- [x] `settings.gradle` / `gradle.properties` / `build.gradle` / `.gitignore`
- [x] `src/main/resources/META-INF/mods.toml`（含 gtlcore / gtladditions 依赖声明）
- [x] `src/main/resources/pack.mcmeta`
- [x] `src/main/java/com/shanhai/ShanhaiMod.java`（mod 入口，可被 Forge 加载）
- [x] `libs/`（33 jar）
- [x] `gradle build -x test --no-daemon` → BUILD SUCCESSFUL
- [ ] t3 主机 / t4 模块 / t5 物品与资产（各自的并行任务）
