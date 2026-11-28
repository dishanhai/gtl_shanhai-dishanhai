# 摘星者 Jade 工程遥测 HUD

准星指向摘星者主控、12 类专用结构方块，或只接入一台摘星者的 GT 仓室时，Jade 显示恒星工程面板。默认紧凑显示，Jade 的“显示详情”键展开主控参数和品牌资料，默认键为左 Shift。普通 GT 仓室失去关联时恢复原生 Jade。

## 配置

配置位于 `config/gt_shanhai/ui-client.toml` 的 `[starLifter.jade]` 段。Jade 插件设置中的“寰宇联合重工 · 摘星者 HUD”可独立切换。

| 键 | 用途 |
| --- | --- |
| `mode` | `ORIGINAL` 使用原样 Jade。普通观察可选 `MINIMAL` 或默认的 `STANDARD`。`ENGINEERING` 常驻详情。 |
| `motion` | `ULTRA`、`HIGH`、`STANDARD`、`PERFORMANCE`、`REDUCED`、`DISABLED`。Jade 关闭动画时静态显示。UUK 减弱动效和低性能偏好限制动画档位。 |
| `brand`、`icons`、`scan`、`glow`、`digits` | 分别控制品牌揭示、图标动效、扫描、低亮度辉光与数位滚动。 |
| `backgroundOpacity`、`brandDetails` | 设置背景透明度和详情页的品牌资料。制造商署名常驻。 |

### 工程终端启动

`[starLifter.jade.boot]` 管理独立启动序列。`enabled` 总开关和 `quality = "DISABLED"` 都会立即显示静态工程面板。`brandIntro`、`terminalText`、`deviceIdentification`、`structureScan`、`interfaceMorph` 和 `brandMotto` 分别控制视觉阶段。`quality` 设定启动质量上限，并遵从 Jade 动画开关和 UUK 的减弱动效与低性能偏好。`PERFORMANCE` 保留捕获、终端反馈和轻量过渡，`REDUCED` 使用短透明度过渡，宣传语只在 `HIGH` 和 `ULTRA` 显示。

| 启动策略 | 条件 | 默认时长 |
| --- | --- | --- |
| Cold Boot | 首次观察，或挂起缓存到期后重新观察 | `coldBootMs = 720` |
| Fast Boot | 观察另一台主控或尚未确认归属的新模块 | `fastBootMs = 320` |
| Warm Boot | 挂起窗口内重新观察最近的目标 | `warmBootMs = 100` |
| Module Transition | 权威遥测确认两个目标属于同一主控实例 | 140 ms |
| Sub-terminal Boot | 展开 Jade 工程详情 | 220 ms |

准星移开时收束面板，退出目标为 135 ms。`cacheMs = 1800` 从离开时计起，客户端只保留最近一个启动目标。未缓存的模块先按 Fast Boot 接入，回包确认同一台主控后可接续为模块过渡。新的世界或资源重载会重新初始化视觉缓存。

启动进度线表示本地视觉时间。方块名称、工程分类和已有机器状态从首个可绘制帧开始显示。连接标签和扫描节点的成功反馈来自现有权威快照。若遥测超时，运行面板显示不可用，基础方块信息继续展示。Shift 展开与主面板启动并行，Jade 的坐标、库存和其他贡献行延续原有显示路径。

## 数据和生命周期

1. `StarLifterHudTarget` 映射注册 ID 和功能图形。GT 仓室通过已同步的仓室控制器关系加入面板。仓室类和区块容量沿用 GT 和其他 Jade Provider 的输出。
2. `StarLifterHudTelemetry` 通过 GTCEu 的分区块索引筛选候选巨构，用现有匹配集合和摘星者的旋转模板验证成员。GT 仓室另外核对已登记的控制器坐标。服务端用 `getChunkNow()` 检查主控区块，同一刻和同一主控复用快照。
3. `StarLifterHudNetwork` 使用 Forge 的独立可选通道。Jade 11 的方块数据 Provider 以方块实体为目标，专用外壳、服务节点舱体、预留接口等是普通方块。专用通道让这些成员通过同一数据路径工作，且可在未安装 Jade 的服务端提供数据。服务端检查方向、维度、距离、区块和角色。
4. `StarLifterHudCache` 用客户端世界、坐标、方块状态、方块实体识别目标，用主控位置和实例序号共享快照。可见且在当前目标上的 Jade 渲染前回调触发请求。稳定目标约每 250 ms 请求一次，切换受全局间隔限制。客户端切换目标时使旧请求失效，回包和已缓存快照都按对应请求的发出时间检查 700 ms 有效期。

储备来自实际恒星采集额度和消耗。批次进度、配方、并行、配方负载、能量存储和吞吐、已完成批次来自机器及 GTCEu。星环开关反映世界演出开关，共鸣表示实际链接状态。装置没有独立的环段同步率或每块外壳的运行数据。普通结构面板显示其真实结构用途和连接，详情可读所属主控的数据。动态图形替代的世界环体沿用现有世界选择规则，实体化的结构方块可以直接作为 Jade 目标。

