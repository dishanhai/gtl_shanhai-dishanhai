package com.shanhai.common.recipe.editor;

/**
 * JEI 热生效的<b>判据核心</b>（纯函数，不碰任何 JEI/MC 类）。
 *
 * <h2>1. 为什么把"贴补丁"这件事抽成算术</h2>
 * 红线禁止开客户端 ⇒ JEI 那一步**本机验不了**。但"同一 id 在界面上还剩几条"这件事
 * <b>是可以用纯算术判的</b>：
 * <pre>
 *   界面上能看到几条 = 这次拿到手的【原件条数】 − 丢掉的条数 + 我们补进去的条数
 *                    = matched                 − hidden      + added
 * </pre>
 * 于是 {@link #of} 就是那条判据，自检里跑四组数（三组正对照 ＋ 一组<b>负对照</b>）。
 *
 * <h2>🔴 2. 2026-10-05 订正：旧口径把 added 和 hidden 混成了一个计数器</h2>
 * 旧实现里"用我们造的那一份替换掉原件"这一支只 {{@code added++}}、<b>不加 hidden</b>，
 * 于是同一拍在日志上打成
 * <pre>
 *   [SHANHAI-JEIPATCH] jei_sync hidden=0 added=1 … visible_expected=1 (纯核算出来的：matching=1 hide=1 add=1)
 * </pre>
 * —— <b>"hidden=0" 与紧跟着的 "hide=1" 自相矛盾</b>（这一行是用户实例
 * {@code logs\latest.log} 11:49:17 的原文）。真相不是"hide 没执行"：那一条原件确实被替换掉了，
 * 只是<b>它没被记进 hidden</b>。⇒ 计数口径改成三个独立计数器（matched / hidden / added），
 * 判据行里三个数都打出来，且 {@code matched - hidden + added} 必须等于期望值。
 * <p>负对照 = 把旧口径那个读数喂进同一条判据（{@code of(1, 0, 1)}），它<b>必须算出 2</b>；
 * 算不出 2 就说明这条判据没有判别力（假绿）。
 */
public final class ShanhaiJeiSyncPlan {

    /**
     * 一次同步的处置读数。
     *
     * @param matched       这次拿到手的、id 命中补丁表的<b>原件</b>条数
     * @param hidden        丢掉的条数（重复的 + 删除的 + 被我们替换掉的）
     * @param added         我们补进去的条数
     * @param visibleAfter  判据：这一拍之后界面上这一条配方能看到几条
     */
    public record Plan(int matched, int hidden, int added, int visibleAfter) {

        /** 判据：改过的那条应当只剩一条。 */
        public boolean unique() {
            return visibleAfter == 1;
        }

        /** 判据：删掉的那条应当一条都不剩。 */
        public boolean gone() {
            return visibleAfter == 0;
        }
    }

    /**
     * 🔴 <b>2026-10-05（闪退修复）：贴完补丁之后列表变空了 ⇒ 必须把【原件】还回去。</b>
     *
     * <h2>它修的是哪一次闪退（原始堆栈）</h2>
     * <pre>
     *   Description: mouseReleased event handler
     *   java.lang.IllegalArgumentException: recipes must not be empty.
     *     at mezz.jei.common.util.ErrorUtil.checkNotEmpty(ErrorUtil.java:113)
     *     at mezz.jei.gui.recipes.RecipesGui.showRecipes(RecipesGui.java:496)
     *        {pl:mixin:APP:shanhai.mixin.json:ShanhaiJeiBookmarkMixin}   ← 我们的 mixin
     *     at mezz.jei.gui.overlay.elements.RecipeBookmarkElement.show(RecipeBookmarkElement.java:118)
     * </pre>
     * <b>根因</b>：用户点的是收藏夹里<b>一条已经被删掉的配方</b>。收藏夹那条通道传进来的是
     * {@code List.of(bookmark.getRecipe())}（<b>恰好 1 条</b>），而我们的补丁表里这条是
     * {@code action=removed} ⇒ {@code patched()} 把它丢掉了 ⇒ 返回<b>空列表</b>
     * ⇒ JEI 的 {@code checkNotEmpty} 抛异常 ⇒ <b>游戏闪退</b>。
     *
     * <h2>判据（这一条必须能被机器判，不能只靠"进游戏点点看"）</h2>
     * <pre>
     *   inSize=1 outSize=0  ⇒ true   （正是闪退那一拍）
     *   inSize=1 outSize=1  ⇒ false  （正常：改过的那条换成了我们造的新对象）
     *   inSize=0 outSize=0  ⇒ false  （本来就没东西，JEI 自己的行为，不归我们管）
     * </pre>
     * ⚠️ <b>语义取舍（写清楚）</b>：还回原件 = 收藏夹里<b>还会显示那条已经被删掉的配方</b>。
     * 这是刻意的：<b>"显示一条过期数据"远好于"整局游戏崩掉"</b>。
     * 同时会打一条可 grep 的 WARN ＋ 一次客户端提示（见 {@code ShanhaiJeiRecipePatches}），
     * 所以它不是静默的。
     */
    public static boolean mustKeepOriginal(int inSize, int outSize) {
        return inSize > 0 && outSize == 0;
    }

