package com.shanhai.common.itemedit;

import com.shanhai.ShanhaiMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · <b>物品覆盖的四个拦截面（真正的实现都在这里，mixin 只当转发壳）</b>。
 *
 * <h2>1. 四条拦截面分别挡什么（用户点名的四条，缺一条就有一处看不见）</h2>
 * <pre>
 *   ① ItemStack#getHoverName()                 物品栏 / tooltip / JEI 列表  ⇒ 改名主通道
 *   ② Item#getDescription()                    GTCEu 的 Jade「配方输出」读它（踩过：不改这里，
 *                                              机器里产物名还是 item.shanhai.xxx）
 *   ③ ItemStack#getTooltipLines(Player,Flag)   追加/替换描述行
 *   ④ ItemStack#getMaxStackSize()              堆叠上限
 * </pre>
 *
 * <h2>2. 🔴 为什么这些方法放在普通类、mixin 里一个 MC 成员都不许碰</h2>
 * 见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2 的实测证据：mixin 类体里的 MC 成员引用
 * <b>在生成的 refmap 里没有条目</b> ⇒ 生产环境会拿开发名去 SRG 命名的类上找、找不到。
 * 所以两个 mixin 都只做「类型对得上的形参 + {@code (Object) this} + 一次静态调用」，
 * 所有 {@code getItem()} / {@code isEmpty()} / {@code getKey()} 都在本类里做（普通类会被 reobf）。
 *
 * <h2>3. 🔴 纪律：本类<b>永远不许把游戏弄坏</b></h2>
 * 每个入口整段 {@code try/catch(Throwable)} 自吞 + 一次性 WARN；出任何问题都返回
 * {@code null}（= 不改），让原版那条路照旧。返回值约定：
 * <ul>
 *   <li>{@code null} ⇒ 调用方<b>不要</b>干预（原样用原版结果）；</li>
 *   <li>非 {@code null} ⇒ 用它替换返回值。</li>
 * </ul>
 *
 * <h2>4. 热路径开销</h2>
 * {@code getMaxStackSize()} / {@code getHoverName()} 是每帧每格都会走的路径。第一道闸是
 * {@link ShanhaiItemOverrideTable#active()}（一次 volatile 读 + {@code isEmpty()}），
 * 表为空时（= 没配任何覆盖）总开销约等于一次静态字段读后返回。表非空时才做一次注册表反查 + 一次 Map 查。
 */
public final class ShanhaiItemOverrideHooks {

    private static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 每种失败只 WARN 一次（避免每帧刷日志）。 */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private ShanhaiItemOverrideHooks() {}

    // ------------------------------------------------------------------ ① 显示名

    /**
     * {@code ItemStack#getHoverName()} 的 {@code RETURN}。
     *
     * <p>⚠️ 这里<b>优先于</b> NBT 自定义名（铁砧改名）：物品一旦配了覆盖名，覆盖名就是最终显示名。
     * 这一条是取舍，写进报告的「待你确认」。
     */
    public static Component hoverName(Object stackObj, Component original) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return null;
            }
            if (!(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
                return null;
            }
            final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.getFor(stack.getItem());
            if (e == null || !e.hasName()) {
                return null;
            }
            return Component.literal(e.name());
        } catch (Throwable t) {
            warnOnce("hover_name", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ ② Item#getDescription()

    /**
     * {@code Item#getDescription()} 的 {@code RETURN}。
     *
     * <p>为什么必须有这一条：{@code RealityShellItem} 的注释里有逐字取证 ——
     * GTCEu 的 Jade 提供器 {@code RecipeOutputProvider#getItemName} 走的是
     * <b>{@code ItemStack.getItem().getDescription()}</b>（无参重载），不是 {@code getHoverName()}。
     * 不改这里，机器产物名就还是翻译键 {@code item.xxx.yyy}。
     *
     * <p>⚠️ 局限：注入点在 {@code Item} 基类上 ⇒ <b>自己覆写了 {@code getDescription()} 的模组物品</b>
     * 不受影响。这是 mixin 的固有边界，写进报告。
     */
    public static Component description(Object itemObj, Component original) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return null;
            }
            if (!(itemObj instanceof Item item)) {
                return null;
            }
            final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.getFor(item);
            if (e == null || !e.hasName()) {
                return null;
            }
            return Component.literal(e.name());
        } catch (Throwable t) {
            warnOnce("description", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ ③ 提示

    /**
     * {@code ItemStack#getTooltipLines(Player, TooltipFlag)} 的 {@code RETURN}。
     *
     * <p>原版该方法返回的一定是 {@code Lists.newArrayList()} 造出来的<b>可变</b> ArrayList
     * （javap 取证：{@code 0: invokestatic Lists.newArrayList; 3: astore_3}，末尾 {@code 1410: aload_3; areturn}），
     * 所以 {@code append} 模式就地追加即可；万一被别的模组换成了不可变列表，退化成新建一份。
     *
     * <p>{@code replace} 模式：<b>保留第 0 行（名字行，带稀有度样式）</b>，丢掉它后面的一切
     * （原物品自带描述、附魔、属性、GTCEu 的机器信息……）只留我们的行。这就是"尽量去掉原物品自带描述"，
     * 而且不需要去猜哪一行是"原物品的描述"（那是猜不准的）。
     */
    public static List<Component> tooltip(Object stackObj, Object originalList) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return null;
            }
            if (!(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
                return null;
            }
            final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.getFor(stack.getItem());
            if (e == null || !e.hasLore()) {
                return null;
            }
            if (!(originalList instanceof List<?>)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final List<Component> list = (List<Component>) originalList;

            final boolean shift = ShanhaiItemLore.shiftHeld();
            final boolean ctrl = ShanhaiItemLore.ctrlHeld();
            final List<Component> ours = new ArrayList<>(4);
            final boolean gated = ShanhaiItemLore.addLoreLines(e.lore(), shift, ctrl, ours);
            if (gated && !shift && !ctrl) {
                ours.add(Component.literal(ShanhaiItemLore.HINT));
            }
            if (ours.isEmpty()) {
                return null;
            }
            if (e.replaceMode()) {
                final List<Component> out = new ArrayList<>(ours.size() + 1);
                if (!list.isEmpty()) {
                    out.add(list.get(0));
                }
                out.addAll(ours);
                return out;
            }
            try {
                list.addAll(ours);
                return null;
            } catch (Throwable immutable) {
                final List<Component> out = new ArrayList<>(list.size() + ours.size());
                out.addAll(list);
                out.addAll(ours);
                return out;
            }
        } catch (Throwable t) {
            warnOnce("tooltip", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ ④ 堆叠上限

    /**
     * {@code ItemStack#getMaxStackSize()} 的 {@code RETURN}。
     *
     * <p>为什么拦 {@code ItemStack} 而不是 {@code Item}：{@code Item#getMaxStackSize()}（无参）是
     * {@code final}（javap 取证），模组改不掉；而 {@code ItemStack#getMaxStackSize()} 的字节码是
     * {@code invokevirtual Item.getMaxStackSize:(Lnet/minecraft/world/item/ItemStack;)I} ⇒ 拦在
     * {@code ItemStack} 上才<b>对任意物品一视同仁</b>（原版物品、别人模组的物品，都不用自己覆写）。
     */
    public static Integer maxStack(Object stackObj, int original) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return null;
            }
            if (!(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
                return null;
            }
            final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.getFor(stack.getItem());
            if (e == null || !e.hasStack()) {
                return null;
            }
            final int want = e.maxStack();
            return want == original ? null : want;
        } catch (Throwable t) {
            warnOnce("max_stack", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static void warnOnce(String where, Throwable t) {
        if (WARNED.putIfAbsent(where, Boolean.TRUE) == null) {
            ShanhaiMod.LOGGER.warn("{} hook_soft_failed where={} err={} -> 退回原版那一条路（本提示只打一次）",
                    PREFIX, where, t.toString());
        }
    }
}
