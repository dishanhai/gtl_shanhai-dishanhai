package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 把 {@link ShanhaiRecipeEditorFactory} 注册进 LDLib 的 {@code UIFactory.FACTORIES}。
 *
 * <h2>为什么必须两侧都注册、且必须在 mod 构造期附近</h2>
 * 客户端收到"开面板"包之后，是靠<b>包里的工厂 id</b>去 {@code FACTORIES} 里找工厂、
 * 再 {@code readHolderFromSyncData} 重建界面的。如果客户端那份没注册，包会被丢掉、
 * 界面上什么也不会出现 —— 而服务端日志里 {@code openUI} 仍然是成功的。
 * （这条正是"幽灵容器"那一族坑的同族：<b>失败在两侧看起来不一样</b>。）
 * ⇒ 注册点选 {@code FMLCommonSetupEvent}（客户端与专用服务端各跑一次，且在玩家进任何世界之前）。
 *
 * <h2>为什么用 {@code enqueueWork}</h2>
 * FML 的惯例：{@code FMLCommonSetupEvent} 的回调可能并发跑，凡是要动全局静态注册表的都排进主线程。
 * {@code UIFactory.FACTORIES} 就是全局静态表，所以照惯例排队。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiRecipeEditorBootstrap {

    private ShanhaiRecipeEditorBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ShanhaiRecipeEditorFactory.registerOnce();
            // 第二刀：JEI 热生效要的那条网络通道（本模组的第一条；客户端与专服都会跑这一行）
            ShanhaiJeiBridge.register();
            ShanhaiMod.LOGGER.info("{} bootstrap_done factories={} channel_ready={}",
                    ShanhaiRecipeOverrideStore.PREFIX,
                    com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size(),
                    ShanhaiJeiBridge.isReady());
            // 🆕 2026-10-06：把"哪些屏能新建配方"这条判据打成一行机器可读读数。
            //    它就是用户报的那一条（「从搜索 / 获取途径进去的类型页无法新增配方」）——
            //    纯函数、开机就跑、含 3 条负对照 ⇒ "修好了没有"在日志里一眼可判，不用进游戏。
            ShanhaiMod.LOGGER.info("{} {}",
                    ShanhaiRecipeOverrideStore.PREFIX,
                    ShanhaiRecipeEditorWorkspace.newRecipeGateSelfTest());
        });
    }
}
