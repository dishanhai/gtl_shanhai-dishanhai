---
enabled: true
version: 4.0.1
title: gt_shanhai 4.0.1 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
---

# gt_shanhai 4.0.1

发布日期：`2026 年 10 月 1 日`

## 配置与样板编码

- 版本号更新至 `4.0.1`。
- `forceWrapOmittedNonConsumables` 默认值改为 `true`。
- 编码器反查到缺失的不消耗输入时，默认自动补回虚拟物品提供器或流体标记。
- 配置界面与配置说明同步更新，仍可手动关闭该行为。

## 多方块预览与 JEI

- 重构 GTLCore 多方块结构预览注册流程，简化异步处理并降低启动阶段的复杂度。
- 保留单个多方块预览失败时跳过该条目的容错行为，避免影响整个 JEI 多方块分类。
- 将 `PatternPreviewSearchGuard` 移动到 GTLCore 集成包。
- 将 `MultiblockPreviewRegistrationHelper` 移动到 JEI 集成包，明确兼容代码的模块边界。
- 更新多方块预览 Mixin 的处理方式，适配当前 GTLCore 实例方法调用。

## JEI 快捷操作与兼容

- 适配新版 JEI 客户端内部 API，修复配方焦点界面的快捷复制与创造物品操作。
- 保留无限单元格等快捷操作，并改用当前 JEI 运行时配置获取客户端状态。
- 更新相关测试与配置文档，减少版本升级后的静默失效。

## 其他

- 更新仓室提示文本，补充无线样板管理终端和原初引擎模块联动说明。
