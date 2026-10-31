---
enabled: true
version: 4.8.0
title: gt_shanhai 4.8.0 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)] - 【腾讯文档】gt_shanhai 模组问题与建议反馈表
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.8.0

发布日期：`2026 年 10 月 31 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`

接在 4.7.0 后面。归墟视界枢核的光柱环改成单遍外缘吸积流，黑洞加了表面波纹与星云星尘，AR 模组列表超过一页会自动轮播。新增彭罗斯遏制场模组与超维度链接模组，功率预算归为一套。原始终焉引擎可以弱引用归墟枢核，高阶原初模块改成必须接入归墟才能跑，并加了黑名单开关，同一网络里的万象核心也会互斥。商店有了阶段贷款额度、顶栏横向滚动、购物车 AE 补料下单和无线电力商品。星律总成的卡死报告独立成页，支持实时刷新。新增 AE 蒸汽覆盖板与蒸汽生成多方块，文字特效追加四款山海流动色板。

## 联机兼容

- 网络协议升到 12（4.7.0 为 9）。服务端与客户端必须同为 4.8.0，版本不一致时进不了服务器。

## 归墟视界枢核与黑洞特效

- 光柱环重做为单遍外缘吸积流，顶点只留 Position 与 UV，光照从 CPU 挪进 `FlowSink` 与吸积流着色器，绘制更快，外缘也不再生硬。
- 黑洞加上表面波纹与降级波峰，重调了吸积盘噪声和光子环算法。外缘硬边、深度打架的问题已修，光子环与吸积盘的前后层次按光线走法重排。
- 外围补上星空、轨道星尘和流星。背景星空常亮，吸入的星辰与喷流跟黑洞开关走；星云用独立着色器并单独配了开关，星辰场的固定种子数组做了缓存。
- 新增调试指令 `/shanhai blackhole`，可调窗口半径（`window`）、径向拖影强度（`streak`）、洞内诊断隔离（`insidedebug`）与深度门开关（`depthgate`）。视界半径改为反算，屏幕上的黑盘格数与机器字段严丝合缝。
- 黑洞渲染前会核对来源世界与区块是否有效，超出视距或结构拆散后自动清掉残留。
- 归墟 AR 模组列表超过 7 个时每 10 秒自动翻页，末页只画剩下的几行。正反两面的字拆到各自平面，不再叠出荧光重影。并行数走 `long` 同步，超上限写「无限」；服务端只发注册名 ID，由客户端本地化，不必跨维度强行加载世界。

## 彭罗斯遏制场与超维度链接

- 新增「彭罗斯遏制场模组」与配套多方块结构，带遏制场增益状态机。时空流体实扣不够就算断供，ME 仓走实扣校验，失稳断开或区块卸载残留一律坍缩。主机统一生成倍率快照，主机与各模块配方同步吃满遏制场增益与锁定提示。
- 新增「归墟超维度链接模组」：手持右击绑定原始终焉引擎后，归墟枢核就能跨维度接管那台引擎的模组位（生效期间归墟模组位上限 +16），解绑或引擎拆掉时原样归还。目标坐标按未加载、缺失、就绪三态解析，不强拉区块常驻。
- 并行与提速改用同一份 `BigInteger` 功率预算（`GuixuPowerBudget`），按能量与并行上限均分份额；能源仓功率读写收口到 `GuixuHatchPower`。

## 原初系列机器与模块规则

- 原始终焉引擎可向归墟视界枢核建立弱引用链接，代领产出倍率与遏制场增益。
- 高阶原初模块必须接入归墟视界枢核，没接上会停机报错。
- 配置新增 `primordialEngineAllowAnyModule`（放开限制，允许引擎直接装任意原初模块）与 `primordialEngineBlockedModuleItemIds`（引擎禁装模块名单，默认封禁 10 种高阶模块），两项都支持热重载。
- 万象增殖核心同一网络只认一台，多装的自动休眠并弹出警告。
- 新增外部原初模块总开关：打开后只禁掉跑外部模组配方的原初模块，山海自有配方模块与主机本体照常保留。
- 跨配方并行改由配方逻辑按需挑出本轮要搜的配方类型，到最后一批才记下实际跑通的并行数；AR 面板只报实绩，不再挂理论上限。
- 配置新增原初铸币工厂禁用开关，铸币权限下沉到模块自身检查。