## 渲染和兼容

- `StellarJadeProvider` 仅移除目标的通用名称和模组名，以及主控重复的 GT 状态／进度元素。原有坐标、可采掘信息、仓室库存及其他 Provider 输出仍在 Jade 列表内。
- 摘星者内容按自身占位高度绘制，和后续 Jade 行留出 4 个 GUI 像素。可视高度不足时，专属内容按比例缩小，给原生 Jade 行保留空间。主控已有原生绝对坐标时只显示一组坐标。仓室保留面板内的归属主控坐标，以及 Jade 提供的被瞄准方块坐标。
- Jade 11.13.x 适配器复用已调度的渲染前回调和正在渲染的 Jade 矩形。Jade 管理锚点、缩放、隐藏规则、按键、文本朗读以及回退到原样提示的交接。
- `StellarHudPanel` 和 `StellarHudStyle` 使用 SH-Better-UI 时钟、可打断动画和全局主题。背景、角标、扫描、品牌、图标和数据插值共用时间。向量运算在 GUI 双线边缘绘制，无世界恒星资源依赖。文字和关键状态保留原始像素字形与权威语义。真实储备随有效遥测采样保留有限趋势窗口。
- `CuhiIdentity` 和 `StellarEngineeringArt` 提供 Full、Standard、Compact、Micro 品牌资源。摘星者物品工程档案共用制造商署名。机器终端、启动和星环预览仍使用各自的既有布局。
- 仓库发布目标是 Minecraft 1.20.1、Forge 47.4.16、Java 21。此次选用 UUK 1.0.14。Jade 11.13.2 与 11.13.3 的所用回调及渲染接口已比对。NeoForge 属于 Jade 的发布范围，当前项目没有 NeoForge 构建入口。[Jade 11.13.3 发布页](https://modrinth.com/mod/jade/version/11.13.3%2Bforge)

## 修改位置

| 文件 | 内容 |
| --- | --- |
| `integration/jade/DShanhaiJadePlugin.java`、`integration/jade/provider/StellarJadeProvider.java` | 专属 Jade 注册、分流和原生显示兼容。 |
| `common/machine/starlifter/StarLifterHudTarget.java`、`StarLifterHudTelemetry.java` | 设备角色、结构关联、权威数据与编码。 |
| `network/StarLifterHudNetwork.java`、`ShanhaiNetwork.java` | 可选通道及请求注册。 |
| `client/gui/starlifter/StarLifterHudCache.java` | 客户端请求限频、共享快照和过期。 |
| `client/gui/starlifter/StellarHudPanel.java`、`StellarHudReadout.java`、`StellarHudStyle.java` | 动态布局、状态、数据动效和主题。 |
| `client/gui/starlifter/CuhiIdentity.java`、`StellarEngineeringArt.java`、`StarLifterDossier.java` | 品牌字样、解析矢量标识及物品档案署名。 |
| `config/ShanhaiUiClientConfig.java`、`assets/gt_shanhai/lang/{zh_cn,en_us}.json` | 配置及中英本地化。 |
| `StarLifterHudTelemetryTest.java` | 验证数值零、过期、故障与跳过无效配方数据的网络编码。 |

## 实机与短时性能对照

Forge 47.4.16、Jade 11.13.3、UUK 1.0.14 的同一隔离存档和同一待机主控视角交替比较 `ORIGINAL` 与 `STANDARD`。每种模式两段，每段跳过前 2 秒后采集约 4.5 秒。实际帧缓冲为 2560 × 1601，帧率上限为 120，显卡为 NVIDIA GeForce RTX 4060 Laptop GPU。GPU 数值取 Jade 绘制区间的 OpenGL 时间戳，调用数为该区间的索引绘制。原始数据与截图位于 `tmp/star-lifter-jade/evidence`。

| 指标 | 原样 Jade | 摘星者 Jade |
| --- | ---: | ---: |
| 有效帧数 | 1,054 | 1,050 |
| 平均 FPS | 116.23 | 116.87 |
| 帧时间 P95 / P99 | 11.02 / 11.89 ms | 10.80 / 11.42 ms |
| Jade CPU 中位数 / P95 | 0.131 / 0.234 ms | 0.113 / 0.179 ms |
| Jade GPU 中位数 / P95 | 0.004 / 0.605 ms | 0.009 / 0.015 ms |
| Jade 索引绘制／帧 | 12 | 5 |
| Jade 渲染线程分配量 | 31.4 kB／帧 | 23.7 kB／帧 |
| 集成服务端 Tick 中位数 / P95 | 3.15 / 4.81 ms | 2.98 / 4.34 ms |
| 专属请求与回复 | 0 | 各 52 条，约 4 次／秒 |
| 专属编码字节 | 0 | 5,096 B，约 392 B／秒 |

两轮快照分别采到 5.80／5.84 GB 和 6.05／5.59 GB 使用堆。各轮结束的 JVM 累计 GC 次数和时长保存在 `memory.csv`。这些整进程读数包含世界和资源加载缓存，无法归因到单个 HUD，也不足以评估长时间驻留的增长趋势。帧时间样本属于同一主机的短时待机结果。

隔离客户端核对了主控、已安装 GT 仓室、独立汇流槽、石头、自定义模式与原样模式往返、多次展开后重捕获、GUI Scale 切换与拆除后失效提示。服务端库存 Provider 的编程电路仍出现在仓室面板内。交付构建通过摘星者相关的 36 项测试，中英文各 75 个 HUD 与 CUHI 资源键通过键集合和占位符对应检查。

Jade 11.13.2 的英文实例用交付 JAR 重跑上述场景并核对全部 13 个直接注册目标。向真实输入仓室补充蒸馏水与编程电路后，配方进入 2,800 刻的生产批次，并实际消耗能量及恒星储备。电量不足后，HUD 展示配方余留进度和 GT 的缺能原因。英文截图和数据位于 `tmp/star-lifter-jade/alternate/evidence`。该组短测中索引绘制从 12 次降到 4–5 次，帧时间 P99 从 13.22 ms 到 13.92 ms，整机帧时存在环境波动。

无 Jade 的 Forge 独立服务端使用同一交付 JAR，在隔离目录完成世界启动并正常保存关闭。隔离包保留 KubeJS 与整合包配置并移出 Oculus 和 Just Enough Characters 两个客户端模组。证据在 `tmp/star-lifter-jade/dedicated-no-jade.log`。本次未采集独立服务器的多人连接、丢包、切维度和长时间驻留数据。相关实测项见 `TODO.md`。

## Jade Terminal Boot 实测

Forge 47.4.16、Jade 11.13.3、UUK 1.0.14、RTX 4060 Laptop GPU，实际帧缓冲 2560 × 1601。发布包 SHA-256 为 `BA5776E1809FFBC7A49F41C85E927DED38E2D798DE81E493938156C049AFC6E1`。编译、重映射及发布包构建通过，定向测试 12 类共 41 项，16 个新增键的中英文键名和占位符一致。

| 场景 | 从首个对应阶段到在线帧 |
| --- | ---: |
| 主控完整冷启动 | 720–726 ms，五次 |
| 切到无归属汇流槽的 Fast Boot | 323 ms |
| 准星移开后恢复已接入仓室的 Warm Boot | 100 ms |
| 回包确认同一主控后的模块过渡 | 144 ms |
| 帧率上限设为 15 时的完整冷启动 | 801 ms |

冷启动首个可绘制帧已有名称、工程分类和状态，阶段采样额外等待为 0 ms。模拟迟到的回包在该场景的第 672 ms 生效，视觉状态在第 502 ms 已在线，同时面板仍显示等待。持续丢弃回包时，面板在第 848 ms 转为遥测不可用，名称和分类持续显示。冷启动中途移开准星，再观察同一主控后进入 Warm Boot，恢复至在线用时 101 ms。

| 采样区间 | 帧数 | 整帧 P95 / P99 | Jade CPU 中位 / P95 / P99 | Jade GPU 中位 / P95 / P99 | 分配中位 / P95 |
| --- | ---: | --- | --- | --- | --- |
| 资源重载后四次冷启动的阶段帧 | 286 | 20.15 / 57.33 ms | 0.192 / 0.528 / 4.415 ms | 0.006 / 0.073 / 10.444 ms | 26.88 / 53.99 kB/帧 |
| 约 10 秒同巨构快速切换 | 1,003 | 17.58 / 42.07 ms | 0.196 / 0.522 / 1.195 ms | 0.005 / 0.011 / 0.271 ms | 23.75 / 24.50 kB/帧 |

GPU 使用 Jade 绘制区间的 OpenGL 时间戳。分配量取渲染线程在该区间的字节增量。发布包复测捕获 54 次模块识别和 0 次关闭阶段。资源重载结束和随后四次冷启动结束时，整进程使用堆分别为 6.08 和 5.80 GB。整包世界、纹理和渲染缓存同时参与这些读数，长时间留存需要独立采样。

隔离测试核对了全部 13 个直接注册目标、GT 仓室的编程电路、Jade 坐标行、Shift 工程详情、离线、结构解体、GUI Scale 2 和 3。资源重载后重新出现完整冷启动。阶段包在主世界与下界往返时采集到各自的正确目标，切换初期使用等待遥测状态。后续传送复测的回程射线偏到磁控部件，未取得有效 HUD 样本。模拟网络场景在客户端丢弃指定回包，实际远程链路与长期驻留仍见 `TODO.md`。发布包原始采集和截图保存在 `tmp/star-lifter-jade-boot/release`，跨维度阶段包的采集在 `tmp/star-lifter-jade-boot/verified`。
