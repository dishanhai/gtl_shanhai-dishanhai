package com.shanhai.client.ptype;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.shanhai.ShanhaiMod;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 选择器的状态：面板开关 / 搜索词 / 滚动 / <b>「本地记住的选择」</b> / 粘性重下发。
 *
 * <h2>1. 为什么分「本地选择」和「样板里的真实值」两套</h2>
 * <ul>
 *   <li><b>本地选择</b>（{@link #selection}，按菜单实例 identity 存）：它决定<b>下一次编码</b>会写什么；</li>
 *   <li><b>真实值</b>（{@link PatternTypeSupport#readWritten}，直接从编码槽那张样板的 NBT 读）：
 *       它是<b>已经发生的事实</b>，也是「星律样板总成」将来真正读到的东西。</li>
 * </ul>
 * 两者都可能为空、也可能不一致（例如：选完新类型但还没再编码；或者这张样板是 JEI 快速上传写的）。
 * 界面把两者<b>都显示出来</b>（胶囊显示本地选择优先，面板底部两行都给），见
 * {@code PatternTypeSelectorRenderer} 的类注释。任务书要求「优先读样板」的场景
 * （没有本地选择时）完全满足。
 *
 * <h2>2. 🔴 粘性重下发（本模组的决定，报告里标了「待确认」）</h2>
 * javap 实证 gtlcore 的 {@code encode()} → {@code gTLCore$writeQuickUploadMetadata(stack)} →
 * {@code (stack, true)}，而 {@code (stack, true)} 在写完 NBT 后把
 * {@code pendingQuickUploadRecipeTypeId} 清成 null ⇒ <b>gtlcore 的选择是一次性的</b>：
 * 只影响紧接的那一次编码。
 * <p>本模组的目标是「之后在这台终端上编码出来的样板都带上这个类型」，所以在客户端做一层
 * <b>粘性重下发</b>：{@link #tickSticky} 每 250 ms 看一次编码槽，<b>只要那张样板换了一张</b>
 * 且本地有选择，就把同一个值再下发一次。代价是每次编码多一个极小的 client action
 * （玩家点一次「编码」才发生一次）。
 * <p>关掉它 = 把 {@link #STICKY_REAPPLY} 改 false（回到 gtlcore 原生的一次性语义）。
 */
@OnlyIn(Dist.CLIENT)
public final class PatternTypeSelectorState {

    /** 粘性重下发总开关（见类注释 §2）。 */
    public static final boolean STICKY_REAPPLY = true;

    /** 观察编码槽的最小间隔（毫秒）。 */
    private static final long STICKY_INTERVAL_MS = 250L;

    /** 面板状态（同一时刻只可能开一个面板）。 */
    public static final class PanelState {

        /** 面板归属于哪个菜单（identity 比较）。 */
        public AbstractContainerMenu menu;
        public boolean open;
        public String query = "";
        public int scroll;
        public boolean searchFocused;

        private String filteredQuery;
        private List<RecipeTypeCatalog.Entry> filtered = List.of();

        /**
         * 当前过滤结果（查询词没变就直接复用上一帧的列表，避免每帧重新过滤 + 分配）。
         * <p>顺带把 {@link #scroll} 夹回合法范围 —— 滚动越界时列表会「空白」看起来像加载失败。
         */
        public List<RecipeTypeCatalog.Entry> filtered() {
            if (!query.equals(filteredQuery)) {
                filtered = RecipeTypeCatalog.filter(RecipeTypeCatalog.all(), query);
                filteredQuery = query;
                scroll = 0;
            }
            int max = Math.max(0, filtered.size() - 1);
            if (scroll > max) {
                scroll = max;
            }
            if (scroll < 0) {
                scroll = 0;
            }
            return filtered;
        }

        void reset() {
            open = false;
            menu = null;
            query = "";
            scroll = 0;
            searchFocused = false;
            filteredQuery = null;
            filtered = List.of();
        }

        /** 面板开给 {@code menu} 时复位（每次打开都是一份干净的全量列表，且搜索框直接可输入）。 */
        void openFor(AbstractContainerMenu target) {
            reset();
            menu = target;
            open = true;
            // 打开即可输入：玩家点开面板第一件事几乎总是打字过滤，
            // 若还要先点一下搜索框，那一下会被当成「点面板空白」吃掉，手感很差。
            searchFocused = true;
        }
    }

    /** 粘性重下发的每菜单观察窗。 */
    private static final class Watch {
        ItemStack last = ItemStack.EMPTY;
        long lastCheckMs;
    }

    private static final PanelState PANEL = new PanelState();
    private static final Map<AbstractContainerMenu, ResourceLocation> SELECTIONS = new WeakHashMap<>();
    private static final Map<AbstractContainerMenu, Watch> WATCHES = new WeakHashMap<>();

    private PatternTypeSelectorState() {}

    // ------------------------------------------------------------------ 面板

    public static PanelState panel() {
        return PANEL;
    }

    /** 面板是否正开在 {@code menu} 这张界面上。 */
    public static boolean isOpenFor(AbstractContainerMenu menu) {
        return PANEL.open && menu != null && PANEL.menu == menu;
    }

    /** 打开面板（换菜单时自动改属主）。 */
    public static void openFor(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        PANEL.openFor(menu);
        ShanhaiMod.LOGGER.debug("{} 面板已打开（menu={}）", PatternTypeSupport.PREFIX, menu.getClass().getSimpleName());
    }

    /** 关面板（不清搜索词以外的任何东西）。 */
    public static void close() {
        PANEL.reset();
    }

    /** 屏幕 init / 关闭时调用：面板一律关掉（窗口尺寸变了，坐标不可信）。 */
    public static void resetOnScreenInit() {
        if (PANEL.open) {
            ShanhaiMod.LOGGER.debug("{} 屏幕重init ⇒ 面板关闭", PatternTypeSupport.PREFIX);
        }
        PANEL.reset();
    }

    // ------------------------------------------------------------------ 本地选择

    public static ResourceLocation selection(AbstractContainerMenu menu) {
        return menu == null ? null : SELECTIONS.get(menu);
    }

    public static void setSelection(AbstractContainerMenu menu, ResourceLocation id) {
        if (menu == null) {
            return;
        }
        if (id == null) {
            SELECTIONS.remove(menu);
        } else {
            SELECTIONS.put(menu, id);
        }
    }

    // ------------------------------------------------------------------ 编码槽真实值

    /**
     * 编码槽那张样板里写着的配方类型。
     *
     * <p>⚠️ 这不是「一条」：gtlcore 写 NBT 时走的是
     * {@code PatternQuickUploadRecipeTypeResolver.expandProxiedRecipeTypeIds(id)}（javap 实证），
     * 即<b>代理扩展</b>后的<b>集合</b> ⇒ 选 {@code gtceu:chemical_reactor} 也可能写进多条等价 id。
     * 所以判断「这张样板是不是就是当前选择」必须用 {@link #contains}，不能拿 first 比。
     */
    public record Written(ResourceLocation first, int count, java.util.Set<ResourceLocation> ids) {

        public static final Written NONE = new Written(null, 0, java.util.Set.of());

        public boolean empty() {
            return count <= 0 || first == null;
        }

        /** 这张样板里有没有 {@code id}（= 它的 NBT 列表里包含它）。 */
        public boolean contains(ResourceLocation id) {
            return id != null && !ids.isEmpty() && ids.contains(id);
        }
    }

    /**
     * 从编码槽那张<b>已成型</b>的样板读真实值（一帧只读一次，调用方自己缓存）。
     *
     * <p>多条时取字典序最小的一条当代表，保证显示稳定（不随 {@code Set} 迭代序跳动）。
     */
    public static Written written(PatternEncodingTermMenu menu) {
        java.util.Set<ResourceLocation> ids = PatternTypeSupport.readWritten(menu);
        if (ids == null || ids.isEmpty()) {
            return Written.NONE;
        }
        ResourceLocation first = ids.size() == 1
                ? ids.iterator().next()
                : ids.stream()
                        .sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                        .findFirst()
                        .orElse(null);
        return first == null ? Written.NONE : new Written(first, ids.size(), java.util.Set.copyOf(ids));
    }

    // ------------------------------------------------------------------ 粘性重下发

    /**
     * 每帧调用（内部自己限频）。见类注释 §2。
     *
     * @return 这一帧真的重下发了一次就返回 {@code true}（日志已经打好）
     */
    public static boolean tickSticky(PatternEncodingTermMenu menu) {
        if (!STICKY_REAPPLY || menu == null) {
            return false;
        }
        Watch watch = WATCHES.computeIfAbsent(menu, ignored -> new Watch());
        long now = Util.getMillis();
        if (now - watch.lastCheckMs < STICKY_INTERVAL_MS) {
            return false;
        }
        watch.lastCheckMs = now;

        ItemStack current = PatternTypeSupport.encodedStack(menu);
        boolean changed = !ItemStack.matches(current, watch.last);
        watch.last = current.copy();
        if (!changed) {
            return false;
        }

        ResourceLocation selected = SELECTIONS.get(menu);
        if (selected == null) {
            return false;
        }
        // 只对「真的编码出来了」的样板补发：空白板 / 空槽不补，避免无意义的包
        if (!PatternTypeSupport.isEncodedPattern(current)) {
            return false;
        }
        if (!PatternTypeSupport.apply(menu, selected)) {
            return false;
        }
        ShanhaiMod.LOGGER.info("{} 编码后重下发（判据=编码槽样板变化；gtlcore 的 pending 是一次性的）recipeType={}",
                PatternTypeSupport.PREFIX, selected);
        return true;
    }
}
