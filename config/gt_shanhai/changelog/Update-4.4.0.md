---
enabled: true
version: 4.4.0
title: gt_shanhai 4.4.0 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.4.0

发布日期：`2026 年 10 月 TBD 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`

## 配置界面

- 模组列表里的`配置`补上原先只能手改 `gt_shanhai-common.toml` 的项目。
- `配方类型样板总成`增加`卡死告警延迟`和`虚拟供料单次并行上限`。
- 新增分类：`高级样板包装箱`、`样板总成工具箱`、`商店银行`、`缓存与诊断`、`开发`。
- `商店`增加`奖励抽取次数上限`和`出售回收比例`。
- `机器行为`增加`AE 库存强制重扫间隔`。只在增量缓存开启时生效。
- `商店银行`可以改存款基点、贷款基点、最大欠款和贷款期限。
- `缓存与诊断`可以开关运行期配方缓存统计，以及 KJS 配方库磁盘缓存。后者默认关闭，改完要重启。
- `开发`可以开关`开发模式`。关闭时配方修改器仍导出到游戏目录 `kubejs/data/Exported_Recipe/`。打开后写到这台开发机的模组源码 recipes 目录。其他机器没有这个目录，导出会失败。
- 星律样板总成点`恢复默认`时，每行槽位、每页行数、最大页数都回到 `10`。已放置的总成在下次区块加载时采用新配置。调小会裁掉高位槽，里面的样板掉在机器位置，内部缓冲料留不住。

## 原初渊薮精炼塔

- 增加配方类型`太素衍化`。
- 物品说明由`七源归一`改为`八源归一`。类型列表末尾加上太素衍化。

## 配方修改器

- JEI 配方页增加`编辑此配方`。背包里有配方修改器、并且权限等级达到 2 时才会出现。缺工具或缺权限时，聊天栏会说明原因。
- 修改器快照里还没有这条配方时，会先从当前配方表抓取再打开。
- 输入格新放入的编程电路，概率仍是默认时改为`不消耗`。悬停会写出电路号等细节。
- 右键改为取消选取。中键打开选取器。

## 配方数据

- 新增物质模块铸造`维度世线碎片`，默认关闭，需在配方开关里打开。
- 新增超时空装配线`创造维度碎片`，默认关闭。
- 新增超时空装配线`万象原核`，默认关闭。
