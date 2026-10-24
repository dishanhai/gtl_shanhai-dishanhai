package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;

/**
 * <b>⑦ 库存输入页 / ⑧ 标签过滤输入页的版面与槽号唯一常量处</b>（阶段六新增）。
 *
 * <h2>一、为什么单独一个类</h2>
 * 这两个数字（每页 8 列 × 2 行、共 10 页 = 160 格）既要在机器里用，也要能被<b>无头探针</b>
 * 直接验证（{@code temp\stellar-p1\probe6\StockLayoutProbe.java}）—— 把纯算术放在一个不引用
 * 任何 Minecraft 类的类里，探针就能在不启动游戏的情况下跑正/负对照。
 *
 * <h2>二、🔴 这里<b>不是</b>样板槽号空间</h2>
 * 槽号空间（普通 0..1079 / 母槽 1080..1129 / 展开 1130..1329）的唯一常量处仍然是
 * {@link ShanhaiWildcardLayout}。本类只描述「库存输入页里第几页第几格 = 第几个配置槽」，
 * <b>与 {@code getInternalSlot(int)} 的槽号无关</b>，两者不要混。
 *
 * <h2>三、坑（阶段一~五都踩过的那一类）</h2>
 * gtlcore 的 {@code PaginationUIManager} 有 7 参 / 8 参两个重载，<b>第 3 个参数含义相反</b>
 * （7 参 = 最大样板数；8 参 = 页数）。本类服务的组件是 gtlcore 的 {@code AEDualConfigWidget}，
 * 它的 8 参构造器最后一个参数是 <b>rows（行数）</b>，`pageSize = rows * 8`，
 * `MAX_PAGE = max(1, itemList.getSize() / pageSize)`（{@code javap -c} 偏移 69–135 实证）。
 * ⇒ 传 2（行），不是传 10（页）；传 10 会得到一页 80 格。
 */
public final class ShanhaiStockConfigLayout {

    /** 每页列数（{@code AEDualConfigWidget} 内部写死 8 列 × 18 px）。 */
    public static final int COLUMNS = 8;

    /** 每页行数（传给 {@code AEDualConfigWidget} 的第 8 个参数）。 */
    public static final int ROWS_PER_PAGE = 2;

    /** 每页配置格数 = 8 × 2 = 16。 */
    public static final int SLOTS_PER_PAGE = COLUMNS * ROWS_PER_PAGE;

    /** 页数 = 10。 */
    public static final int PAGE_COUNT = 10;

    /** 配置槽总数 = 8 × 2 × 10 = <b>160</b>（⑦ / ⑧ 各自一份）。 */
    public static final int CONFIG_SIZE = SLOTS_PER_PAGE * PAGE_COUNT;

    private ShanhaiStockConfigLayout() {}

    /** 第 {@code page}（1 基）第 {@code cell}（0 基，0..15）格 = 全局第几格（0..159）。 */
    public static int slotIndex(int page, int cell) {
        if (page < 1 || page > PAGE_COUNT) {
            throw new IllegalArgumentException("库存输入页号越界：" + page + "（合法 1.." + PAGE_COUNT + "）");
        }
        if (cell < 0 || cell >= SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("页内格号越界：" + cell + "（合法 0.." + (SLOTS_PER_PAGE - 1) + "）");
        }
        return (page - 1) * SLOTS_PER_PAGE + cell;
    }

