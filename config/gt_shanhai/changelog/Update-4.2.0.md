---
enabled: true
version: 4.2.0
title: gt_shanhai 4.2.0 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.2.0

发布日期：`2026 年 10 月 7 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`、**dgyjh02804**——`重置版作者`、**ReallyChooseC**——`issues #15` 反馈者并提出改进建议

## 商店批量管理

- 新增商品`批量操作`功能。
- 支持使用 `Alt + 左键` 选择多个商品卡片。
- 支持批量设置`交易方向`、批量隐藏与批量删除。
- 批量删除保留`二次确认`，降低误操作风险。
- 空白区域支持 `Alt + 右键` 清空批量名单。
- 批量管理使用`稳定商品 ID`，避免排序或重载后选错商品。
- `阶段解锁前置`新增「AE 下单补齐阶段前置」。
- `商品固定前置`新增「AE 下单补齐商品前置」。

## 商店限购与货币中心

- 新增`存档次数`与`服务端次数`分离的双层限购机制。
- 不同存档分别保存购买次数，新存档使用`服务端基准值`。
- 货币中心支持 AE 网络余额直接参与`自动结算`。
- 货币中心同时显示`钱包余额`与 `AE 网络余额`。
- 优化货币快速出售与 AE 补足流程。
- 添加商店自动补货功能支持提交和阶段前置。
- 使用稳定ID作为商品购买统计键值。

## JEI 收藏与样板

- # 新增 JEI 配方侧边收藏能力。
- 支持按 AE 网络库存不足、缺少样板流程等条件筛选收藏物品。
- 增加虚拟流体催化剂支持，优化虚拟目标处理。
- 自动合成确认界面增加 UUID 标识，减少不同任务之间的混淆。

## 工具

- 修复`终极终端替换配置`问题。
- 更新网络协议版本至 v6。
- 添加`配方修改器开发者工具`物品——接入重置版配方修改器。

## 配方与机器及仓室

# 原初系列
- 修复原始终焉引擎`异步检查模式`问题；
- 修复原始终焉引擎模块`主机位置检查`；
- 优化能量仓供电与并行功率预算计算。
# 配方与星律
- 优化配方类型注册、配方查找与运行时缓存路径；
- 改进星律样板执行相关的`输入匹配与虚拟供料`逻辑。
# 星空库存总成
- 添加新仓室 `星空库存总成` [`ME Stellar Stock`] ；
· 具体功能见 [AE 星空库存总成](guides\gt_shanhai\guide\ae\me_stellar_stock_part_machine.md)；
· 或查看游戏内「AE 星空库存总成」的 `guideme` 文档（对物品jei按G进入）。


## kubejs 修改

- 添加三个去循环量子操纵者配方。
- 为gtlcore世界碎片系列添加tag：`#dishanhai:world_fragments`——你可以使用此标签快速拉取世界碎片。

## 虚拟物品系统
- 添加虚拟物品支持到`AE2最大快速请求`。
- 为存在性检查添加了专门的请求入口跟踪机制。
- 实现了当检测到虚拟物品时回退到原生AE2请求的方法。
- 在`MaxFastExecutor`中添加了针对`虚拟物品的特殊处理逻辑`。
- 防止虚拟物品输入参与最大快速聚合和循环候选图构建。

## AE2 快速请求

- 为 `AE2 最大快速请求`增加虚拟物品系统支持。
- 优化 CraftingTreeNode 与 MaxFastExecutor 的 Mixin 处理逻辑。
- 移除不必要的请求入口和高频诊断日志，降低额外开销。

## 更新公告系统

- 新增从 `config/gt_shanhai/changelog/` 读取 Markdown 更新公告。
- 添加公告链接支持和改进历史更新功能。
- 按文件名中的 SemVer 自动选择最高版本公告。
- 支持历史版本列表、标题、列表、粗体、斜体与行内代码显示。

## 重置版合并内容

- 移植添加`全息投影渲染器UI`——*手持创始现实修改模块即可唤出全息UI*。
- 移植添加`配方修改器`——*全息UI中添加配方修改器，并支持实时配方修改*。
- 移植`中子星渲染器`——*支持多种显示模式，支持始终渲染工作状态...*。
- 移植新增`原始物质解构`配方类型以及配方解析器。
- 移植`原初系列机器挂载槽扩展`——*支持原初系列机器挂载槽扩展*：
  -- 维护仓：超净间/重力，放1个即可——维护仓条件模拟。
  -- 世界碎片：对应维度，放1个即可——世界环境模拟。
  -- 创造模式数据访问仓：研究要求，放1个即可——全数据模拟。
  -- 线圈/恒星热力容器：必须放满64个——模拟线圈热量及恒星热力容器。
  添加 `原初模块旧式无限制模式` 配置默认 `flase`——开启时除维护仓外不检查上述额外挂载限制(数据除外) 。
- 移植物品`创造维度碎片`——用于模拟创造维度环境。

## 配方修改器

- 实现配方重构建服务，支持运行时配方类型覆盖。
- 添加配方编辑器基础类和继承逻辑，支持tickOutputs和条件验证。
- 增加IO表JSON解析功能，同步客户端和服务端配方快照。
- 优化配方编辑器动画效果，使用平滑步进函数改善用户体验。
- 添加配方重构建报告和同步机制，确保配方修改正确传播。
- 支持配方覆盖存储和冲突检测，提供配方指纹验证功能。
- 添加特殊配方需求的增加和删除能力
- 添加配方json化导出能力
- 添加高级筛选能力和物品反向搜索功能

## TODO

- 合并重置版内容
