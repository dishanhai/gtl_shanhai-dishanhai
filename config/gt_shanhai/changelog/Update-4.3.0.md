---
enabled: true
version: 4.3.0
title: gt_shanhai 4.3.0 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.3.0

发布日期：`2026 年 10 月 8 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`

## 配方修改器

- 新增`配方添加`。从当前类型另建一条配方，不改、不删已有配方。
- 创建页左侧可直接点选`配方类型`。
- `猜测`按「类型 + 第一个输入 + 第一个产物」填入 `gt_shanhai:` 配方 id。
- `EU/t` 旁增加电压档。`ULV` 到 `MAX`，以及 `MAX-2` 到 `MAX+16`。`MAX` 写入 `2147483647`，`MAX+16` 写入长整型上限。
- 新建配方可以直接`编码样板`、导出 JSON，不再要求原始快照里已经有这条配方。
- 草稿的保存和恢复覆盖创建模式。
- JEI 侧可以把编辑后的配方编码进`无线终端`样板。
- 物品、流体选择改为模组自己的`多选界面`，不再走 FTB Library 的选择屏。

## 配方加载

- 重复注册的配方会给出`重复警告`。
- JSON 加载失败时，向玩家发送具体失败原因。
- `可选配方类型`界面显示已选数量，补上悬停提示，并整理滚动列表。

## 万象原核与原初模块

- 新增物品`万象原核`。放 1 个即可同时满足线圈温度、恒星容器、超净间、重力、研究数据和维度。
- 原初引擎模块额外挂载槽由 3 个扩到 `6` 个。旧存档会补齐槽位。
- 重做挂载页：槽位标签、实时状态、挂载规则和能力说明。
- 温度改为紧凑显示，例如 `1万K`、`1.5万K`。过长的维度名会截断。
- 质能核心界面宽度随新布局调整。

## 原初终焉引擎

- 工作时，约束光束外侧增加往复运动的星球，并会躲开当前最贴近的那一圈环。
- 同一位置增加一颗不带光束的`微缩中子星`，与宿主星体画在同一帧里。
- 停机后这些附加效果不再空转。

## 山海银行

- 存款和贷款改为`本金`与`利息`分开记账。线性单利不再滚进本金。
- 已经折进旧档本金的利息留在本金里，之后新产生的利息进利息桶。
- 不满 1 小时的零头留到下次结算。小额本金算出 0 利息时，计息起点不往前跳。
- 取出时先取利息，再取本金。还款时先冲利息，再冲本金。
- 借款上限按`本金 + 利息`计算，达到上限后不能再借。
- 会员中心银行页显示本金、利息、合计、可借额度和服务器利率。
- 新增`全部存入`、`全部取出`、`全部还款`。
- 购买会员失败时，分开提示余额不足、已拥有该档或更高档、无效档位。
- 银行查询指令同步显示上述分项。

## 样板与自动合成

- 多张样板抢同一产物时，整单必须绕回当前产物才能补齐的`回环样板`会让到队尾。库存够跑完整单的短样板保持原位。
- 证明不了回环时不改 AE 的选择。深度或访问预算用尽时也保持原顺序。
- 自动合成确认界面会写明：`已跳过回环样板，缺失来自下一张可运行样板`。
- 有样板、又不缺这张样板本身时，提示缺口在`上游材料`。
- `gtceu:matter_exoticizer` 样板会折叠成 `gtceu:matter_exotic`，星律槽可以正常扣料。
- 虚拟在场镜像进催化剂仓时，不再把普通样板总成整张判成失败。

## 配方数据

- 宇宙模拟新增：`hxsp`、强化、奇迹宇宙、时间逆转协议强化。
- 扭曲新增：黑洞视界剥离两张、宇宙模拟 hxsp、永恒巅峰、铂族一站式处理、铂族矿物三张、生物模拟实验室无限。
- 移除文件名带尾部下划线的重复`逻辑算力仓`配方。正本配方仍在。
- 移除恒星点燃里「天体秘密 → 天体秘密等离子体」。
- 电爆压缩`爆弹`产物由 `disksavior:quantum_chromodynamic_charge_super` 改为 `dishanhai:quantum_chromodynamic_charge_super`。
- 新增组装模块配方`宇宙探针 Mk1`，默认关闭，需在配方开关里打开。
