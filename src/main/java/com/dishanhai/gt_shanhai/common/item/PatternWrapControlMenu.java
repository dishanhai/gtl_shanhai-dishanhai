package com.dishanhai.gt_shanhai.common.item;

/**
 * 样板终端"包裹控制"客户端动作入口，由 PatternWrapControlMenuMixin 实现。
 * 单独抽成接口是为了让客户端 Screen 侧 mixin 能直接调用，不用关心 Mixin 内部实现细节
 * （沿用 GTLCore 自己 PatterEncodingTermMenuModify 的同款写法）。
 */
public interface PatternWrapControlMenu {
    void gtShanhai$cycleWrapMode();

    void gtShanhai$toggleMark(Integer slot);
}
