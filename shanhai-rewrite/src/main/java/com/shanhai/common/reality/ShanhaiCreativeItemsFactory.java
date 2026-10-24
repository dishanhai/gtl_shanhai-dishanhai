package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 「自己新增物品」面板的<b>服务端权威入口</b>（LDLib 的 {@code UIFactory}）。
 *
 * <h2>🔴 为什么必须走 UIFactory，而不是"客户端 new 一个 ModularUI"</h2>
 * 本工程在这个坑上付过账（见 {@code ShanhaiRecipeEditorFactory} 的类注释）：
 * 纯客户端开的面板，LDLib 的控件动作在服务端只认 {@code player.containerMenu instanceof
 * ModularUIContainer}，否则<b>直接丢弃、不报错</b>。走 UIFactory 时 LDLib 会在服务端建一份 UI、
 * 开一个真的 {@code ModularUIContainer}，客户端那份由 {@code readHolderFromSyncData} 重建。
 *
 * <h2>持有者（holder）里带什么</h2>
 * 什么都不带（只有一个 {@code remote} 位）。本面板的编辑状态全在<b>客户端</b>，
 * 落盘走 {@link ShanhaiCreativeItemsNetwork}（C2S），所以不需要把数据塞进同步包。
 */
public final class ShanhaiCreativeItemsFactory extends UIFactory<ShanhaiCreativeItemsFactory.Holder> {

    /** 工厂 id（注册表键，也是"开界面"包里的分派键）。 */
    public static final ResourceLocation ID = new ResourceLocation("shanhai", "creative_items");

    public static final ShanhaiCreativeItemsFactory INSTANCE = new ShanhaiCreativeItemsFactory();

    private ShanhaiCreativeItemsFactory() {
        super(ID);
    }

    /** 幂等注册（两侧各调一次）。 */
    public static void registerOnce() {
        if (!UIFactory.FACTORIES.containsKey(ID)) {
            UIFactory.register(INSTANCE);
            com.shanhai.ShanhaiMod.LOGGER.info("{} uifactory_registered id={} factories_now={}",
                    ShanhaiRealityItemTable.PREFIX, ID, UIFactory.FACTORIES.size());
        } else {
            com.shanhai.ShanhaiMod.LOGGER.info("{} uifactory_already_registered id={}",
                    ShanhaiRealityItemTable.PREFIX, ID);
        }
    }

    /** 打开面板（<b>只在服务端调用才有意义</b>）。 */
    public static boolean open(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        // 多人时客户端本地那份表可能是旧的 ⇒ 开界面之前先对一次账（单人同 JVM，这一步也无害）
        ShanhaiCreativeItemsNetwork.syncTo(player);
        final boolean ok = INSTANCE.openUI(new Holder(false), player);
        com.shanhai.ShanhaiMod.LOGGER.info("{} openUI ok={} player={} defined={}",
                ShanhaiRealityItemTable.PREFIX, ok, player.getName().getString(),
                ShanhaiRealityItemTable.definedCount());
        return ok;
    }

    @Override
    protected ModularUI createUITemplate(Holder holder, Player player) {
        // ⚠️ 本方法【两侧各跑一次】：只许建"两侧都安全"的件（面板自己不碰 @OnlyIn(CLIENT) 的类）
        // 🆕 2026-10-17：外面多包了一层三页签容器（新增 / 修改 / 删除）。
        //   「新增」那一页就是原来那个 ShanhaiCreativeItemsPanel 实例，一个字节没动，只是摆到
        //   页签容器里 y=TAB_H 的位置上（LDLib 的绝对＝父组＋自身，所以整页自动跟着下移）。
        return new ModularUI(ShanhaiItemStudioPanel.W, ShanhaiItemStudioPanel.H, holder, player)
                .widget(new ShanhaiItemStudioPanel(holder, player));
    }

    @Override
    protected Holder readHolderFromSyncData(FriendlyByteBuf buf) {
        buf.readVarInt();
        return new Holder(true);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buf, Holder holder) {
        buf.writeVarInt(0);
    }

    /** 面板的持有者（两侧各建一个；{@code remote} 由构造现场决定，不查全局态）。 */
    public static final class Holder implements IUIHolder {

        private final boolean remote;

        public Holder(boolean remote) {
            this.remote = remote;
        }

        @Override
        public ModularUI createUI(Player player) {
            // 真正的装配在 createUITemplate 里（UIFactory 那条路）
            return new ModularUI(ShanhaiItemStudioPanel.W, ShanhaiItemStudioPanel.H, this, player)
                    .widget(new ShanhaiItemStudioPanel(this, player));
        }

        @Override
        public boolean isInvalid() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return remote;
        }

        @Override
        public void markAsDirty() {
            // 没有需要回写的脏状态：权威状态是 config/shanhai/creative_items.json
        }

        @Override
        public String toString() {
            return "ShanhaiCreativeItemsHolder[remote=" + remote + "]";
        }
    }
}
