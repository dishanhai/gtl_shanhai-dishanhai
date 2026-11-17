package com.shanhai.common.patternmover;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板的<b>服务端权威入口</b>（LDLib 的 {@code UIFactory}），三种形态。
 *
 * <h2>一、三形态（同一个 UIFactory id，靠 Holder 里的 {@code kind} 分派）</h2>
 * <pre>
 *   kind = 0  READ    Shift+右键空气 ⇒ 物品面板（分页列表 ＋ 取出 ＋ 清空物品）
 *   kind = 1  CONFIRM 右键主机      ⇒ 放置确认面板（三列信息 ＋ 确认放置/取消 ＋ 清空主机入口）
 *   kind = 2  CLEAR   确认面板里点「清空」⇒ 清空二次确认页（确认清空/取消）
 * </pre>
 * 🔴 <b>为什么不新开 UIFactory</b>：一个 {@code ResourceLocation} 一套
 * {@code readHolderFromSyncData/writeHolderToSyncData}，三形态共用一份同步格式
 * 反而<b>少两个注册点</b>、也少一处"客户端找不到工厂"的可能（本工程在
 * {@code ShanhaiRecipeEditorFactory} 上付过这个账）。代价是 Holder 里多一个 {@code kind}
 * 与两个可空的预览（见 {@link #writeHolderToSyncData}）。
 *
 * <h2>🔴 二、为什么必须走 UIFactory，而不是"客户端 new 一个 ModularUI"</h2>
 * 纯客户端开的面板，LDLib 的控件动作在服务端只认
 * {@code player.containerMenu instanceof ModularUIContainer}，否则<b>直接丢弃、不报错</b>
 * （第一刀类注释已记）。⇒ 所有按钮（确认/取消/取出/清空/翻页<b>除外</b>：
 * 翻页是纯界面状态、两侧各改自己那份）都<b>必须</b>有一个真的服务端容器，才可能拿到服务端那次回调。
 *
 * <h2>三、Holder 里带什么（几百字节，绝不带整份 NBT）</h2>
 * <pre>
 *   READ    : {@link HostPatternMoverPanelState}（块数 / 字节 / 组数 / 每组类型与块数 / 通配符三项计数）
 *   CONFIRM : {@link PlacePreview}（目标坐标 ＋ 新填/覆盖/跳过三类计数 ＋ 每台总成的摘要）
 *   CLEAR   : {@link ClearPreview}（目标坐标 ＋ 总成数 / 槽数 / 将清空的块数 ＋ 每台总成的摘要）
 * </pre>
 * 整份物品 NBT 最多 1.5 MiB，塞进开界面的包会顶到「单帧 ≤ 2,097,151 B」
 * （取证 §6.2 的两条字节码常量）⇒ 一个字节都不传。
 */
public final class HostPatternMoverFactory extends UIFactory<HostPatternMoverFactory.Holder> {

    /** 工厂 id（注册表键，也是"开界面"包里的分派键）。 */
    public static final ResourceLocation ID = new ResourceLocation("shanhai", "host_pattern_mover");

    public static final HostPatternMoverFactory INSTANCE = new HostPatternMoverFactory();

    /** 物品面板（分页列表 ＋ 取出 ＋ 清空物品）。 */
    public static final int KIND_READ = 0;

    /** 放置确认面板（右键主机时）。 */
    public static final int KIND_CONFIRM = 1;

    /** 清空二次确认页（放置确认面板里点「清空」时）。 */
    public static final int KIND_CLEAR = 2;

    private HostPatternMoverFactory() {
        super(ID);
    }

    /** 幂等注册（两侧各调一次；第一次开面板之前注册也来得及，见类注释 §二）。 */
    public static void registerOnce() {
        if (!UIFactory.FACTORIES.containsKey(ID)) {
            UIFactory.register(INSTANCE);
            HostPatternMoverLog.info("{} uifactory_registered id={} factories_now={}",
                    HostPatternMoverLog.PREFIX, ID, UIFactory.FACTORIES.size());
        }
    }

    /** 打开物品面板（<b>只在服务端调用才有意义</b>）。 */
    public static boolean open(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return false;
        }
        registerOnce();
        final HostPatternMoverPanelState state = HostPatternMoverPanelState.of(stack.getTag());
        final Holder holder = new Holder(false, KIND_READ, state, null, null);
        final boolean ok = INSTANCE.openUI(holder, player);
        HostPatternMoverLog.info("{} openUI ok={} kind=read player={} {}",
                HostPatternMoverLog.PREFIX, ok, player.getName().getString(), state.describe());
        return ok;
    }

    /** 打开放置确认面板（<b>只在服务端调用才有意义</b>）。 */
    public static boolean openConfirm(ServerPlayer player, PlacePreview preview) {
        if (player == null || preview == null) {
            return false;
        }
        registerOnce();
        final Holder holder = new Holder(false, KIND_CONFIRM, HostPatternMoverPanelState.EMPTY, preview, null);
        final boolean ok = INSTANCE.openUI(holder, player);
        HostPatternMoverLog.info("{} openUI ok={} kind=confirm player={} pos={} requested={} put={} skip={} "
                        + "assemblies={}", HostPatternMoverLog.PREFIX, ok, player.getName().getString(),
                preview.pos() == null ? "?" : preview.pos().toShortString(), preview.requested(),
                preview.putNew() + preview.putReplace(), preview.skip(), preview.infos().size());
        return ok;
    }

    /**
     * 打开<b>清空二次确认页</b>（<b>只在服务端调用才有意义</b>）。
     *
     * <p>用户明确要求「这个加一个额外确认」⇒ 清空主机这件事必须再点一次。
     */
    public static boolean openClear(ServerPlayer player, ClearPreview preview) {
        if (player == null || preview == null) {
            return false;
        }
        registerOnce();
        final Holder holder = new Holder(false, KIND_CLEAR, HostPatternMoverPanelState.EMPTY, null, preview);
        final boolean ok = INSTANCE.openUI(holder, player);
        HostPatternMoverLog.info("{} openUI ok={} kind=clear player={} pos={} assemblies={} blocks={}",
                HostPatternMoverLog.PREFIX, ok, player.getName().getString(),
                preview.pos() == null ? "?" : preview.pos().toShortString(),
                preview.assemblies(), preview.occupied());
        return ok;
    }

    @Override
    protected ModularUI createUITemplate(Holder holder, Player player) {
        // ⚠️ 两侧各跑一次：只许建"两侧都安全"的件（面板自己不碰 @OnlyIn(CLIENT) 的类）
        final int kind = holder == null ? KIND_READ : holder.kind();
        if (kind == KIND_CONFIRM) {
            return new ModularUI(HostPatternMoverConfirmPanel.W, HostPatternMoverConfirmPanel.H, holder, player)
                    .widget(HostPatternMoverConfirmPanel.create(holder, player));
        }
        if (kind == KIND_CLEAR) {
            return new ModularUI(HostPatternMoverClearPanel.W, HostPatternMoverClearPanel.H, holder, player)
                    .widget(HostPatternMoverClearPanel.create(holder, player));
        }
        return new ModularUI(HostPatternMoverPanel.W, HostPatternMoverPanel.H, holder, player)
                .widget(HostPatternMoverPanel.create(holder, player));
    }

    @Override
    protected Holder readHolderFromSyncData(FriendlyByteBuf buf) {
        return Holder.read(buf);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buf, Holder holder) {
        (holder == null ? Holder.EMPTY : holder).write(buf);
    }

    // ================================================================ 确认面板的数据（CONFIRM 形态）

    /**
     * 「按下去会发生什么」的一份<b>预览</b>（面板显示的就是它）。
     *
     * @param pos          目标主机坐标（确认时按这个坐标重新解析机器，见
     *                     {@link HostPatternMoverActions#confirmPlace}）
     * @param requested    物品里一共几块
     * @param putNew       计划填进空槽的
     * @param putReplace   计划覆盖同 id 的
     * @param skip         放不下的总数
     * @param skipUnknown  放不下：类型未识别
     * @param skipNoModule 放不下：没有模块支持这个类型（老主机上就是"不是当前选中的类型"）
     * @param skipNoSlot   放不下：没空槽 / 槽校验不过
     * @param infos        每台目标总成一行（坐标 ＋ 模块 id ＋ 槽数 ＋ 空槽数）
     */
    public record PlacePreview(BlockPos pos, int requested, int putNew, int putReplace, int skip,
                               int skipUnknown, int skipNoModule, int skipNoSlot,
                               List<HostPatternMoverTargets.AssemblyInfo> infos) {

        public static final PlacePreview EMPTY = new PlacePreview(BlockPos.ZERO, 0, 0, 0, 0, 0, 0, 0, List.of());

        public PlacePreview {
            infos = infos == null ? List.of() : List.copyOf(infos);
            pos = pos == null ? BlockPos.ZERO : pos;
        }

        public int put() {
            return this.putNew + this.putReplace;
        }

        public String describe() {
            return "requested=" + this.requested + " put=" + put() + "(+" + this.putNew
                    + "/~" + this.putReplace + ") skip=" + this.skip + "(unknown=" + this.skipUnknown
                    + " noModule=" + this.skipNoModule + " noSlot=" + this.skipNoSlot + ")";
        }

        void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeVarInt(this.requested);
            buf.writeVarInt(this.putNew);
            buf.writeVarInt(this.putReplace);
            buf.writeVarInt(this.skip);
            buf.writeVarInt(this.skipUnknown);
            buf.writeVarInt(this.skipNoModule);
            buf.writeVarInt(this.skipNoSlot);
            buf.writeVarInt(this.infos.size());
            for (HostPatternMoverTargets.AssemblyInfo info : this.infos) {
                (info == null ? HostPatternMoverTargets.AssemblyInfo.EMPTY : info).write(buf);
            }
        }

        static PlacePreview read(FriendlyByteBuf buf) {
            final BlockPos pos = buf.readBlockPos();
            final int requested = buf.readVarInt();
            final int putNew = buf.readVarInt();
            final int putReplace = buf.readVarInt();
            final int skip = buf.readVarInt();
            final int skipUnknown = buf.readVarInt();
            final int skipNoModule = buf.readVarInt();
            final int skipNoSlot = buf.readVarInt();
            final int n = Math.min(buf.readVarInt(), 512);
            final List<HostPatternMoverTargets.AssemblyInfo> infos = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                infos.add(HostPatternMoverTargets.AssemblyInfo.read(buf));
            }
            return new PlacePreview(pos, requested, putNew, putReplace, skip,
                    skipUnknown, skipNoModule, skipNoSlot, infos);
        }
    }

    // ================================================================ 清空确认页的数据（CLEAR 形态）

    /**
     * 「清空这台主机及其模块」的预览（二次确认页显示的就是它）。
     *
     * @param pos        目标主机坐标（确认时按这个坐标重新解析机器）
     * @param hostTag    {@code primordial_omega_engine} / {@code guixu_vision_core} / {@code "?"}
     * @param assemblies 收集到几台样板总成
     * @param slots      这些总成的槽数总和
     * @param occupied   其中<b>有样板</b>的格数（= 将清空的块数）
     * @param infos      每台总成一行（坐标 ＋ 模块 id ＋ 槽数 ＋ 空槽数）
     */
    public record ClearPreview(BlockPos pos, String hostTag, int assemblies, int slots, int occupied,
                               List<HostPatternMoverTargets.AssemblyInfo> infos) {

        public static final ClearPreview EMPTY = new ClearPreview(BlockPos.ZERO, "?", 0, 0, 0, List.of());

        public ClearPreview {
            infos = infos == null ? List.of() : List.copyOf(infos);
            pos = pos == null ? BlockPos.ZERO : pos;
            hostTag = hostTag == null || hostTag.isEmpty() ? "?" : hostTag;
        }

        public String describe() {
            return "assemblies=" + this.assemblies + " slots=" + this.slots + " occupied=" + this.occupied;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeUtf(this.hostTag, 64);
            buf.writeVarInt(this.assemblies);
            buf.writeVarInt(this.slots);
            buf.writeVarInt(this.occupied);
            buf.writeVarInt(this.infos.size());
            for (HostPatternMoverTargets.AssemblyInfo info : this.infos) {
                (info == null ? HostPatternMoverTargets.AssemblyInfo.EMPTY : info).write(buf);
            }
        }

        static ClearPreview read(FriendlyByteBuf buf) {
            final BlockPos pos = buf.readBlockPos();
            final String hostTag = buf.readUtf(64);
            final int assemblies = buf.readVarInt();
            final int slots = buf.readVarInt();
            final int occupied = buf.readVarInt();
            final int n = Math.min(buf.readVarInt(), 512);
            final List<HostPatternMoverTargets.AssemblyInfo> infos = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                infos.add(HostPatternMoverTargets.AssemblyInfo.read(buf));
            }
            return new ClearPreview(pos, hostTag, assemblies, slots, occupied, infos);
        }
    }

    // ================================================================ Holder

    /**
     * 面板持有者（两侧各一份；{@code remote} 由构造现场决定，不查全局态）。
     *
     * <p>🔴 它<b>可变</b>：服务端动作（取出 / 清空物品 / 清空主机）会换掉 {@link #state()} 或
     * {@link #status()}；面板每 tick 在服务端 {@code detectAndSendChanges} 里
     * <b>现读物品 NBT</b> 造一份状态，与"上次真的推出去的那一份"不同才用 {@code writeUpdateInfo}
     * 推给客户端（客户端 {@code readUpdateInfo} 落到它自己那份 Holder 上）⇒ <b>界面上的数字是活的</b>。
     * <p>客户端另有一条<b>镜像</b>兜底：面板每几帧自己把手上的物品 NBT 重读一遍，
     * 但<b>只有 {@code rev} 严格更大才许覆盖</b>（第五刀 ①；见 {@link HostPatternMoverPanel#updateScreen()}）
     * —— 否则"客户端手上还没同步的旧 NBT"会把动作回执盖回旧值（用户实测的那个回退）。
     * <p>⚠️ {@link #version()} / {@link #setState} 那套「谁改谁 ＋1」是第三刀留下的记账，
     * <b>面板的推送去重已经不再用它</b>（改成比"上次推出去的那一份"）—— 保留它是因为
     * 确认页 / 清空页的 {@code setStatus} 仍走同一条路。
     */
    public static final class Holder implements IUIHolder {

        public static final Holder EMPTY = new Holder(true, KIND_READ, HostPatternMoverPanelState.EMPTY,
                null, null);

        private final boolean remote;
        private final int kind;

        private HostPatternMoverPanelState state;
        private final PlacePreview preview;
        private final ClearPreview clearPreview;

        /** 服务端：动作结果（一行；客户端收到后显示在面板底部）。 */
        private String status = "";

        /** 服务端：每次状态变化 ＋1（面板靠它决定要不要推）。 */
        private int version;

        public Holder(boolean remote, int kind, HostPatternMoverPanelState state,
                      PlacePreview preview, ClearPreview clearPreview) {
            this.remote = remote;
            this.kind = kind;
            this.state = state == null ? HostPatternMoverPanelState.EMPTY : state;
            this.preview = preview == null ? PlacePreview.EMPTY : preview;
            this.clearPreview = clearPreview == null ? ClearPreview.EMPTY : clearPreview;
        }

        public int kind() {
            return this.kind;
        }

        public HostPatternMoverPanelState state() {
            return this.state;
        }

        public PlacePreview preview() {
            return this.preview;
        }

        public ClearPreview clearPreview() {
            return this.clearPreview;
        }

        public String status() {
            return this.status;
        }

        public int version() {
            return this.version;
        }

        /** 服务端：换掉状态包并 ＋1 版本（记账用；面板的推送判定已改成比"上次推出去的那一份"）。 */
        public void setState(HostPatternMoverPanelState next, String newStatus) {
            this.state = next == null ? HostPatternMoverPanelState.EMPTY : next;
            this.status = newStatus == null ? "" : newStatus;
            this.version++;
        }

        /** 服务端：只换状态行（例如"这一组已经没有样板了"这种没有数据变化的结果）。 */
        public void setStatus(String newStatus) {
            this.status = newStatus == null ? "" : newStatus;
            this.version++;
        }

        /**
         * 客户端（{@code readUpdateInfo} / 镜像重读那条路）：<b>只</b>换状态包，不动版本号。
         *
         * <p>为什么不复用 {@link #setState}：那个方法是"服务端改了数据"的语义（会 ＋1 版本），
         * 客户端跟着 ＋1 会让它自己以为自己又有新数据可推 ⇒ 这里显式分成两个方法，
         * 让"谁改谁 ＋1"变成结构性的。
         */
        public void replaceState(HostPatternMoverPanelState next) {
            this.state = next == null ? HostPatternMoverPanelState.EMPTY : next;
        }

        /** 客户端：只换状态行（同 {@link #replaceState} 的理由）。 */
        public void replaceStatus(String newStatus) {
            this.status = newStatus == null ? "" : newStatus;
        }

        @Override
        public ModularUI createUI(Player player) {
            // 真正的装配在 createUITemplate 里（UIFactory 那条路）
            if (this.kind == KIND_CONFIRM) {
                return new ModularUI(HostPatternMoverConfirmPanel.W, HostPatternMoverConfirmPanel.H, this, player)
                        .widget(HostPatternMoverConfirmPanel.create(this, player));
            }
            if (this.kind == KIND_CLEAR) {
                return new ModularUI(HostPatternMoverClearPanel.W, HostPatternMoverClearPanel.H, this, player)
                        .widget(HostPatternMoverClearPanel.create(this, player));
            }
            return new ModularUI(HostPatternMoverPanel.W, HostPatternMoverPanel.H, this, player)
                    .widget(HostPatternMoverPanel.create(this, player));
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
            // 面板的刷新走 writeUpdateInfo（见类注释），不依赖 dirty 标记
        }

        // ---------------------------------------------------------------- 同步

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.kind);
            this.state.write(buf);
            buf.writeUtf(this.status == null ? "" : this.status, 512);
            // 只有对应形态才写预览（另外两态只写一个 false，省字节）
            if (this.kind == KIND_CONFIRM) {
                buf.writeBoolean(true);
                this.preview.write(buf);
            } else {
                buf.writeBoolean(false);
            }
            if (this.kind == KIND_CLEAR) {
                buf.writeBoolean(true);
                this.clearPreview.write(buf);
            } else {
                buf.writeBoolean(false);
            }
        }

        static Holder read(FriendlyByteBuf buf) {
            final int kind = buf.readVarInt();
            final HostPatternMoverPanelState state = HostPatternMoverPanelState.read(buf);
            final String status = buf.readUtf(512);
            final PlacePreview preview = buf.readBoolean() ? PlacePreview.read(buf) : null;
            final ClearPreview clearPreview = buf.readBoolean() ? ClearPreview.read(buf) : null;
            final Holder holder = new Holder(true, kind, state, preview, clearPreview);
            holder.status = status;
            return holder;
        }

        @Override
        public String toString() {
            return "HostPatternMoverHolder[" + (this.remote ? "remote" : "server") + ", kind=" + this.kind
                    + ", " + this.state.describe() + ", clearBlocks=" + this.clearPreview.occupied() + "]";
        }
    }
}