## 商店系统

- 最大欠款从固定死数改成按阶段算贷款总额度，银会界面与配置提示一并改掉。
- 顶栏左侧按钮排不下时可用滚轮左右滚，两端亮 `«` `»` 提示还有内容；编辑模式下的「新增商品」不再挤占标题和右侧操作区。
- 购物车新增「AE 下单补齐缺口」：把车里各商品缺的原料合在一处，交给绑定的 AE 网络算合成方案，算好弹窗确认即可下单。
- 商品支持卖无线电力：编辑器里可配电力额度并显示电压档位，买下直充玩家绑定的无线电网，周期额度也会跟着同步。
- 修复流体商品出售时的库存核对错误，现在卖流体能从 AE 网络正常扣账。
- 按钮点击波纹从 240 毫秒拉长到 320 毫秒。

## 星律样板与卡死诊断

- 星律样板总成记录每次分配的实际并行与容量，告警系统直接读取实时数据。
- 终端报告界面单开一页「星律卡死报告」（`STELLAR_STUCK`）：支持实时刷新，刷新时不收起已展开的条目、不跳滚动条；可按状态筛选、复制全文或直接跳去对应记录。
- 同一坐标反复报卡死会先攒成一批再定时发出，不再刷满聊天栏。
- 样板缓冲远程界面的通配符页加上聚焦模式、独立分页与彩虹焦点框；样板详情行可直接点进对应的卡死报告。
- 卡死原因补上铸币权限锁定（`COIN_MINT_LOCKED`）等状态，报错说明写得更直白。

## AE 蒸汽系统

- 新增「山海 AE 蒸汽覆盖板」：贴在机器表面、绑好 AE 节点后，直接抽网络里的蒸汽供能，按精确比例折算 EU 与蒸汽消耗。
- 新增「AE 蒸汽绑定器」：按住 Shift 左键就能拆下覆盖板，原先绑的节点和预存蒸汽都留着。
- 新增「蒸汽生成多方块机器」与纯流体配方类型 `STEAM_GENERATION`，可装进原初物质模块并行产蒸汽。
- GuideME 补上《AE 蒸汽供应》与《蒸汽生成机》两篇指南。

## 视觉与文字特效

- 新增山海朝霞、山海青岚、山海星河、山海琉彩四款流动色板，支持颜色别名。
- 重写全元素流动色板（`all`）：所有色板等时长轮播，每轮长度随机、轮内洗牌不重样，并加了时间跳跃和反向流动效果。
- 整理混合 FCS 插入的分段绘制路径，长段富文本渲染省了不少开销。

## 配方与数值调整

- 归墟模式下物质解压、能量抽取、范式简化三类配方的最大槽位从 `(9, 9, 4, 4)` 扩到 `(9, 144, 4, 32)`。
- 组装机新增粒子束引导管道外壳配方：UXV 机械外壳 ×32 + 中子素双板 ×4 + 世界线发散核心 ×1 → 管道外壳 ×32。
- 修好无线电路板重组配方的启用条件 ID、长整数产物写法与组装单元消耗。
- 补齐太空采矿模块配方的电压档位与耗能；新增圆石转钻石、废料盒物质球、蓝超巨星光子分离等配方。

## 内部调整

- 新增特效与渲染类：`BlackHoleSurfaceWaves`、`GuixuAccretionFlow`、`GuixuNebulaField`、`HostFxBeamRing`、`HostFxGlassArModel`。
- 新增机器与逻辑类：`GuixuPenroseContainmentModule`、`GuixuHyperdimensionalLinkModule`、`GuixuPowerBudget`、`GuixuHatchPower`、`ShanhaiAeSteamCover`、`ShanhaiSteamGeneratorMachine`。
- 网络协议升至 12，调整 `ShopActionPacket`、`ShopAutoCraftRequestPacket`、`ShanhaiTerminalReportPacket` 并补齐配套新包。
