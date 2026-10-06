package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 开面板的<b>服务端权威入口</b>。
 *
 * <h2>🔴 为什么必须走这条，而不是"在客户端 new 一个 ModularUI"</h2>
 * 本工程在这个坑上付过账（"幽灵容器"）。原话照抄任务书：
 * <blockquote>
 * 面板若走纯客户端路径打开，{@code CPacketUIClientAction} 在服务端只认
 * {@code player.containerMenu instanceof ModularUIContainer}，否则<b>直接 return</b>
 * ——所有控件动作被<b>静默丢弃，不报错不写日志</b>，界面看着完全正常。
 * </blockquote>
 * 走 {@code UIFactory} 的服务端路径时，LDLib 会在服务端建一份 UI、开一个
 * {@code ModularUIContainer}（它 {@code extends AbstractContainerMenu}，是真正的原版容器），
 * 客户端那份由 {@code readHolderFromSyncData} 重建 ⇒ <b>两侧都有，控件动作才有人接</b>。
 *
 * <h2>调用口径抄的是 GTCEu 自己</h2>
 * {@code /gtceu} 命令里那三条字节码（javap -c {@code GTCommands} 原文）：
 * <pre>
 *   getstatic  GTUIEditorFactory.INSTANCE
 *   getstatic  GTUIEditorFactory.INSTANCE
 *   invokevirtual GTUIEditorFactory.openUI:(Ljava/lang/Object;Lnet/minecraft/server/level/ServerPlayer;)Z
 * </pre>
 * 本类与之同构：{@code ShanhaiRecipeEditorFactory.INSTANCE.openUI(holder, serverPlayer)}。
 *
 * <h2>注册时机</h2>
 * {@code UIFactory.FACTORIES} 是一个静态表；客户端收到开面板包时要靠它按 id 找到本工厂
 * ⇒ <b>两侧都必须注册</b>。注册点 = mod 构造期的 {@code FMLCommonSetupEvent}
 * （见 {@link ShanhaiRecipeEditorBootstrap}）。
 */
public final class ShanhaiRecipeEditorFactory extends UIFactory<ShanhaiRecipeEditorHolder> {

    /** 工厂 id（注册表键，也是开面板包里的分派键）。 */
    public static final ResourceLocation ID = new ResourceLocation("shanhai", "recipe_editor");

    public static final ShanhaiRecipeEditorFactory INSTANCE = new ShanhaiRecipeEditorFactory();

    private ShanhaiRecipeEditorFactory() {
        super(ID);
    }

    /** 幂等注册（两侧各调一次；重复调不会覆盖别人的条目）。 */
    public static void registerOnce() {
        if (!UIFactory.FACTORIES.containsKey(ID)) {
            UIFactory.register(INSTANCE);
            com.shanhai.ShanhaiMod.LOGGER.info("{} uifactory_registered id={} factories_now={}",
                    ShanhaiRecipeOverrideStore.PREFIX, ID, UIFactory.FACTORIES.size());
        } else {
            com.shanhai.ShanhaiMod.LOGGER.info("{} uifactory_already_registered id={} factories_now={}",
                    ShanhaiRecipeOverrideStore.PREFIX, ID, UIFactory.FACTORIES.size());
        }
    }

    /** 打开面板。<b>只有服务端调用它才有意义</b>（客户端调会拿不到 ModularUIContainer）。 */
    public static boolean open(ShanhaiRecipeEditorHolder holder, ServerPlayer player) {
        // 🔴 开面板这一拍先把"账本里在生效的 id"发给这个客户端做对账：修的是
        //    「服务端没套用、客户端 JEI 还显示改过的」两边不一致（用户实测第 3 条症状）。
        try {
            ShanhaiJeiBridge.sendReconcile(player, ShanhaiRecipeOverrideStore.appliedIds());
        } catch (Throwable t) {
            com.shanhai.ShanhaiMod.LOGGER.error("{} reconcile_send_failed err={}",
                    ShanhaiRecipeOverrideStore.PREFIX, t.toString(), t);
        }
        final boolean ok = INSTANCE.openUI(holder, player);
        com.shanhai.ShanhaiMod.LOGGER.info("{} openUI holder={} ok={} player={}",
                ShanhaiRecipeOverrideStore.PREFIX, holder, ok, player.getName().getString());
        return ok;
    }

    // ⚠️ 不实现 IUIHolder 的 createUI(Player)：本工厂【只】承担 UIFactory 那三条
    //    （createUITemplate / readHolderFromSyncData / writeHolderToSyncData）。
    //    IUIHolder 由 holder 自己实现（LDLib 的 ModularUI 拿的是 holder）。

    static ModularUI buildUI(ShanhaiRecipeEditorHolder holder, Player player) {
        return new ModularUI(ShanhaiRecipeEditorPanel.W, ShanhaiRecipeEditorPanel.H, holder, player)
                .widget(new ShanhaiRecipeEditorPanel(holder, player));
    }

    // ------------------------------------------------------------ UIFactory 两侧装配

    @Override
    protected ModularUI createUITemplate(ShanhaiRecipeEditorHolder holder, Player player) {
        // ⚠️ 本方法【两侧各跑一次】（服务端直接建、客户端 readHolderFromSyncData 之后建）
        // ⇒ 里只许建"两侧都安全"的件，不许碰 @OnlyIn(CLIENT)，否则专用服务端会 NoClassDefFoundError。
        com.shanhai.ShanhaiMod.LOGGER.info("{} createUITemplate holder={} clientSide={}",
                ShanhaiRecipeOverrideStore.PREFIX, holder, player.level().isClientSide());
        return buildUI(holder, player);
    }

    @Override
    protected ShanhaiRecipeEditorHolder readHolderFromSyncData(FriendlyByteBuf buf) {
        final ResourceLocation itemId = ShanhaiRecipeEditorHolder.readNullable(buf);
        final int stage = buf.readVarInt();
        final ResourceLocation typeId = ShanhaiRecipeEditorHolder.readNullable(buf);
        final ResourceLocation recipeId = ShanhaiRecipeEditorHolder.readNullable(buf);
        // 本方法只在客户端跑 ⇒ remote = true（这就是"每个实例自己知道自己在哪一侧"的来源）。
        return new ShanhaiRecipeEditorHolder(itemId, stage, typeId, recipeId, true);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buf, ShanhaiRecipeEditorHolder holder) {
        ShanhaiRecipeEditorHolder.writeNullable(buf, holder.itemId);
        buf.writeVarInt(holder.stage);
        ShanhaiRecipeEditorHolder.writeNullable(buf, holder.typeId);
        ShanhaiRecipeEditorHolder.writeNullable(buf, holder.recipeId);
    }
}
