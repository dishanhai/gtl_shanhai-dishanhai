package com.shanhai.common.reality;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.itemedit.ShanhaiItemOverrideTable;
import com.shanhai.common.itemedit.ShanhaiItemRemovalOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 「自己新增物品 / 修改已有物品 / 删除抹除物品」三页签面板的<b>唯一一条网络通道</b>
 * （<b>五条消息</b>；前两条是 2026-10-17 之前就验过的，后三条是那次之后为新增的两页补的）。
 *
 * <h2>为什么必须走网络（而不是"客户端直接写文件"）</h2>
 * ① 写盘的是<b>服务端权威</b>的状态：<b>多人</b>时客户端的 {@code config/} 与服务端读的不是同一份，
 *    客户端直接写等于"自己骗自己"（第 ⑩ 条：不做多人同步，但别把 SinglePlayer 假设写死）；
 * ② 服务端自己也要立刻知道"这一格定义了没有"（创造栏内容、命令、配方校验都读它）；
 * ③ 🆕 影响面报告<b>只能</b>在服务端算（{@code impact()} 要 {@code MinecraftServer} 的配方表；
 *    客户端那份算出来全是 0，那就是假读数）。
 *
 * <h2>五条消息</h2>
 * <pre>
 *   id=0  C2S  save       ：槽位 + 名字 + 图标 + 堆叠 + 稀有度 + 提示   —— 「新增」页点「保存」
 *   id=1  S2C  sync       ：整表 JSON                                  —— 服务端改完之后广播
 *   id=2  C2S  override   ：id + 名字 + 描述 + lore_mode + 堆叠         —— 「修改」页点「保存」
 *   id=3  C2S  removal    ：id + 动作(藏/取消藏/抹除/撤回)              —— 「删除」页三个按钮
 *   id=4  C2S  impact_req ：id                                        —— 「删除」页选中/「看影响面」
 *   id=5  S2C  impact_res ：id + 影响面五个数 + 方块计数                —— 服务端算完回填
 * </pre>
 * <b>没有"请求打开界面"这条消息</b>：两条入口（全息板 / 指令）本来就在服务端那一侧动作
 * （全息板走 {@code ShanhaiHoloMenuNetwork} 的既有 C2S，指令直接跑在服务端）。
 *
 * <h2>🔴 服务端不信任客户端</h2>
 * 客户端发来的载荷只当"字段值"用：槽位越界 / 名字过长 / 图标串非法 / 堆叠越界
 * 一律在服务端再夹一次（{@link ShanhaiRealityItemTable#apply} 里那一套），并且
 * <b>门槛再判一次</b>（身上必须有创始现实修改模块）。🆕 后三条同理：
 * {@code shanhai:reality_item_*} 这 128 个壳在服务端再拒一次；「抹除」在没有影响面记录时再拒一次
 * （同 {@code /shanhai item remove} 的那道闸门）。
 */
public final class ShanhaiCreativeItemsNetwork {

    public static final String PREFIX = "[SHANHAI-REALITY]";

    private static final String CHANNEL_NAME = "creative_items";
    /**
     * 🔴 2026-10-17 由 "1" 提到 "2"：同一个通道上多了三条 C2S ＋ 一条 S2C。
     * 版本号一致才握手 ⇒ 新旧 jar 混装会<b>拒连</b>，而不是把消息 id 对错位（对错位是静默的坏）。
     */
    private static final String CHANNEL_VERSION = "2";

    private static final int ID_C2S_SAVE = 0;
    private static final int ID_S2C_SYNC = 1;
    /** 🆕 「修改」页：设这一条覆盖项（名字 / 描述 / lore_mode / 堆叠）。 */
    private static final int ID_C2S_OVERRIDE = 2;
    /** 🆕 「删除」页：藏 / 取消藏 / 抹除 / 撤回。 */
    private static final int ID_C2S_REMOVAL = 3;
    /** 🆕 「删除」页：请求影响面（服务端算）。 */
    private static final int ID_C2S_IMPACT = 4;
    /** 🆕 影响面结果（回填给请求的那一个玩家）。 */
    private static final int ID_S2C_IMPACT = 5;

    /** 「删除」页的四个动作（{@link #requestRemoval} 的第二个参数）。 */
    public static final int ACTION_HIDE_ON = 0;
    public static final int ACTION_HIDE_OFF = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_RESTORE = 3;

    /**
     * 本会话里"这个玩家为这个 id 请求过影响面"的记录 —— 「抹除」的前置闸门
     * （与 {@code ShanhaiItemOverrideCommand#IMPACT_SEEN} 同一条规矩，只是按玩家分开记）。
     */
    private static final java.util.Set<String> IMPACT_SEEN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static SimpleChannel channel;
    private static boolean registered;

    private ShanhaiCreativeItemsNetwork() {}

    /** 注册（两侧各一次；重复调用不会重复注册）。由 {@link ShanhaiCreativeItemsBootstrap} 调。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(ID_C2S_SAVE, SaveMessage.class,
                    SaveMessage::encode, SaveMessage::decode, SaveMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_S2C_SYNC, SyncMessage.class,
                    SyncMessage::encode, SyncMessage::decode, SyncMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            // 🆕 2026-10-17：三页签面板的另外两页
            channel.registerMessage(ID_C2S_OVERRIDE, OverrideMessage.class,
                    OverrideMessage::encode, OverrideMessage::decode, OverrideMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_C2S_REMOVAL, RemovalMessage.class,
                    RemovalMessage::encode, RemovalMessage::decode, RemovalMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_C2S_IMPACT, ImpactRequestMessage.class,
                    ImpactRequestMessage::encode, ImpactRequestMessage::decode, ImpactRequestMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_S2C_IMPACT, ImpactResultMessage.class,
                    ImpactResultMessage::encode, ImpactResultMessage::decode, ImpactResultMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            registered = true;
            ShanhaiMod.LOGGER.info("{} net_channel_registered name={} version={} c2s=[save,override,removal,impact] "
                            + "s2c=[sync,impact_result]",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION);
        } catch (Throwable t) {
            // 通道建不起来不能让服务端起不来（界面的"保存"会退化成只改客户端内存并打 ERROR）
            ShanhaiMod.LOGGER.error("{} net_channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    /** 客户端：把"这一格该是什么样"发到服务端（服务端才是落盘的那一侧）。 */
    public static void requestSave(int slot, String name, String icon, int maxStack, String rarity, String lore) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.error("{} save_net_skip reason=channel_not_ready slot={}", PREFIX, slot + 1);
            return;
        }
        try {
            channel.sendToServer(new SaveMessage(slot, name, icon, maxStack, rarity, lore));
            ShanhaiMod.LOGGER.info("{} save_sent slot={} name_len={} icon={} stack={} rarity={}",
                    PREFIX, slot + 1, name == null ? 0 : name.length(), icon, maxStack, rarity);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} save_send_failed slot={} err={}", PREFIX, slot + 1, t.toString(), t);
        }
    }

    /** 服务端：把当前整表推给一个玩家（开界面时先对一次账）。 */
    public static void syncTo(ServerPlayer player) {
        if (!isReady() || player == null) {
            return;
        }
        try {
            channel.send(PacketDistributor.PLAYER.with(() -> player),
                    new SyncMessage(ShanhaiRealityItemTable.toJsonString()));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sync_send_failed player={} err={}",
                    PREFIX, player.getName().getString(), t.toString(), t);
        }
    }

    /** 服务端：改完表之后广播给所有在线客户端（单人也走这条，客户端那一份才会跟着变）。 */
    public static void syncToAll(MinecraftServer server) {
        if (!isReady() || server == null) {
            return;
        }
        try {
            final String json = ShanhaiRealityItemTable.toJsonString();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                channel.send(PacketDistributor.PLAYER.with(() -> p), new SyncMessage(json));
            }
            ShanhaiMod.LOGGER.info("{} sync_broadcast players={} bytes={} defined={}",
                    PREFIX, server.getPlayerList().getPlayerCount(), json.length(),
                    ShanhaiRealityItemTable.definedCount());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sync_broadcast_failed err={}", PREFIX, t.toString(), t);
        }
    }

    // ------------------------------------------------------------------ 🆕 三页签面板：修改 / 删除

    /**
     * 「修改」页：把这一条覆盖项的整包字段发到服务端（服务端校验 ＋ 落盘）。
     *
     * @param maxStack 0 = 不改堆叠（不是"改成 0"）
     */
    public static void requestOverride(String id, String name, String lore, String loreMode, int maxStack) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.error("{} override_net_skip reason=channel_not_ready id={}", PREFIX, id);
            return;
        }
        try {
            channel.sendToServer(new OverrideMessage(id, name, lore, loreMode, maxStack, false));
            ShanhaiMod.LOGGER.info("{} override_sent id={} name_len={} lore_len={} mode={} stack={}",
                    PREFIX, id, name == null ? 0 : name.length(), lore == null ? 0 : lore.length(),
                    loreMode, maxStack);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} override_send_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }

    /**
     * 「修改」页的「清除（恢复原样）」：<b>删掉这一条覆盖</b>。
     *
     * <p>🔴 必须走服务端：{@code ShanhaiItemOverrideTable.clear(...)} 会<b>写文件</b>，
     * 在客户端那份上调等于只改客户端自己的 config（多人时就是自己骗自己）。
     */
    public static void requestClear(String id) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.error("{} clear_net_skip reason=channel_not_ready id={}", PREFIX, id);
            return;
        }
        try {
            channel.sendToServer(new OverrideMessage(id, "", "", "", 0, true));
            ShanhaiMod.LOGGER.info("{} clear_sent id={}", PREFIX, id);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} clear_send_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }

    /** 「删除」页：藏 / 取消藏 / 抹除 / 撤回（服务端执行，执行面全在 {@code ShanhaiItemRemovalOps}）。 */
    public static void requestRemoval(String id, int what) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.error("{} removal_net_skip reason=channel_not_ready id={}", PREFIX, id);
            return;
        }
        try {
            channel.sendToServer(new RemovalMessage(id, what));
            ShanhaiMod.LOGGER.info("{} removal_sent id={} what={}", PREFIX, id, what);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} removal_send_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }

    /** 「删除」页：请服务端算影响面（客户端那份算不出来，那是假读数）。 */
    public static void requestImpact(String id) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.error("{} impact_net_skip reason=channel_not_ready id={}", PREFIX, id);
            return;
        }
        try {
            channel.sendToServer(new ImpactRequestMessage(id));
            ShanhaiMod.LOGGER.info("{} impact_request_sent id={}", PREFIX, id);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} impact_request_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }

    /**
     * 影响面结果（服务端 → 客户端）。{@code blockTotal < 0} = 不是方块。
     *
     * <p>数字全部来自 {@code ShanhaiItemRemovalOps.impact(...)}／{@code countBlocks(...)} 的既有返回值，
     * 界面只负责显示，不重算。
     */
    public record ImpactResult(String id, boolean registered, boolean isBlock,
                               int outVanilla, int outGt, int inVanilla, int inGt, int soleOutput,
                               int blockTotal, int blockChunks, int blockRadius, boolean blockPartial) {

        public String oneLine() {
            return "id=" + id + " outV=" + outVanilla + " outG=" + outGt
                    + " inV=" + inVanilla + " inG=" + inGt + " sole=" + soleOutput
                    + " block=" + blockTotal + " chunks=" + blockChunks
                    + " radius=" + blockRadius + " partial=" + blockPartial;
        }
    }

    // ------------------------------------------------------------------ 消息

    /** C2S：保存一个槽位。 */
    public static final class SaveMessage {

        private final int slot;
        private final String name;
        private final String icon;
        private final int maxStack;
        private final String rarity;
        private final String lore;

        SaveMessage(int slot, String name, String icon, int maxStack, String rarity, String lore) {
            this.slot = slot;
            this.name = name == null ? "" : name;
            this.icon = icon == null ? "" : icon;
            this.maxStack = maxStack;
            this.rarity = rarity == null ? "" : rarity;
            this.lore = lore == null ? "" : lore;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(slot);
            buf.writeUtf(cap(name, ShanhaiRealityItemTable.MAX_NAME_CHARS + 8), 512);
            buf.writeUtf(cap(icon, ShanhaiRealityItemTable.MAX_ICON_CHARS + 8), 512);
            buf.writeVarInt(maxStack);
            buf.writeUtf(cap(rarity, 24), 64);
            buf.writeUtf(cap(lore, ShanhaiRealityItemTable.MAX_LORE_CHARS + 8), 2048);
        }

        static SaveMessage decode(FriendlyByteBuf buf) {
            return new SaveMessage(buf.readVarInt(), buf.readUtf(512), buf.readUtf(512),
                    buf.readVarInt(), buf.readUtf(64), buf.readUtf(2048));
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                final ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                if (!ShanhaiRealityItemTable.playerHasModule(player)) {
                    // 🔴 门槛在服务端再判一次（客户端那份可能是改过的，也可能是模块刚被丢掉）
                    ShanhaiMod.LOGGER.warn("{} save_rejected reason=no_module slot={} player={}",
                            PREFIX, this.slot + 1, player.getName().getString());
                    player.displayClientMessage(Component.literal(ShanhaiRealityItemTable.moduleRequiredText()), false);
                    return;
                }
                final boolean ok = ShanhaiRealityItemTable.apply(this.slot, this.name, this.icon,
                        this.maxStack, this.rarity, this.lore);
                if (!ok) {
                    player.displayClientMessage(Component.literal("§c保存失败（看日志里的 store_write_failed）"), false);
                    return;
                }
                ShanhaiCreativeItemsNetwork.syncToAll(player.getServer());
            });
            context.setPacketHandled(true);
        }

        private static String cap(String s, int max) {
            if (s == null) {
                return "";
            }
            return s.length() > max ? s.substring(0, max) : s;
        }
    }

    /** S2C：整表。 */
    public static final class SyncMessage {

        private final String json;

        SyncMessage(String json) {
            this.json = json == null ? "{}" : json;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json, 262144);
        }

        static SyncMessage decode(FriendlyByteBuf buf) {
            return new SyncMessage(buf.readUtf(262144));
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ShanhaiRealityItemTable.applyFromJsonString(json);
                // ⚠️ 这半句只在客户端执行（本消息方向是 PLAY_TO_CLIENT）⇒ 专服不会加载客户端类
                com.shanhai.client.reality.ShanhaiRealityClientRefresh.markDirty("sync");
                ShanhaiMod.LOGGER.info("{} sync_applied defined={} version={}",
                        PREFIX, ShanhaiRealityItemTable.definedCount(), ShanhaiRealityItemTable.version());
            });
            context.setPacketHandled(true);
        }
    }

    // ================================================================== 🆕 修改 / 删除（2026-10-17）

    /** C2S：「修改」页的一条覆盖项（名字 / 描述 / lore_mode / 堆叠）。 */
    public static final class OverrideMessage {

        private final String id;
        private final String name;
        private final String lore;
        private final String loreMode;
        private final int maxStack;
        /** {@code true} = 这次是「清除（恢复原样）」（把这一条覆盖整个删掉），其余字段一律不看。 */
        private final boolean clear;

        OverrideMessage(String id, String name, String lore, String loreMode, int maxStack, boolean clear) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.lore = lore == null ? "" : lore;
            this.loreMode = loreMode == null ? "" : loreMode;
            this.maxStack = maxStack;
            this.clear = clear;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(capStr(id, 160), 512);
            buf.writeUtf(capStr(name, ShanhaiItemOverrideTable.MAX_NAME_CHARS + 8), 512);
            // ⚠️ writeUtf 的上限是【UTF-8 字节数】，中文一个字 3 字节 ⇒ 这里给 8192，别卡在 2048
            buf.writeUtf(capStr(lore, ShanhaiItemOverrideTable.MAX_LORE_CHARS + 8), 8192);
            buf.writeUtf(capStr(loreMode, 24), 64);
            buf.writeVarInt(maxStack);
            buf.writeBoolean(clear);
        }

        static OverrideMessage decode(FriendlyByteBuf buf) {
            return new OverrideMessage(buf.readUtf(512), buf.readUtf(512),
                    buf.readUtf(8192), buf.readUtf(64), buf.readVarInt(), buf.readBoolean());
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                final ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                if (!ShanhaiRealityItemTable.playerHasModule(player)) {
                    player.displayClientMessage(Component.literal(
                            ShanhaiRealityItemTable.moduleRequiredText()), false);
                    ShanhaiMod.LOGGER.warn("{} override_rejected reason=no_module id={} player={}",
                            PREFIX, this.id, player.getName().getString());
                    return;
                }
                final ResourceLocation rid = ResourceLocation.tryParse(this.id);
                if (rid == null) {
                    player.displayClientMessage(Component.literal("§c物品 id 不合法：" + this.id), false);
                    return;
                }
                if (ShanhaiItemOverrideTable.isReserved(rid)) {
                    // 🔴 128 个壳在服务端再拒一次（界面上也灰字提示了）
                    player.displayClientMessage(Component.literal("§c" + rid
                            + " 是「现实物品」壳（shanhai:reality_item_1..128），不吃覆盖"), false);
                    ShanhaiMod.LOGGER.warn("{} override_rejected reason=reserved_reality_shell id={}", PREFIX, rid);
                    return;
                }
                ShanhaiItemOverrideTable.refreshNow();
                if (this.clear) {
                    final boolean cleared = ShanhaiItemOverrideTable.clear(rid);
                    player.displayClientMessage(Component.literal(cleared
                            ? "§e已清除这一条覆盖 §f" + rid + " §8（立刻恢复原样）"
                            : "§c清除失败（看日志 store_write_failed / clear_rejected）"), false);
                    ShanhaiMod.LOGGER.info("{} clear_ok id={} ok={}", PREFIX, rid, cleared);
                    return;
                }
                boolean ok = ShanhaiItemOverrideTable.setName(rid, this.name);
                ok &= ShanhaiItemOverrideTable.setLore(rid, this.lore, this.loreMode);
                if (this.maxStack > 0) {
                    ok &= ShanhaiItemOverrideTable.setMaxStack(rid, this.maxStack);
                }
                if (!ok) {
                    player.displayClientMessage(Component.literal(
                            "§c保存失败（看日志里的 store_write_failed / edit_rejected）"), false);
                    return;
                }
                final ShanhaiItemOverrideTable.Entry now = ShanhaiItemOverrideTable.get(rid);
                player.displayClientMessage(Component.literal("§a已保存 §f" + rid + " §8· "
                        + (now == null ? "<没有覆盖项＝恢复原样>" : now.signature())), false);
                ShanhaiMod.LOGGER.info("{} override_ok id={} after={}", PREFIX, rid,
                        now == null ? "<none>" : now.signature());
            });
            context.setPacketHandled(true);
        }
    }

    /** C2S：「删除」页的四个动作。 */
    public static final class RemovalMessage {

        private final String id;
        private final int what;

        RemovalMessage(String id, int what) {
            this.id = id == null ? "" : id;
            this.what = what;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(capStr(id, 160), 512);
            buf.writeVarInt(what);
        }

        static RemovalMessage decode(FriendlyByteBuf buf) {
            return new RemovalMessage(buf.readUtf(512), buf.readVarInt());
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                final ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                if (!ShanhaiRealityItemTable.playerHasModule(player)) {
                    player.displayClientMessage(Component.literal(
                            ShanhaiRealityItemTable.moduleRequiredText()), false);
                    return;
                }
                final ResourceLocation rid = ResourceLocation.tryParse(this.id);
                if (rid == null) {
                    player.displayClientMessage(Component.literal("§c物品 id 不合法：" + this.id), false);
                    return;
                }
                if (ShanhaiItemOverrideTable.isReserved(rid)) {
                    player.displayClientMessage(Component.literal(
                            "§c" + rid + " 是现实物品壳，这一页对它无效"), false);
                    return;
                }
                final MinecraftServer server = player.getServer();
                switch (this.what) {
                    case ACTION_HIDE_ON -> {
                        final boolean ok = ShanhaiItemRemovalOps.hide(server, rid, true);
                        player.displayClientMessage(Component.literal(ok
                                ? "§e已藏起来 §f" + rid + " §8（JEI / 创造栏看不到，实例还在）"
                                : "§c隐藏失败（看日志 store_write_failed）"), false);
                    }
                    case ACTION_HIDE_OFF -> {
                        final boolean ok = ShanhaiItemRemovalOps.hide(server, rid, false);
                        player.displayClientMessage(Component.literal(ok
                                ? "§a已取消隐藏 §f" + rid
                                : "§c取消隐藏失败（看日志 store_write_failed）"), false);
                    }
                    case ACTION_REMOVE -> {
                        // 🔴 与 /shanhai item remove 同一道闸门：必须先为这个 id 请求过影响面
                        if (!IMPACT_SEEN.contains(impactKey(player, rid))) {
                            player.displayClientMessage(Component.literal(
                                    "§c先点「看影响面」再抹除（后端也会拒）"), false);
                            ShanhaiMod.LOGGER.warn("{} removal_rejected reason=impact_not_run id={} player={}",
                                    PREFIX, rid, player.getName().getString());
                            return;
                        }
                        final ShanhaiItemRemovalOps.Result r =
                                ShanhaiItemRemovalOps.remove(server, rid, true);
                        player.displayClientMessage(Component.literal(r.ok()
                                ? "§c" + r.message() + " §8· 被摘/停用的配方 §f" + r.stripped()
                                        + " §8条（整条停用 §f" + r.disabled() + "§8 条）"
                                        + " §8· §7方块形态只做藏/抹/摘配方，世界里已放置的§c不动"
                                : "§c" + r.message()), false);
                    }
                    default -> {
                        final ShanhaiItemRemovalOps.Result r =
                                ShanhaiItemRemovalOps.restore(server, rid);
                        player.displayClientMessage(Component.literal(r.ok()
                                ? "§a" + r.message() + " §8· hidden/removed 已取消"
                                : "§c撤回失败（看日志 del_restore）"), false);
                    }
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** C2S：请服务端算影响面（顺带把"这个玩家看过这个 id 的影响面"记下来）。 */
    public static final class ImpactRequestMessage {

        private final String id;

        ImpactRequestMessage(String id) {
            this.id = id == null ? "" : id;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(capStr(id, 160), 512);
        }

        static ImpactRequestMessage decode(FriendlyByteBuf buf) {
            return new ImpactRequestMessage(buf.readUtf(512));
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                final ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                if (!ShanhaiRealityItemTable.playerHasModule(player)) {
                    return;      // 影响面是只读的，没有模块就安静地不答，不刷聊天栏
                }
                final ResourceLocation rid = ResourceLocation.tryParse(this.id);
                if (rid == null) {
                    return;
                }
                final MinecraftServer server = player.getServer();
                final ShanhaiItemRemovalOps.Impact im = ShanhaiItemRemovalOps.impact(server, rid);
                ShanhaiMod.LOGGER.info("{} impact_begin {}", PREFIX, im.oneLine());
                for (String line : ShanhaiItemRemovalOps.impactLines(im)) {
                    ShanhaiMod.LOGGER.info("{} impact_line {}", PREFIX,
                            line.replaceAll("§.", ""));
                }
                // ④+ 方块计数（只读；一次扫描拿到数字，不调两次）
                final Item item = BuiltInRegistries.ITEM.get(rid);
                final ShanhaiItemRemovalOps.BlockCount bc = ShanhaiItemRemovalOps.countBlocks(server, item);
                ShanhaiMod.LOGGER.info("{} {}", PREFIX, bc == null
                        ? "impact_block_count=not_a_block"
                        : "impact_block_count=" + bc.total() + " chunks=" + bc.chunks()
                                + " radius=" + bc.radius() + " partial=" + bc.partial() + " ms=" + bc.ms());
                IMPACT_SEEN.add(impactKey(player, rid));
                final ImpactResult result = new ImpactResult(rid.toString(), im.registered(), im.isBlock(),
                        im.outVanilla(), im.outGt(), im.inVanilla(), im.inGt(), im.soleOutput(),
                        bc == null ? -1 : bc.total(),
                        bc == null ? 0 : bc.chunks(),
                        bc == null ? 0 : bc.radius(),
                        bc != null && bc.partial());
                if (isReady()) {
                    channel.send(PacketDistributor.PLAYER.with(() -> player), new ImpactResultMessage(result));
                }
                ShanhaiMod.LOGGER.info("{} impact_done {}（「抹除」闸门已解锁）", PREFIX, result.oneLine());
            });
            context.setPacketHandled(true);
        }
    }

    /** S2C：影响面结果（只发给请求的那个玩家）。 */
    public static final class ImpactResultMessage {

        private final ImpactResult result;

        ImpactResultMessage(ImpactResult result) {
            this.result = result;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(capStr(result.id(), 160), 512);
            buf.writeBoolean(result.registered());
            buf.writeBoolean(result.isBlock());
            buf.writeVarInt(result.outVanilla());
            buf.writeVarInt(result.outGt());
            buf.writeVarInt(result.inVanilla());
            buf.writeVarInt(result.inGt());
            buf.writeVarInt(result.soleOutput());
            buf.writeVarInt(result.blockTotal());
            buf.writeVarInt(result.blockChunks());
            buf.writeVarInt(result.blockRadius());
            buf.writeBoolean(result.blockPartial());
        }

        static ImpactResultMessage decode(FriendlyByteBuf buf) {
            final String id = buf.readUtf(512);
            final boolean registered = buf.readBoolean();
            final boolean isBlock = buf.readBoolean();
            final int outVanilla = buf.readVarInt();
            final int outGt = buf.readVarInt();
            final int inVanilla = buf.readVarInt();
            final int inGt = buf.readVarInt();
            final int sole = buf.readVarInt();
            final int blockTotal = buf.readVarInt();
            final int blockChunks = buf.readVarInt();
            final int blockRadius = buf.readVarInt();
            final boolean partial = buf.readBoolean();
            return new ImpactResultMessage(new ImpactResult(id, registered, isBlock, outVanilla, outGt,
                    inVanilla, inGt, sole, blockTotal, blockChunks, blockRadius, partial));
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                // ⚠️ 本消息方向是 PLAY_TO_CLIENT ⇒ 这段只在客户端跑（专服不会加载到界面类）
                ShanhaiItemRemovalPage.onImpactResult(this.result);
                ShanhaiMod.LOGGER.info("{} impact_result_applied {}", PREFIX, this.result.oneLine());
            });
            context.setPacketHandled(true);
        }
    }

    /** 「这个玩家为这个 id 请求过影响面」的键。 */
    private static String impactKey(ServerPlayer player, ResourceLocation id) {
        return (player == null ? "?" : player.getUUID().toString()) + "|" + id;
    }

    private static String capStr(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
