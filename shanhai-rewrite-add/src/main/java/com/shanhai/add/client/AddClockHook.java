package com.shanhai.add.client;

import com.mojang.logging.LogUtils;
import com.shanhai.add.client.time.ShanhaiClientClock;
import com.shanhai.add.time.ShanhaiClockRules;
import com.shanhai.api.ShanhaiClockHook;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * 扩展侧给本体接缝 {@link ShanhaiClockHook} 的实现（<b>客户端专用</b>）。
 *
 * <h2>它只是把两个静态调用搬了个门</h2>
 * 拆分前本体两个渲染器直接调：
 * <pre>
 *   ShanhaiClientClock.freezeSmoothTick(live)                                   // 环/恒星动画
 *   ShanhaiClockRules.renderPartialTick(ShanhaiClientClock.paused(), vanilla)   // 特效 α
 * </pre>
 * 拆分后这两个类整块搬进扩展（{@code com.shanhai.add.client.time} / {@code com.shanhai.add.time}），
 * 本体不再认它们 ⇒ 由本类把同样两个调用重新接回本体那个零 MC 依赖的接缝。
 * <b>语义一个字都没变</b>：暂停时沿用锁住的那一帧 / 暂停时 α=0；未暂停时原值透传。
 *
 * <h2>🔴 为什么整类 {@code @OnlyIn(Dist.CLIENT)}，且装配点要包在 dist 判定里</h2>
 * 它引用 {@code net.minecraft.client.*}（经 {@link ShanhaiClientClock}）。专用服务端上
 * 只要这个类<b>被加载</b>就会 {@code NoClassDefFoundError} ⇒ 装配点必须是
 * {@code if (FMLEnvironment.dist == Dist.CLIENT)}，让常量池里的这条引用在服务端<b>永远不解析</b>
 * （与本体 {@code ShanhaiMod} 注册客户端配置那段的写法逐字同款）。
 *
 * <h2>为什么要 try/catch 到这么细</h2>
 * 两个方法都跑在<b>渲染线程的每帧路径</b>上：这里抛一次，代价是客户端渲染崩。
 * ⇒ 任何异常都退回"原值透传"（= 与没装时钟时完全一样），并只由被调方自己记日志。
 */
@OnlyIn(Dist.CLIENT)
public final class AddClockHook implements ShanhaiClockHook.Impl {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 幂等闩：重复调用只装一次。 */
    private static boolean installed;

    private AddClockHook() {}

    /**
     * 把本类装进本体的接缝（由扩展的 {@code @Mod} 构造器在 {@code Dist.CLIENT} 分支里调用）。
     *
     * @return {@code true} = 装上了（构造器的开机自证行会打 {@code clock_hook=true}）
     */
    public static boolean install() {
        if (installed) {
            return true;
        }
        installed = true;
        ShanhaiClockHook.install(new AddClockHook());
        LOGGER.info("[SHANHAI-ADD] clock_hook installed（本体渲染器 → ShanhaiClockHook → "
                + "ShanhaiClientClock.freezeSmoothTick / ShanhaiClockRules.renderPartialTick）");
        return true;
    }

    /** 连续渲染时钟：暂停 ⇒ 沿用锁住的那一帧；未暂停 ⇒ 原值。异常 ⇒ 原值。 */
    @Override
    public float smoothTick(float live) {
        try {
            return ShanhaiClientClock.freezeSmoothTick(live);
        } catch (Throwable t) {
            return live;
        }
    }

    /** 世界动画 α：暂停 ⇒ 0；未暂停 ⇒ 原值。异常 ⇒ 原值。 */
    @Override
    public float partialTick(float vanilla) {
        try {
            return ShanhaiClockRules.renderPartialTick(ShanhaiClientClock.paused(), vanilla);
        } catch (Throwable t) {
            return vanilla;
        }
    }
}
