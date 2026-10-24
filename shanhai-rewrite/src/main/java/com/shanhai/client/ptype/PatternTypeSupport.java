package com.shanhai.client.ptype;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.client.gui.AEBaseScreen;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.shanhai.ShanhaiMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import org.gtlcore.gtlcore.client.gui.PatterEncodingTermMenuModify;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;

import java.util.List;
import java.util.Set;

/**
 * 「配方类型」选择器与 gtlcore 的唯一接触面。
 *
 * <h2>1. 为什么能<b>零 mixin、零反射</b>做到</h2>
 * gtlcore 1.2.3.2 自己已经把这件事做了一半，并且把入口<b>公开</b>了：
 * <ul>
 *   <li>接口 {@code org.gtlcore.gtlcore.client.gui.PatterEncodingTermMenuModify}
 *       （<b>类名确实少一个 n：{@code Patter} 不是 {@code Pattern}</b>），
 *       javap 实证：{@code public interface}，方法全是 {@code default}，其中
 *       {@code public default void gTLCore$setQuickUploadRecipeType(ResourceLocation)}；</li>
 *   <li>gtlcore 的 {@code PatternEncodingTermMenuMixin} 让<b>原版</b>
 *       {@code appeng.menu.me.items.PatternEncodingTermMenu} <b>实现</b>了这个接口，
 *       并且用<b>同签名具体方法</b>盖掉了接口里的空 default
 *       （javap：{@code public void gTLCore$setQuickUploadRecipeType(ResourceLocation)} 有真实方法体）；</li>
 *   <li>那个具体方法自己分两端（javap 逐行）：{@code isClientSide()} 为真 ⇒
 *       {@code sendClientAction("setQuickUploadRecipeType", id == null ? "" : id.toString())}；
 *       为假 ⇒ {@code gTLCore$pendingQuickUploadRecipeTypeId = id}。
 *       ⇒ <b>我们只要在客户端调一次</b>，AE2 官方的 client action 通道把值送到服务端菜单。</li>
 * </ul>
 * 所以本类<b>不注入任何 AE2 私有成员</b>、<b>不反射 protected 的 sendClientAction</b>，
 * 只做一次 {@code instanceof} + 一次接口调用。AE2 的 {@code encodePattern()} /
 * {@code modePanels} 在本文件里<b>一次都没出现</b>。
 *
 * <h2>2. 值写进 NBT 的哪个键（与 JEI 快速上传同一个键）</h2>
 * AE2 的 {@code encode()} 被 gtlcore 的 mixin 盖掉后<b>无条件</b>调
 * {@code gTLCore$writeQuickUploadMetadata(pattern)}，而它内部只在
 * {@code pendingQuickUploadRecipeTypeId != null} 时才写 ⇒ <b>没选就什么都不写</b>（安全）。
 * 键（javap 常量池实证，{@code ROOT_KEY="gtlcore"} / {@code RECIPE_TYPES_KEY="patternQuickUploadRecipeTypes"}）：
 * <pre>
 *   ItemStack.tag.gtlcore.patternQuickUploadRecipeTypes = ListTag&lt;String&gt;
 * </pre>
 * 读/写/取名一律走 gtlcore 自己的公开工具类 {@code PatternQuickUploadMetadata}
 * （{@code public final}，方法都 {@code public static}），因此键名不会漂移。
 *
 * <h2>3. 🔴 一个 <b>与任务书措辞不同</b> 的实证行为（必读）</h2>
 * 单参 {@code gTLCore$writeQuickUploadMetadata(stack)} 内部转调
 * {@code (stack, true)}；javap 里第 72~81 字节码 = {@code if (arg2) pending = null}
 * ⇒ <b>gtlcore 的选择是「一次性」的：写完一张样板就把 pending 清空</b>。
 * （任务书说的「之后在这台终端上编码出来的样板都带上」是<b>期望</b>，不是 gtlcore 的默认行为。）
 * 本模组的取舍见 {@link PatternTypeSelectorState#tickSticky}：客户端在观察到
 * 「编码槽里的样板变了」时把本地选择<b>重新下发一次</b>，于是对本终端表现为「粘住」。
 * 这一条是<b>本模组自己的决定</b>，报告里已标「待确认」。
 *
 * <h2>4. 拿不到接口怎么办</h2>
 * gtlcore 在 mods.toml 里是 {@code mandatory=false}，所以「类根本不在」也是合法状态：
 * 所有对 gtlcore 类的引用都写在 {@code try/catch (Throwable)} 里，并且先过
 * {@link ModList#isLoaded} 闸门 ⇒ 接口缺席时 {@link #supports(AbstractContainerMenu)}
 * 恒为 {@code false}（胶囊显示「本终端不支持」、不响应点击），<b>不报错、不崩</b>。
 */
@OnlyIn(Dist.CLIENT)
public final class PatternTypeSupport {

    /** 本功能的统一日志前缀（无头服/客户端日志里可 grep 断言）。 */
    public static final String PREFIX = "[SHANHAI-PTYPE]";

    private static final String GTLCORE_MODID = "gtlcore";

    private static Boolean gtlcorePresent;
    private static boolean supportsProbeFailed;
    private static boolean applyMissingLogged;
    private static boolean readFailedLogged;

    private PatternTypeSupport() {}

    // ------------------------------------------------------------------ 环境

    /** gtlcore 在不在（缓存；{@link ModList} 不可用时按「不在」处理）。 */
    public static boolean gtlcorePresent() {
        if (gtlcorePresent == null) {
            boolean present;
            try {
                present = ModList.get().isLoaded(GTLCORE_MODID);
            } catch (Throwable t) {
                present = false;
            }
            gtlcorePresent = present;
        }
        return gtlcorePresent;
    }

