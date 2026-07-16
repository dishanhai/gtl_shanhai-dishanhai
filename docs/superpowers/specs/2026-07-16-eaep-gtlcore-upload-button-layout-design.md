# EAEP 与 GTLCore 样板上传按钮冲突修复设计

## 目标

解决 AE2 样板编码终端中 GTLCore 快速上传回收按钮遮挡 EAEP 上传样板按钮的问题，同时保留两个模组的完整功能。

## 根因

EAEP 将 `eap$uploadBtn` 定位在 `encodePattern` 左侧。GTLCore 的 `QuickUploadUndoButton` 同样位于 `encodePattern` 左侧，并在每次 `updateBeforeRender()` 时重算位置。GTLCore 还使用独立的 `gtlcore$quickUploadUndoHitX/Y/Width/Height` 拦截点击，因此只移动可见按钮会在旧位置留下不可见热区，继续阻断 EAEP。

## 已确认行为

1. EAEP 上传按钮位置不变。
2. GTLCore 快速上传按钮位置不变。
3. 仅当 GTLCore 回收按钮与 EAEP 上传按钮实际重叠时，将回收按钮移动到 GTLCore 快速上传按钮正下方，间隔 2 像素。
4. 可见按钮位置和 GTLCore 自定义回收点击热区必须同步更新。
5. 未安装 EAEP、按钮缺失或按钮没有重叠时不改变 GTLCore 原布局。

## 实现

新增仅客户端 Mixin，目标为 `AEBaseScreen`，优先级低于 GTLCore 默认 Mixin。在 `updateBeforeRender()` 尾部运行，确保 GTLCore 已完成本帧坐标刷新。

通过 Screen children 查找三个实际按钮：EAEP 上传按钮、GTLCore 快速上传按钮、GTLCore 回收按钮。仅在三者齐全且矩形重叠时计算新坐标。

GTLCore 的点击热区字段是其 Mixin 动态加入 `AEBaseScreen` 的私有字段，山海 Mixin 使用一次性缓存反射获取 `gtlcore$quickUploadUndoHitX` 和 `gtlcore$quickUploadUndoHitY`。只有两个字段均可写时才移动按钮；反射失败则保持原布局，避免出现按钮与点击位置分离。

## 验证

1. 源码契约测试锁定客户端 Mixin 注册、低优先级、重叠检测、下方定位和点击热区同步。
2. 完整 `clean build` 必须通过。
3. 游戏内确认 EAEP 上传按钮可点击并正常打开供应器选择界面。
4. 确认 GTLCore 快速上传与回收按钮仍可点击，回收按钮 tooltip 跟随新位置。
