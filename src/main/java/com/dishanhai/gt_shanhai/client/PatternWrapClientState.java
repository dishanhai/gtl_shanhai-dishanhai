package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.common.item.PatternEncodeOverride;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 样板终端"包裹控制"客户端本地状态——纯 UI 展示用（三态按钮图标 + 标记槽位高亮框），
 * 不依赖服务端回传同步。点击时本地立即更新（乐观），同时把动作发给服务端权威状态
 * （PatternWrapControlMenuMixin），两边极少数情况下可能短暂不一致，但只影响视觉，
 * 重开界面即可复位，不影响 encode 时实际读取的服务端权威判断。
 */
public final class PatternWrapClientState {

    public static PatternEncodeOverride.WrapMode mode = PatternEncodeOverride.WrapMode.AUTO;
    public static final Set<Integer> markedSlots = new LinkedHashSet<>();

    public static void reset() {
        mode = PatternEncodeOverride.WrapMode.AUTO;
        markedSlots.clear();
    }

    private PatternWrapClientState() {}
}