    // ------------------------------------------------------------------ 取菜单

    /**
     * 从当前屏幕拿到「样板编码终端」菜单。
     *
     * <p>判据放在<b>菜单</b>上而不是屏幕类上，这样 ae2wtlib 的无线样板编码终端
     * （同样是 {@code PatternEncodingTermMenu}）也自动覆盖。
     *
     * @return 不是样板编码终端时返回 {@code null}
     */
    public static PatternEncodingTermMenu menuOf(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return null;
        }
        AbstractContainerMenu menu;
        try {
            menu = container.getMenu();
        } catch (Throwable t) {
            return null;
        }
        return menu instanceof PatternEncodingTermMenu patternMenu ? patternMenu : null;
    }

    /** 屏幕是不是 AE2 系（只有 AE2 系才有 {@code getGuiLeft()/getGuiTop()} 这套坐标）。 */
    public static boolean hasGuiAnchor(Screen screen) {
        return screen instanceof AEBaseScreen<?>;
    }

    /** AE2 窗口左上角（GUI 缩放后的绝对坐标）。 */
    public static int guiLeft(Screen screen) {
        return screen instanceof AEBaseScreen<?> base ? base.getGuiLeft() : 0;
    }

    /** AE2 窗口左上角（GUI 缩放后的绝对坐标）。 */
    public static int guiTop(Screen screen) {
        return screen instanceof AEBaseScreen<?> base ? base.getGuiTop() : 0;
    }

    // ------------------------------------------------------------------ 能力探测

    /**
     * 这台终端的菜单有没有被 gtlcore 改过（即是否实现了那个公开接口）。
     *
     * <p>🔴 {@code instanceof} 在「接口类根本不存在」时会抛 {@link NoClassDefFoundError}
     * ——它是 {@link Error} 不是 {@link Exception}，所以这里捕 {@code Throwable}。
     */
    public static boolean supports(AbstractContainerMenu menu) {
        if (menu == null || !gtlcorePresent()) {
            return false;
        }
        try {
            return menu instanceof PatterEncodingTermMenuModify;
        } catch (Throwable t) {
            if (!supportsProbeFailed) {
                supportsProbeFailed = true;
                ShanhaiMod.LOGGER.warn("{} 能力探测失败（gtlcore 接口不可解析）⇒ 一律按「本终端不支持」处理", PREFIX, t);
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 把配方类型下发给这台终端（{@code null} = 清除）。
     *
     * <p>客户端那侧只会发 AE2 官方的 client action，服务端那侧只往
     * {@code pendingQuickUploadRecipeTypeId} 里存值 —— 两端行为都在 gtlcore 里，
     * 本方法只负责「调一次」。
     *
     * @return 真的调到了接口才返回 {@code true}
     */
    public static boolean apply(PatternEncodingTermMenu menu, ResourceLocation id) {
        if (menu == null) {
            return false;
        }
        try {
            if (!(menu instanceof PatterEncodingTermMenuModify modify)) {
                if (!applyMissingLogged) {
                    applyMissingLogged = true;
                    ShanhaiMod.LOGGER.warn("{} 下发被跳过：menu={} 未实现 PatterEncodingTermMenuModify"
                            + "（本终端没有经过 gtlcore 的 mixin）", PREFIX, menu.getClass().getName());
                }
                return false;
            }
            modify.gTLCore$setQuickUploadRecipeType(id);
            ShanhaiMod.LOGGER.info("{} 已下发 recipeType={}（menu={}）",
                    PREFIX, id == null ? "null（清除）" : id, menu.getClass().getSimpleName());
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} 下发失败 recipeType={}（menu={}）",
                    PREFIX, id, menu.getClass().getName(), t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 读回

    /**
     * 从编码槽里那张<b>已成型</b>的样板读真实写进去的配方类型。
     *
     * <p>槽位语义取自 AE2 自己的 {@code SlotSemantics.ENCODED_PATTERN}
     * （样式表 {@code pattern_encoding_terminal.json} 里就是它：left 147 / bottom 118），
     * 不走任何私有字段。
     *
     * @return 读不到时返回空集（永不为 {@code null}）
     */
    public static Set<ResourceLocation> readWritten(PatternEncodingTermMenu menu) {
        if (menu == null) {
            return Set.of();
        }
        try {
            List<Slot> slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
            if (slots.isEmpty()) {
                return Set.of();
            }
            ItemStack stack = slots.get(0).getItem();
            if (stack == null || stack.isEmpty()) {
                return Set.of();
            }
            Set<ResourceLocation> ids = PatternQuickUploadMetadata.readRecipeTypeIds(stack);
            return ids == null ? Set.of() : ids;
        } catch (Throwable t) {
            if (!readFailedLogged) {
                readFailedLogged = true;
                ShanhaiMod.LOGGER.warn("{} 读取编码槽里的配方类型失败（按「读不到」处理，界面退回本地选择）", PREFIX, t);
            }
            return Set.of();
        }
    }

    /** 编码槽里那张样板本身（空槽返回 {@link ItemStack#EMPTY}）。 */
    public static ItemStack encodedStack(PatternEncodingTermMenu menu) {
        if (menu == null) {
            return ItemStack.EMPTY;
        }
        try {
            List<Slot> slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
            if (slots.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = slots.get(0).getItem();
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 是不是一张已成型的 AE2 样板（AE2 公开工具；异常按「不是」处理）。 */
    public static boolean isEncodedPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return PatternDetailsHelper.isEncodedPattern(stack);
        } catch (Throwable t) {
            return false;
        }
    }
}