    /** 全局槽号（0..159）→ 页号（1 基）。 */
    public static int pageOf(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= CONFIG_SIZE) {
            throw new IllegalArgumentException("库存配置槽号越界：" + slotIndex + "（合法 0.." + (CONFIG_SIZE - 1) + "）");
        }
        return slotIndex / SLOTS_PER_PAGE + 1;
    }

    /** 全局槽号（0..159）→ 页内格号（0..15）。 */
    public static int cellOf(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= CONFIG_SIZE) {
            throw new IllegalArgumentException("库存配置槽号越界：" + slotIndex + "（合法 0.." + (CONFIG_SIZE - 1) + "）");
        }
        return slotIndex % SLOTS_PER_PAGE;
    }

    /** 一行式描述（日志/断言用）。 */
    public static @NotNull String describe() {
        return "stockInput=" + COLUMNS + "x" + ROWS_PER_PAGE + "x" + PAGE_COUNT
                + " (slots=" + CONFIG_SIZE + ", perPage=" + SLOTS_PER_PAGE + ")";
    }

    // ---------------------------------------------------------------- ⑦ 迁移：幂等闸门的唯一判定点

    /** 旧 32 条配置迁移要做的事。 */
    public enum MigrationAction {
        /** once 标记已置 ⇒ 什么都不做（<b>删光配置后重进也不会让旧配置复活</b>）。 */
        SKIP_ALREADY_MIGRATED,
        /** 我们的 160 格已经有配置 ⇒ 只置 once 标记，不覆盖。 */
        SKIP_ALREADY_CONFIGURED,
        /**
         * 两边都还没有配置 ⇒ <b>什么都不做，也<b>不</b>置 once 标记</b>。
         *
         * <p>🔴 为什么不能置标记：{@code onLoad()} 与 {@code loadCustomPersistedData(tag)} 的先后
         * <b>不是我们能保证的</b>（前者由 GT 的机器生命周期驱动，后者由 LDLib 在 BE {@code load} 注入里驱动）。
         * 如果 {@code onLoad} 先跑，那一刻两份 handler 都还是空 —— 此时若落「已迁移」标记，
         * 随后真正的存档数据到了也会被闸门挡掉 ⇒ <b>旧 32 条永远迁不过来，而且没有任何报错</b>。
         * 所以「两边都空」只当无事发生，让下一次（真正的 load 之后）再判一次。
         */
        NOTHING_TO_COPY,
        /** 真的把旧 32 条灌进 160 格。 */
        COPY
    }

    /**
     * 迁移闸门（纯函数；探针 {@code temp\stellar-p1\probe6\StockLayoutProbe.java} 实跑真值表）。
     *
     * <p>顺序是刻意的：先看 once 标记 ⇒ 再看「我们已有配置」⇒ 再看「旧的有没有」⇒ 才复制。
     * 任何一步写反都会导致「每次进存档重复迁」或「删光配置后旧配置复活」，
     * 所以它被抽成一个<b>可断言</b>的点（机器侧只调用它，不再自己写 if）。
     */
    public static @NotNull MigrationAction migrationAction(boolean migrated, int ourConfigured, int legacyConfigured) {
        if (migrated) {
            return MigrationAction.SKIP_ALREADY_MIGRATED;
        }
        if (ourConfigured > 0) {
            return MigrationAction.SKIP_ALREADY_CONFIGURED;
        }
        if (legacyConfigured <= 0) {
            return MigrationAction.NOTHING_TO_COPY;
        }
        return MigrationAction.COPY;
    }

    // ---------------------------------------------------------------- ⑧ 标签过滤：纯规则

    /** 白/黑名单串里的分隔符（与 gtlcore 标签过滤总线同一个：竖线）。 */
    public static final char TAG_SEPARATOR = '|';

    /**
     * 把用户填的标签串解析成去空格、去空项、去重的数组。
     *
     * <p>⚠️ 本方法<b>不做</b> {@code ResourceLocation} 合法性校验：非法项原样保留，
     * 由调用方在查标签时自然匹配失败（不静默丢 = 用户能看出自己填错了）。
     */
    public static @NotNull java.util.List<String> parseTags(@org.jetbrains.annotations.Nullable String raw) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("\\" + TAG_SEPARATOR)) {
            final String tag = part.trim();
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    /**
     * ⑧ 的判据（纯函数）：给定的物品拥有的标签集合应不应该被这台机器的标签过滤页收下。
     *
     * <ol>
     *   <li>黑名单命中 ⇒ <b>拒绝</b>（黑名单优先于白名单，这是 gtlcore 那台总线的口径）；</li>
     *   <li>白名单为空 ⇒ 全部<b>接受</b>（"不填白名单 = 什么都收"，与总线一致）；</li>
     *   <li>白名单非空 ⇒ 命中才接受。</li>
     * </ol>
     *
     * <p>🔴 阶段七起，白/黑名单的匹配<b>委托</b> {@link ShanhaiTagFilterExpression}（支持 {@code & | ! ( ) * ?}）
     * —— 竖线切分那套只是它的子集，所以要<b>消除第二份求值器</b>：本方法保留签名（阶段六那批探针口径不变），
     * 内部不再自己切串。
     *
     * @throws IllegalArgumentException 白/黑名单里有语法错（与表达式求值器一致 ⇒ 响亮失败）
     */
    public static boolean accepts(@org.jetbrains.annotations.Nullable String white,
                                 @org.jetbrains.annotations.Nullable String black,
                                 @org.jetbrains.annotations.Nullable java.util.Collection<String> itemTags) {
        final java.util.List<String> itemTagList = itemTags == null ? java.util.List.of() : new java.util.ArrayList<>(itemTags);
        if (black != null && !black.isBlank() && ShanhaiTagFilterExpression.matches(black, itemTagList)) {
            return false;
        }
        if (white == null || white.isBlank()) {
            return true;
        }
        return ShanhaiTagFilterExpression.matches(white, itemTagList);
    }
}
