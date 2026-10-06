---
enabled: true
version: 3.0.4
title: gt_shanhai 3.0.4 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
---

# gt_shanhai 3.0.4

发布日期：`2026 年 9 月 6 日`

## 商店与界面

- 商店详情栏统一使用固定宽度，移除响应式缩放导致的按钮和详情区错位。
- 优化商品卡片尺寸、拖拽排序与布局计算，提升不同窗口尺寸下的稳定性。
- 新增商品悬停快捷键：在可购买物品的工具提示中按住配置按键，即可打开对应的山海商店页面。
- 商店目录增加商品物品 ID 反向索引，按目录顺序查找可见商品。
- 任务跳转与悬停快捷键统一使用商店页面打开入口，保留服务端校验与最新目录。

## JEI 与 GTLCore 兼容

- 更新 JEI 依赖，并适配 GTLCore 新版本接口。
- 增强 JEI 搜索前缀，支持物品 ID 与标签搜索。
- 增加 JEI 运行时保护，避免 JEI 异步启动异常时继续绘制覆盖层。
- 修复 JEI 流体工具提示注入，提升新版本 API 下的兼容性。
- 优化 GTLCore 多方块结构预览注册，单个结构预览失败时跳过该结构，不影响其余预览。
- 使用异步流程建立多方块预览，避免阻塞 JEI 注册线程并降低渲染线程死锁风险。

## 样板、倍率与虚拟物品

- 改进虚拟样板配方查找，缓存未命中时回退到当前配方类型的实时检索。
- 新增通用宿主输出倍率解析，支持配方类型样板总成同步全局倍率。
- 修复量子 CPU 虚拟模式下的处理逻辑。
- 新增虚拟物品提供器渲染器，并在工具提示中显示绑定物品数量。
- 增强终极终端替换选择器，记住上次替换配置并改善选择器布局。

## 结构与协议

- 改进 STR 结构与世线裂解枢纽的方块解析和图案创建。
- 结构空白槽位支持任意方块，减少结构预览和建造时的限制。
- 更新项目协议标注与 Minecraft、Forge 版本信息。