    private ShanhaiJeiSyncPlan() {}

    /** 由三个独立计数器直接算出可见条数（唯一的公式来源）。 */
    public static Plan of(int matched, int hidden, int added) {
        final int m = Math.max(0, matched);
        final int h = Math.max(0, hidden);
        final int a = Math.max(0, added);
        return new Plan(m, h, a, m - h + a);
    }

    /**
     * 自检：三组正对照 ＋ 一组<b>负对照</b>，返回逐条原始读数（调用方直接打日志）。
     *
     * <p>每一行都写成 {@code JEI_PLAN …}；正对照判据 {@code PASS=true}，
     * 负对照那行必须是 {@code visible=2}（＝这条判据有判别力）。
     */
    public static String[] selfcheck() {
        final Plan firstAdd = of(1, 1, 1);
        final Plan secondEdit = of(1, 1, 1);
        final Plan batched = of(2, 2, 1);
        final Plan removed = of(1, 1, 0);
        final Plan negative = of(1, 0, 1);
        return new String[]{
                "JEI_PLAN first_add matched=1 hidden=1 added=1 -> visible=" + firstAdd.visibleAfter()
                        + " expected=1 PASS=" + firstAdd.unique(),
                "JEI_PLAN second_edit_same_id matched=1 hidden=1 added=1 -> visible=" + secondEdit.visibleAfter()
                        + " expected=1 PASS=" + secondEdit.unique(),
                "JEI_PLAN gtceu_batches_same_id_twice matched=2 hidden=2 added=1 -> visible="
                        + batched.visibleAfter() + " expected=1 PASS=" + batched.unique()
                        + " (GTCEu 分批注册 ⇒ 同 id 的两条都要丢，只补一份)",
                "JEI_PLAN removed matched=1 hidden=1 added=0 -> visible=" + removed.visibleAfter()
                        + " expected=0 PASS=" + removed.gone(),
                "JEI_PLAN NEGATIVE_CONTROL matched=1 hidden=0 added=1 -> visible=" + negative.visibleAfter()
                        + " expected=2 PASS=" + (negative.visibleAfter() == 2)
                        + " (这就是 11:49:17 那行 hidden=0 added=1 的老读数喂进新判据的结果："
                        + "它算出 2 而不是 1 ⇒ 老口径的「自洽」是假的，这条判据有判别力)",
                "JEI_PLAN bookmark_deleted_single in=1 out=0 -> keep_original="
                        + mustKeepOriginal(1, 0) + " expected=true PASS=" + mustKeepOriginal(1, 0)
                        + " (这一拍就是 17:22:21 那次闪退：收藏夹里那条已被删除 ⇒ 补丁丢光 ⇒ "
                        + "showRecipes 收到空列表 ⇒ JEI 的 checkNotEmpty 抛异常)",
                "JEI_PLAN bookmark_deleted_NEGATIVE_CONTROL in=1 out=1 -> keep_original="
                        + mustKeepOriginal(1, 1) + " expected=false PASS=" + (!mustKeepOriginal(1, 1))
                        + " (正常替换那一拍不许被这条兜底改道，否则收藏夹永远刷新不了)",
                "JEI_PLAN bookmark_empty_input_control in=0 out=0 -> keep_original="
                        + mustKeepOriginal(0, 0) + " expected=false PASS=" + (!mustKeepOriginal(0, 0))};
    }
}
