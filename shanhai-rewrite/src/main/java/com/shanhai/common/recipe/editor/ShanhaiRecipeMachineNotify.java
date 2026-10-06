package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 🔴🔴 <b>2026-10-06（用户实测："删掉的配方机器还能跑，必须重进存档才停；{@code /shanhai edit restore} 一样"）：</b>
 * <b>重建索引之后必须【通知机器重新选配方】</b>。
 *
 * <h2>为什么机器会"继续跑那条已经没了的配方"</h2>
 * 我们删/恢复一条配方时做的是：重建 GT 索引树（{@code GTRecipeType.getLookup()}）＋ 写回原版两表。
 * 但**机器那一侧还缓存着"上一次匹配到的配方"**（{@code RecipeLogic.lastRecipe}）——
 * 只要那一轮没结束，它就接着按旧对象跑 ✗
 * <p>⇒ 上游对这种情况有现成的两个动作（本类用的就是它们，不是新发明的）：
 * <ul>
 *   <li>{@link RecipeLogic#markLastRecipeDirty()} —— 让下一次查找**重新选配方**（不打断当前轮）；</li>
 *   <li>{@link RecipeLogic#updateTickSubscription()} —— 让这台机器的 tick 订阅跟着新状态走。</li>
 * </ul>
 * <p>⚠️ <b>"重进存档就好了"正好反证这一条</b>：重进存档 = 整台机器的 {@code RecipeLogic} 重建 ⇒
 * {@code lastRecipe} 清空 ⇒ 自然停 ✓
 *
 * <h2>怎么找"在用这个配方类型的机器"（刻意做轻）</h2>
 * 只遍历**每个维度已加载的实体**一次（{@code level.getEntities().getAll()}），
 * 命中 {@link IRecipeLogicMachine} 且它的 {@code getRecipeTypes()} 里有这个类型才动它。
 * <b>不做全维度遍历、不做方块全表扫描</b>；一次保存只跑一遍（与索引重建同一拍，量级 ≈ 几毫秒）✓
 *
 * <h2>判据（服务端 logs\latest.log）</h2>
 * <pre>
 * [SHANHAI-EDIT] recipe_machine_notify type=gtceu:zero_point_conversion scanned=123 matched=1 dirty=1
 *               （重建索引之后通知机器重选配方；matched=0 = 这个类型当前没有机器在跑）
 * </pre>
 */
public final class ShanhaiRecipeMachineNotify {

    private static final String PREFIX = "[SHANHAI-EDIT]";

    private ShanhaiRecipeMachineNotify() {
    }

    /**
     * 把"正在用这个配方类型的机器"的配方缓存标脏。
     *
     * @return 被通知的机器数（matched 与 dirty 相同；只统计真的调到的）
     */
    public static int markDirtyFor(MinecraftServer server, GTRecipeType type) {
        if (server == null || type == null) {
            return 0;
        }
        int scanned = 0;
        int matched = 0;
        int failed = 0;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getEntities().getAll()) {
                    scanned++;
                    if (!(e instanceof IRecipeLogicMachine machine)) {
                        continue;
                    }
                    if (!usesType(machine, type)) {
                        continue;
                    }
                    matched++;
                    try {
                        final RecipeLogic logic = machine.getRecipeLogic();
                        if (logic == null) {
                            continue;
                        }
                        logic.markLastRecipeDirty();       // 下一次查找重新选配方（不打断当前轮）
                        logic.updateTickSubscription();    // tick 订阅跟着新状态走
                    } catch (Throwable t) {
                        failed++;
                        ShanhaiMod.LOGGER.warn("{} recipe_machine_notify_failed type={} err={}",
                                PREFIX, type.registryName, t.toString());
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} recipe_machine_notify_scan_failed type={} err={}",
                    PREFIX, type.registryName, t.toString());
        }
        ShanhaiMod.LOGGER.info("{} recipe_machine_notify type={} scanned={} matched={} failed={} "
                        + "（重建索引之后通知机器重选配方：不做这一步，机器会一直按缓存的旧配方跑，"
                        + "用户看到的就是「删了还在跑、重进存档才停」）",
                PREFIX, type.registryName, scanned, matched, failed);
        return matched;
    }

    /** 这台机器用不用这个配方类型（多类型机器要逐个比）。 */
    private static boolean usesType(IRecipeLogicMachine machine, GTRecipeType type) {
        try {
            final GTRecipeType[] types = machine.getRecipeTypes();
            if (types != null) {
                for (GTRecipeType t : types) {
                    if (t == type || (t != null && t.registryName != null
                            && t.registryName.equals(type.registryName))) {
                        return true;
                    }
                }
                return false;
            }
            final GTRecipeType one = machine.getRecipeType();
            return one == type || (one != null && one.registryName != null
                    && one.registryName.equals(type.registryName));
        } catch (Throwable t) {
            return false;
        }
    }
}
