package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.shanhai.ShanhaiMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 事件登记面板的<b>服务端权威入口</b>（LDLib 的 {@code UIFactory}）。
 *
 * <h2>一、为什么必须走 UIFactory，不能"客户端 new 一个 ModularUI"</h2>
 * 本工程在同一个坑上付过一次账（见 {@code ShanhaiCreativeItemsFactory} / {@code HostPatternMoverFactory}
 * 的类注释）：纯客户端开的面板，LDLib 的控件动作在服务端只认
 * {@code player.containerMenu instanceof ModularUIContainer}，否则<b>直接丢弃、不报错</b>。
 * 走 UIFactory 时 LDLib 会在服务端建一份 UI、开一个真的 {@code ModularUIContainer}，
 * 客户端那份由 {@link #readHolderFromSyncData} 重建。
 *
 * <h2>二、🔴 全工程<b>只此一个</b> UIFactory（65 件共用一张面板）</h2>
 * 面板内容一份共用，靠 Holder 里的 {@code itemId} 区分"我在配哪一件"。
 * {@link #registerOnce()} 是幂等的（{@code FACTORIES.containsKey} 先查），
 * 且<b>只有一个装配点</b>：{@link ShanhaiRealityInputBootstrap}（另有开面板时兜一次，见 {@link #open}）。
 *
 * <h2>三、数据怎么走（<b>不自建网络包</b>）</h2>
 * <ul>
 *   <li><b>开界面</b>：{@code UIFactory#openUI} 自己发包，Holder 由
 *       {@link #writeHolderToSyncData} / {@link #readHolderFromSyncData} 带过去
 *       （只有 itemId + 两个预填串 + 状态行，几百字节；绝不塞整份 NBT）；</li>
 *   <li><b>输入框的文字</b>：LDLib 的 {@code TextFieldWidget} 自己走 client→server 同步
 *       （打字时 {@code writeClientAction(1, 文本)}，服务端那份控件的 {@code textResponder} 收到），
 *       所以面板里只要把 responder 指向 Holder 即可，<b>不需要我们的包</b>；</li>
 *   <li><b>点「确定」</b>：LDLib 的 {@code ButtonWidget} 自己走 client action
 *       （{@code ClickData#isRemote} 区分两侧，服务端那次才是真的写盘）；</li>
 *   <li><b>回执（状态行）</b>：{@code LabelWidget} 的既有 update-info 机制把服务端那一刻的
 *       字面串推给客户端（照 {@code HostPatternMoverConfirmPanel} 的做法）。</li>
 * </ul>
 */
public final class ShanhaiRealityInputFactory extends UIFactory<ShanhaiRealityInputFactory.Holder> {

    /** 工厂 id（注册表键，也是"开界面"包里的分派键）。 */
    public static final ResourceLocation ID = new ResourceLocation("shanhai", "reality_input");

    public static final ShanhaiRealityInputFactory INSTANCE = new ShanhaiRealityInputFactory();

    /**
     * 状态行的口径（<b>字面串，不走 lang</b>）。
     *
     * <p>🔴 为什么这一处<b>不能</b>用 {@code Component.translatable(...)}：状态行是<b>服务端权威</b>，
     * 它靠 LDLib {@code LabelWidget} 既有的 update-info 机制把服务端那一刻的字面串推给客户端
     * （照 {@code HostPatternMoverConfirmPanel.setState("§a已执行")} 的做法）。而服务端<b>没有语言表</b>
     * ⇒ 若这里放 lang 键，推过去的会是裸键或英文，把客户端正确的中文冲掉。
     * 选<b>字面串</b>则两侧渲染逐字节相同，"推"这件事不会造成任何差异。
     */
    public static final String STATUS_IDLE = "填好后点确定";
    public static final String STATUS_SAVED = "已登记";
    public static final String STATUS_FAILED = "登记失败（看日志）";
    public static final String STATUS_EMPTY_TARGET = "目标不能为空";
    public static final String STATUS_EMPTY_ACTION = "要干什么不能为空";

    private ShanhaiRealityInputFactory() {
        super(ID);
    }

    /** 幂等注册（两侧各调一次；开面板时也会再兜一次）。 */
    public static void registerOnce() {
        if (!UIFactory.FACTORIES.containsKey(ID)) {
            UIFactory.register(INSTANCE);
            ShanhaiMod.LOGGER.info("{} uifactory_registered id={} factories_now={}",
                    ShanhaiRealityInputStore.PREFIX, ID, UIFactory.FACTORIES.size());
        }
    }

    /**
     * 开面板（<b>只在服务端调用才有意义</b>）。
     *
     * @param itemId 完整物品 id（{@code shanhai:mc_livingattackevent}）
     */
    public static boolean open(ServerPlayer player, String itemId) {
        if (player == null || itemId == null || itemId.isEmpty()) {
            return false;
        }
        registerOnce();
        // 预填：这一件之前登记过就把上一次的「目标 / 动作」填回去（没登记过 = 空）
        final ShanhaiRealityInputStore.Entry existing = ShanhaiRealityInputStore.get(itemId);
        final Holder holder = new Holder(false, itemId,
                existing == null ? "" : existing.target(),
                existing == null ? "" : existing.action(),
                STATUS_IDLE);
        final boolean ok = INSTANCE.openUI(holder, player);
        ShanhaiMod.LOGGER.info("{} openUI ok={} item={} wired={} prefill_target={} prefill_action={}",
                ShanhaiRealityInputStore.PREFIX, ok, itemId, ShanhaiRealityInputStore.isWired(itemId),
                existing == null ? "" : existing.target(), existing == null ? "" : existing.action());
        return ok;
    }

    @Override
    protected ModularUI createUITemplate(Holder holder, Player player) {
        // ⚠️ 两侧各跑一次：只许建"两侧都安全"的件（面板自己不碰 @OnlyIn(CLIENT) 的类）
        return new ModularUI(ShanhaiRealityInputPanel.W, ShanhaiRealityInputPanel.H, holder, player)
                .widget(ShanhaiRealityInputPanel.create(holder, player));
    }

    @Override
    protected Holder readHolderFromSyncData(FriendlyByteBuf buf) {
        return Holder.read(buf);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buf, Holder holder) {
        (holder == null ? Holder.EMPTY : holder).write(buf);
    }

    // ================================================================ Holder

    /**
     * 面板持有者（两侧各一份；{@code remote} 由构造现场决定，不查全局态）。
     *
     * <p>只有三样数据：{@code itemId}（配哪一件）、两个<b>预填</b>串（上次登记的 target/action）、
     * 以及<b>服务端权威</b>的状态行。运行期的输入文字<b>不</b>经过 Holder ——
     * 它由 LDLib 的文本框自己同步（见类注释 §三）。
     */
    public static final class Holder implements IUIHolder {

        public static final Holder EMPTY = new Holder(true, "", "", "", STATUS_IDLE);

        private final boolean remote;
        private final String itemId;

        /** 预填串（两侧各一份副本；客户端那份会被面板每帧的"现取"覆盖成屏幕上的内容）。 */
        private String target;
        private String action;

        /** 服务端的动作结果（一行字面串；客户端那份靠 LabelWidget 的 update-info 拿到新值）。 */
        private String status;

        public Holder(boolean remote, String itemId, String target, String action, String status) {
            this.remote = remote;
            this.itemId = itemId == null ? "" : itemId;
            this.target = target == null ? "" : target;
            this.action = action == null ? "" : action;
            this.status = status == null ? "" : status;
        }

        public String itemId() {
            return this.itemId;
        }

        public String target() {
            return this.target;
        }

        public String action() {
            return this.action;
        }

        public String status() {
            return this.status;
        }

        /** 只改内存副本（服务端写状态；客户端写自己的缓冲区）。 */
        public void setTarget(String value) {
            this.target = value == null ? "" : value;
        }

        public void setAction(String value) {
            this.action = value == null ? "" : value;
        }

        public void setStatus(String value) {
            this.status = value == null ? "" : value;
        }

        @Override
        public ModularUI createUI(Player player) {
            // 真正的装配在 createUITemplate 里（UIFactory 那条路）
            return new ModularUI(ShanhaiRealityInputPanel.W, ShanhaiRealityInputPanel.H, this, player)
                    .widget(ShanhaiRealityInputPanel.create(this, player));
        }

        @Override
        public boolean isInvalid() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return this.remote;
        }

        @Override
        public void markAsDirty() {
            // 权威状态是 config/shanhai/mc_bottom_inputs.json；面板的刷新走 update info（见类注释）
        }

        // ---------------------------------------------------------------- 同步

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.itemId, 128);
            buf.writeUtf(this.target, ShanhaiRealityInputStore.MAX_TARGET_CHARS * 4 + 16);
            buf.writeUtf(this.action, ShanhaiRealityInputStore.MAX_ACTION_CHARS * 4 + 16);
            buf.writeUtf(this.status, 128);
        }

        static Holder read(FriendlyByteBuf buf) {
            return new Holder(true, buf.readUtf(128),
                    buf.readUtf(ShanhaiRealityInputStore.MAX_TARGET_CHARS * 4 + 16),
                    buf.readUtf(ShanhaiRealityInputStore.MAX_ACTION_CHARS * 4 + 16),
                    buf.readUtf(128));
        }

        @Override
        public String toString() {
            return "ShanhaiRealityInputHolder[" + (this.remote ? "remote" : "server")
                    + ", item=" + this.itemId + ", status=" + this.status + "]";
        }
    }
}
