package com.shanhai.common.itemedit;

import com.shanhai.ShanhaiMod;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海重构 · <b>多行提示的解析器（唯一一份实现）</b>。
 *
 * <h2>1. 谁在用它</h2>
 * <ul>
 *   <li>{@code com.shanhai.item.RealityShellItem#appendHoverText} —— 128 个「现实物品」壳（原有能力）；</li>
 *   <li>{@link ShanhaiItemOverrideHooks} —— 任意已存在物品的提示覆盖（本轮新增）。</li>
 * </ul>
 * 本类是把 {@code RealityShellItem} 里<b>已经在跑的那段代码原样搬出来</b>（行首标记的解析、按键门槛、
 * 灰色前缀、提示行），而不是重写一份 —— 两份实现漂移过一次就够受的。
 *
 * <h2>2. 行首标记（用户拍板的语义）</h2>
 * <pre>
 *   普通行        永远显示
 *   [s] 开头的行  只有按住 Shift 才显示
 *   [c] 开头的行  只有按住 Ctrl  才显示
 * </pre>
 * 若这一条提示里<b>存在</b>被门控的行、而当前一个键都没按 ⇒ 追加一行灰色提示
 * 「按住 Shift / Ctrl 查看完整提示」（{@link #HINT}），否则用户根本不知道还有内容。
 *
 * <h2>3. 🔴 为什么按键状态要经 {@code DistExecutor} 取</h2>
 * {@code Screen} 是<b>纯客户端</b>类，专用服务端上没有它。公共侧的代码直接引用
 * {@code ClientKeyState} 会让专服在类加载期炸 ⇒ 一律经
 * {@code DistExecutor.unsafeRunForDist(() -> ClientKeyState::shift, () -> () -> false)} 间接调用，
 * 服务端那一侧<b>根本不会加载</b>那个类（这也是 {@code ClientKeyState} 类注释里写死的口径）。
 *
 * <h2>4. 竖切（2026-11-01）：混排行拆成多个 {@code Component}</h2>
 * <b>现象</b>（用户实机）：同一行里混用两套样式时，纯山海关的行正常、混排行不正常：
 * <pre>
 *   第 1 行： &amp;$golden-123&amp;r                    ← 正常
 *   第 5 行： &amp;$magic-123&amp;r4§3567§r8             ← 不正常
 *   第 6 行： §4123§r4&amp;$aurora-567&amp;r8            ← 不正常
 * </pre>
 * <b>根因</b>：tooltip 走 {@code Component → FormattedCharSequence}，{@code §} <b>在那一步已经变成样式对象、
 * 并从文本里剥掉</b> ⇒ 我们的渲染器拿到的重建文本里一个 {@code §} 都没有
 * （「按文本里的 § 切段」在 tooltip 上<b>前提不成立</b>）⇒ 整行被当成「纯山海」⇒ § 段被按彩虹重画/丢色。
 * <p>
 * <b>修法（竖切第一步，就是本类 + {@link ShanhaiLoreSegmenter}）</b>：每一行先切成「山海段 / 原版段」，
 * <b>每段造一个 {@code Component}</b> ⇒ 每段各走一次 {@code Font.drawInBatch} ⇒ 各段交给各自的渲染器。
 * <ul>
 *   <li><b>没有 {@code &$} 的行：整行一个 {@code Component}，逐字保持改动前</b>（回归底线，见
 *       {@code segs.isEmpty()} 那一支）；</li>
 *   <li>切分规则、{@code §} 段内降级规则<b>只有一份</b>，在 {@link ShanhaiLoreSegmenter}（纯函数，可离线单测）；</li>
 *   <li>🔴 <b>已知风险（如实记）</b>：一个 {@code Component} = 一条 tooltip 行
 *       （{@code GuiGraphics#renderTooltip(List<Component>,…)} → {@code ForgeHooksClient#gatherTooltipComponents}
 *       → 每个元素各调一次 {@code Font.split} → 每个元素 {@code >=1} 个 {@code ClientTextTooltip}，
 *       其 {@code getHeight()} 恒为 {@code 10}）⇒ <b>拆成 N 段就会显示成 N 行</b>。
 *       这条<b>只能实机判</b>；一键回滚 = {@link #SPLIT_MIXED_LINES} 改 {@code false}。</li>
 * </ul>
 */
public final class ShanhaiItemLore {

    /** 行首标记：按住 Shift 才显示。 */
    public static final String TAG_SHIFT = "[s]";
    /** 行首标记：按住 Ctrl 才显示。 */
    public static final String TAG_CTRL = "[c]";
    /** 有被门控的行、但一个键都没按时追加的那一行。 */
    public static final String HINT = "§8按住 Shift / Ctrl 查看完整提示";
    /** 普通提示行的默认颜色前缀（跟现实物品壳同一口径）。 */
    public static final String LINE_PREFIX = "§7";

    /**
     * 🔴 <b>混排行分段开关</b>（2026-11-01 竖切）。
     *
     * <p>{@code true}（当前）：一行里出现 {@code &$} 时，按
     * {@link ShanhaiLoreSegmenter#splitSegments(String)} 切成几段、<b>每段一个 {@code Component}</b>
     * ⇒ 每段各自走一次 {@code Font.drawInBatch} ⇒ 各段由各自的渲染器接管（这就是本次要的行为）。
     * <br>{@code false}：<b>逐字退回改动前</b>——整行一个 {@code Component}（旧路径的代码仍在下面 {@code segs.isEmpty()} 那一支）。
     *
     * <p>⚠️ <b>为什么留这个开关</b>：一个 {@code Component} = <b>一条 tooltip 行</b>
     * （{@code GuiGraphics#renderTooltip(List&lt;Component&gt;…)} → {@code ForgeHooksClient#gatherTooltipComponents}
     * → {@code splitLine} 里对每个元素各调一次 {@code Font.split} → 每个元素 {@code >=1} 个
     * {@code ClientTextTooltip}，其 {@code getHeight()} 恒为 {@code 10}）
     * ⇒ <b>拆成 N 段就会变成 N 行</b>。这条风险写进交付文档的「待你确认」，开关是它的一键回滚。
     */
    public static final boolean SPLIT_MIXED_LINES = false;

    /** 段内降级 WARN 的限流间隔（与 {@code ShanhaiTextParser} 的 5000 ms 同一口径：只限流不报数 = 静默）。 */
    private static final long SEG_WARN_GAP_MS = 5000L;

    private static final AtomicLong SEG_WARN_LAST = new AtomicLong(0L);
    private static final AtomicInteger SEG_WARN_SUPPRESSED = new AtomicInteger(0);

    private ShanhaiItemLore() {}

    /** 是否按住 Shift（服务端恒 false）。 */
    public static boolean shiftHeld() {
        final boolean v = net.minecraftforge.fml.DistExecutor.unsafeRunForDist(
                () -> com.shanhai.client.reality.ClientKeyState::shift, () -> () -> false);
        return v;
    }

    /** 是否按住 Ctrl（服务端恒 false）。 */
    public static boolean ctrlHeld() {
        final boolean v = net.minecraftforge.fml.DistExecutor.unsafeRunForDist(
                () -> com.shanhai.client.reality.ClientKeyState::ctrl, () -> () -> false);
        return v;
    }

    /**
     * 把一串多行提示按「按键门槛」筛进 {@code tooltip}。
     *
     * @param lore    多行文本（真实换行；也认字面量 {@code \n}，见 {@link ShanhaiItemOverrideTable}）
     * @param shift   当前是否按住 Shift
     * @param ctrl    当前是否按住 Ctrl
     * @param tooltip 目标列表（会被就地追加）
     * @return {@code true} = 存在被门控的行（调用方据此决定要不要加 {@link #HINT}）
     */
    public static boolean addLoreLines(String lore, boolean shift, boolean ctrl, List<Component> tooltip) {
        if (lore == null || lore.isBlank() || tooltip == null) {
            return false;
        }
        boolean hasGated = false;
        for (String raw : lore.split("\\R")) {
            if (raw.isBlank()) {
                continue;
            }
            String line = raw;
            boolean needShift = false;
            boolean needCtrl = false;
            if (line.startsWith(TAG_SHIFT)) {
                needShift = true;
                line = line.substring(TAG_SHIFT.length());
            } else if (line.startsWith(TAG_CTRL)) {
                needCtrl = true;
                line = line.substring(TAG_CTRL.length());
            }
            if (line.isBlank()) {
                continue;
            }
            if (needShift || needCtrl) {
                hasGated = true;
                final boolean held = needShift ? shift : ctrl;
                if (!held) {
                    continue;
                }
            }
            // ---- 2026-11-01 竖切：没有 &$ 的行不走分段 ⇒ 逐字保持改动前（回归底线） ----
            final List<ShanhaiLoreSegmenter.Seg> segs = SPLIT_MIXED_LINES
                    ? ShanhaiLoreSegmenter.splitSegments(line)
                    : List.of();
            if (segs.isEmpty()) {
                tooltip.add(Component.literal(LINE_PREFIX + line));
                continue;
            }
            // 每段各自一个 Component ⇒ 各段走各自的渲染通道（我们 / 原版）。
            // ⚠️ 每段都带 LINE_PREFIX：拆开后原版段是新的 Component，不补 §7 的话
            //    「§r 之后剩下的字」会从 §7 灰变成默认白（旧版整行一个 Component 时它是灰的）。
            //    山海段的 §7 前缀无害：它进不到我们的 parse 里（§ 在 FCS 那一步已变成样式对象）。
            for (int i = 0; i < segs.size(); i++) {
                final ShanhaiLoreSegmenter.Seg seg = segs.get(i);
                if (seg.degraded()) {
                    warnDegradedSegment(seg.text(), line);
                }
                tooltip.add(Component.literal(LINE_PREFIX + seg.text()));
            }
        }
        return hasGated;
    }

    /**
     * 山海段内部出现 {@code §} ⇒ 该段已降级成原版段（{@link ShanhaiLoreSegmenter} 规则 5），这里补一行 WARN。
     *
     * <p><b>为什么降级而不是硬画</b>：{@code §} 是零宽控制码，我们的逐字路径自己算宽、自己取色，
     * 一段里混着它会让下标换算失效（{@code ShanhaiTextParser} §7.3 同一条判据）。
     *
     * <p><b>限流</b>：本方法在 tooltip 的<b>每帧</b>路径上（60 次/秒），所以照本工程既有做法
     * 每 {@value #SEG_WARN_GAP_MS} ms 最多一行，并带上被折叠掉的行数（只限流不报数 = 静默）。
     */
    private static void warnDegradedSegment(String segment, String line) {
        if (!ShanhaiItemOverrideTable.active()) {
            return;   // 覆盖表没生效时不该有 lore 走到这里；真走到了也不刷日志
        }
        final long now = System.currentTimeMillis();
        final long last = SEG_WARN_LAST.get();
        if (last != 0L && now - last < SEG_WARN_GAP_MS) {
            SEG_WARN_SUPPRESSED.incrementAndGet();
            return;
        }
        if (!SEG_WARN_LAST.compareAndSet(last, now)) {
            SEG_WARN_SUPPRESSED.incrementAndGet();
            return;
        }
        final int suppressed = SEG_WARN_SUPPRESSED.getAndSet(0);
        ShanhaiMod.LOGGER.warn("{} lore_seg_degraded reason=section_sign_inside_mountain_seg"
                        + " seg=\"{}\" line=\"{}\" degraded_now=1 suppressed={} -> 这一段按原版画（§ 由原版解析）",
                ShanhaiItemOverrideTable.PREFIX, segment, line, suppressed);
    }

    /** 便捷版：自己取一次按键状态（两条入口最常用）。 */
    public static boolean addLoreLines(String lore, List<Component> tooltip) {
        return addLoreLines(lore, shiftHeld(), ctrlHeld(), tooltip);
    }
}
