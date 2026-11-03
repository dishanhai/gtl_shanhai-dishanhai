package com.shanhai.common.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;

import com.shanhai.ShanhaiMod;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * 🔴 <b>「电超过 {@code long}」那条通道的<b>唯一</b>接线点</b>（模块侧与主机侧共用一份，防漂移）。
 *
 * <h2>它做三件事</h2>
 * <ol>
 *   <li>{@link #wrap(GTRecipe, BigInteger)} —— 把最终成品包成上游现成的
 *       {@code WirelessGTRecipe}，<b>并把 {@code IGTRecipe} 的那几个附加位逐个搬过去</b>（见下面「为什么必须搬」）；</li>
 *   <li>{@link #consume(IWirelessGTRecipe, IWirelessNetworkEnergyHandler, boolean)} —— 每 tick 扣电，
 *       走 {@code consumeEnergy(BigInteger)}，<b>不碰能源仓</b>；</li>
 *   <li>{@link #logBigint(...)} —— 限流 5 秒 1 行的证据行。</li>
 * </ol>
 *
 * <h2>🔴 为什么必须搬 {@code IGTRecipe} 的附加位（这一格最容易漏，漏了就是静默错数）</h2>
 * {@code IGTRecipe.of(recipe)} 的字节码是<b>纯 {@code checkcast}</b>
 * （{@code org.gtlcore.gtlcore.api.recipe.IGTRecipe#of}：偏移 {@code 0 aload_0 / 1 checkcast IGTRecipe / 4 areturn}）
 * ⇒ 那些位是<b>挂在 {@code GTRecipe} 实例上的 mixin 字段</b>，<b>不会</b>跟着构造器拷贝。
 * {@code WirelessGTRecipe(base, eu)} 的构造器只搬
 * {@code recipeType/id/inputs/outputs/tickInputs/tickOutputs/四张 chance 表/conditions/ingredientActions/data/duration/isFuel}
 * —— <b>{@code parallels}、{@code realParallels}、{@code batchSize}、{@code batchProcessed}、{@code hasTick} 全都不在里面</b>。
 * 不搬的后果（每一条都是"编译过、跑起来静默错"）：
 * <pre>
 *   parallels 丢 ⇒ {@code RecipeRunner#fillContent} 的 {@code ChanceLogic.roll(…, parallels, …)} 按 0 掷骰 ⇒ 概率产出凭空消失；
 *   batchSize / batchProcessed 丢 ⇒ 模块的「批处理（连跑 N 次）」在 bigint 档静默失效；
 *   realParallels 丢 ⇒ 追踪器/概率摊薄读到一个不同的数。
 * </pre>
 *
 * <h2>🔴 为什么符号是负的</h2>
 * 上游 {@code RecipeCalculationHelper#buildWirelessRecipe} 的值 = {@code totalEu.divide(duration).negate()}
 * （字节码 {@code 32 iload_3 / 34 BigInteger.valueOf / 37 divide / 40 negate}），
 * 而 {@code WirelessNetworkEnergyHandler#consumeEnergy(BigInteger)} 一路走到
 * {@code WirelessEnergyManager.addEUToGlobalEnergyMap}（{@code cur = cur.add(v); return cur.signum() >= 0}）
 * ⇒ <b>负 = 扣电</b>。本类照抄这个符号，不自造约定。
 */
public final class ShanhaiWirelessWrap {

    private ShanhaiWirelessWrap() {
    }

    /**
     * 把最终成品包成 {@code WirelessGTRecipe}，并把 {@link IGTRecipe} 的五个附加位逐个搬过去。
     *
     * @param base      最终成品（模块/主机尾链跑完之后的那一张）
     * @param euPerTick <b>每 tick 耗电</b>，<b>正数</b>（本方法内部取负，见类注释「为什么符号是负的」）
     * @return 包好的配方；{@code euPerTick} 为 null 时原样返回 {@code base}
     */
    public static @NotNull GTRecipe wrap(@NotNull GTRecipe base, @Nullable BigInteger euPerTick) {
        if (euPerTick == null || euPerTick.signum() == 0) {
            return base;
        }
        final WirelessGTRecipe wrapped = new WirelessGTRecipe(base, euPerTick.negate());
        // ── ① GTRecipe 自己的公开字段（构造器不搬）──
        //    parallels 是 int（gtlcore 的 setRealParallels 里 parallels = Ints.saturatedCast(p)），
        //    这里照抄基证的值，不做第二套饱和。
        wrapped.parallels = base.parallels;
        wrapped.ocTier = base.ocTier;
        // ── ② IGTRecipe 的附加位（mixin 字段，构造器绝对不搬）──
        try {
            final IGTRecipe src = IGTRecipe.of(base);
            final IGTRecipe dst = IGTRecipe.of(wrapped);
            dst.setHasTick(base.hasTick());
            dst.setRealParallels(src.getRealParallels());
            dst.setBatchSize(src.getBatchSize());
            dst.setBatchProcessed(src.isBatchProcessed());
            dst.setSubTickParallelized(src.isSubTickParallelized());
        } catch (Throwable t) {
            // 附加位搬不动 = 概率产出/批处理会在 bigint 档静默失真 ⇒ 必须喊出来（但不许把配方打死）。
            ShanhaiMod.LOGGER.error("[SHANHAI-INFPAR] WirelessGTRecipe 的 IGTRecipe 附加位搬运失败"
                    + "（概率产出/批处理可能在该档失真）：{}", t.toString());
        }
        return wrapped;
    }

    /**
     * 每 tick 扣电（<b>无线支</b>）。返回 {@code true} = 扣成功，机器正常推进进度；
     * {@code false} = 池子不够 ⇒ 调用方（父类 {@code handleMultipleRecipeWorking}）会自己走
     * 「{@code setWaiting(FAIL_NO_ENOUGH_EU_IN)} + {@code doDamping()}」那条既有语义，
     * <b>本类不许另造状态</b>。
     */
    public static boolean consume(@NotNull IWirelessGTRecipe recipe,
                                 @Nullable IWirelessNetworkEnergyHandler handler) {
        final BigInteger eu = recipe.getWirelessEuTickInputs();
        if (eu == null || eu.signum() == 0) {
            return true;
        }
        if (handler == null || !handler.isOnline()) {
            return false;
        }
        return handler.consumeEnergy(eu);
    }

    /**
     * 这张配方是不是"走无线 BigInt 扣电"的那一张。
     *
     * <p>⚠️ 判据必须是 {@code instanceof IWirelessGTRecipe} —— 与父类
     * {@code handleMultipleRecipeWorking} 的分流<b>同一个判据</b>（字节码 {@code 39 instanceof IWirelessGTRecipe}），
     * 否则我们会算出与真实扣电路径不同的结论。
     */
    public static boolean isWireless(@Nullable GTRecipe recipe) {
        return recipe instanceof IWirelessGTRecipe;
    }
}
