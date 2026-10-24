package com.shanhai.add.gravity;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 重力模块的装配点（<b>两侧各跑一次</b>，在玩家进任何世界之前）。
 *
 * <h2>这里做四件事</h2>
 * <ol>
 *   <li>把旧的 {@code config/shanhai-common.toml [gravity] multiplier} <b>一次性</b>迁进
 *       {@code config/shanhai_add-common.toml}（{@code com.shanhai.add.config.AddGravityConfigMigration}，
 *       拆分设计 §4）；</li>
 *   <li>建同步通道（{@link ShanhaiGravityNetwork}）；</li>
 *   <li>跑一遍<b>开机自证</b>：正样本必须全绿、负对照必须被抓到 —— 一条机器可判的
 *       {@code SELFCHECK positivePASS=… negativeCaught=… PASS=…}；</li>
 *   <li>打一条 {@code bootstrap_done} 读数（通道是否就绪、17 个施加点、当前倍率）。</li>
 * </ol>
 *
 * <h2>为什么这里<b>不</b>读配置</h2>
 * 倍率只从<b>服务端</b>读（{@link ShanhaiGravityEvents#onServerStarted}）。
 * 客户端那份保持 1（安全默认）直到收到 {@code gravity_sync} 包 ——
 * 若客户端也读自己那份 {@code shanhai_add-common.toml}，多人时就会"服务端 0.5 / 客户端 1"直接橡皮筋。
 * 单机不受影响：集成服务器与客户端同 JVM，服务端那次赋值客户端直接看得见。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiGravityBootstrap {

    private ShanhaiGravityBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                // ① 旧配置一次性迁移（拆分前 [gravity] multiplier 在本体的 config/shanhai-common.toml 里）。
                //    ⚠️ 放在【读配置之前】、且必须在 common setup（那一刻配置spec 已加载：
                //    mod 构造器里 ConfigValue#get() 会抛 IllegalStateException）。
                //    迁移自身整段兜异常，失败只会打一行 warn ⇒ 不影响下面任何一步。
                com.shanhai.add.config.AddGravityConfigMigration.runOnce();
                ShanhaiGravityNetwork.register();
                final int failPositive = ShanhaiGravity.runSelfCheck(ShanhaiGravity::scaleFor, false);
                final int failMutant = ShanhaiGravity.runSelfCheck(ShanhaiGravity::mutantNoScale, false);
                final boolean ok = failPositive == 0 && failMutant > 0;
                final String line = "SELFCHECK positivePASS=" + (failPositive == 0)
                        + " negativeCaught=" + (failMutant > 0)
                        + " sites=" + ShanhaiGravity.SITE_BASES.length + " PASS=" + ok;
                if (ok) {
                    ShanhaiMod.LOGGER.info("{} {}", ShanhaiGravity.PREFIX, line);
                } else {
                    ShanhaiMod.LOGGER.error("{} {} positiveFailures={} mutantFailures={}",
                            ShanhaiGravity.PREFIX, line, failPositive, failMutant);
                }
                ShanhaiMod.LOGGER.info("{} bootstrap_done channel_ready={} sites={} mult={}",
                        ShanhaiGravity.PREFIX, ShanhaiGravityNetwork.isReady(),
                        ShanhaiGravity.SITE_BASES.length, ShanhaiGravity.fmt(ShanhaiGravity.get()));
            } catch (Throwable t) {
                // 装配失败绝不能让服务端起不来：退化后 G 恒等于 1（= 完全原版）
                ShanhaiGravity.set(ShanhaiGravity.VANILLA);
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}（已回退到原版重力）",
                        ShanhaiGravity.PREFIX, t.toString(), t);
            }
        });
    }
}
