package com.dishanhai.gt_shanhai.client.gui.shop;

import java.util.Objects;

/** 山海：商店确认界面的一次性选择；画面移除不应触发正常返回流程。 */
final class ShopConfirmationActions {
    private final Runnable confirm;
    private final Runnable cancel;
    private final Runnable abandon;
    private boolean finished;

    ShopConfirmationActions(Runnable confirm, Runnable cancel, Runnable abandon) {
        this.confirm = Objects.requireNonNull(confirm);
        this.cancel = Objects.requireNonNull(cancel);
        this.abandon = Objects.requireNonNull(abandon);
    }

    boolean finished() { return finished; }
    boolean confirm(boolean enabled) { return enabled && finish(confirm); }
    boolean cancel() { return finish(cancel); }
    boolean abandon() { return finish(abandon); }

    private boolean finish(Runnable action) {
        if (finished) return false;
        // setScreen 会同步调用 removed；必须先封住决策，再执行回调。
        finished = true;
        action.run();
        return true;
    }
}
