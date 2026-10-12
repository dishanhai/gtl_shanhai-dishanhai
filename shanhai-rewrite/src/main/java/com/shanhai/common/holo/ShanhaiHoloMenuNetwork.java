package com.shanhai.common.holo;

import com.shanhai.ShanhaiMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 山海重构 · 悬浮全息菜单的<b>网络通道</b>（本模组的<b>第二条</b>；第一条是 {@code ShanhaiJeiBridge}）。
 *
 * <h2>1. 这条通道只为一件事：把"点了一下全息按钮"送回服务端</h2>
 * 全息菜单本身<b>纯客户端</b>（{@code ShanhaiHoloMenuState} 是客户端状态，世界空间渲染）；
 * 但<b>配方编辑器面板必须是服务端权威的</b>：
 * <pre>
 *   面板若走纯客户端路径打开，{@code CPacketUIClientAction} 在服务端只认
 *   {@code player.containerMenu instanceof ModularUIContainer}，否则【直接 return、
 *   静默丢弃所有控件动作、不报错不写日志】（界面看着完全正常）。
 * </pre>
 * （原话见 {@code ShanhaiRecipeEditorFactory} 类注释；本工程在这个坑上付过一次账。）
 * ⇒ 客户端点按钮只发一个<b>板号</b>过来，真正的开面板由服务端调
 * {@code ShanhaiRecipeEditorFactory.open(...)} —— 与 {@code /shanhai edit} 走的是同一条路。
 *
 * <h2>2. 🔴 为什么不像上一条那样"物品 use() ⇒ 发 S2C 让客户端切开关"</h2>
 * 交接件里原本是那个形状。本轮改成<b>全客户端开关</b>，理由是"对空气右键"这条规格：
 * 服务端拿不到客户端的 {@code hitResult}，而 {@code Item#use} 在"右键点到一个不响应的方块"
 * 时也会被调到 ⇒ 服务端那条路<b>分不出"对空气"与"对石头"</b>，只能靠客户端补一个字段过去；
 * 而开关本身又只影响客户端的渲染。⇒ 多绕一圈只会多一处可以漂的地方。
 * 服务端那条路只留<b>它必须管的那件事</b>：开面板。
 *
 * <h2>3. 板号在服务端【必须再判一次】</h2>
 * 客户端只发一个 int，服务端不信它：{@link #accepts(int)} 拿
 * {@link ShanhaiHoloMenuBoards} 的真表重判一次（"是不是恰好那块可用的板"）。
 * 这条判据的自检在 {@link #roundTripSelfCheck()} 里，带负对照。
 *
 * <h2>4. 注册时机</h2>
 * 挂 {@code FMLCommonSetupEvent}（客户端与专用服务端各跑一次，且在玩家进世界之前）。
 * 幂等；通道建不起来也不让服务端起不来（那块功能退化成"点了没反应"，但机器还能跑）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiHoloMenuNetwork {

    public static final String PREFIX = ShanhaiHoloMenuBoards.PREFIX;

    public static final String CHANNEL_NAME = "shanhai_holo_menu";
    public static final String CHANNEL_VERSION = "1";

    /** 消息 id（两侧必须一致，顺序也必须一致）。 */
    public static final int ID_C2S_OPEN_EDITOR = 0;
    /** 🆕 消息 id：客户端把"要执行的命令"整条发过来（服务端以管理员权限跑）。 */
    public static final int ID_C2S_RUN_COMMAND = 1;

    // ---- 🆕 2026-10-07（联机 P2）：三条新消息 ------------------------------------
    //
    //   为什么是三条、而不是"全塞一包"（理由与设计 §3.1 一致，都能独立判）：
    //     · DEF    很少变（只在进服/换维度/改了高级调参时发）⇒ 一人一次 61 字节载荷
    //     · STATE  一变就发（开关/排布/距离/朝向/环绕档）⇒ 9 字节载荷
    //   分开的收益：拨一下档位只花 25 字节，而不是把 61 字节的参数再发一遍。
    //
    //   🔴 收方是谁：**同服所有持模块者**（用户拍板③），由服务端算（不信客户端）；
    //      "按距离裁剪"发生在**渲染端**（只画 64 格以内的人），不在发包端。

    /** 🆕 S2C：那个人的环绕调参（15 项 + 档位），很少变。载荷 {@code ShanhaiHoloRemoteCodec#DEF_BYTES} 字节。 */
    public static final int ID_S2C_HOLO_DEF = 2;

    /** 🆕 S2C：那个人的开关/排布/距离/朝向/环绕档，一变就发。载荷 {@code STATE_BYTES} 字节。 */
    public static final int ID_S2C_HOLO_STATE = 3;

    /** 🆕 C2S：客户端把自己的设置报上来（PUBLISH = DEF + STATE，服务端只当"数据"、不当"权限"）。 */
    public static final int ID_C2S_HOLO_PUBLISH = 4;

    /**
     * 🆕 S2C：服务端请某个客户端<b>再报一次</b>（载荷 16 字节，只有"请谁报"）。
     *
     * <p>为什么要它：用户拍板②定的是"15 项<b>一人一次</b>"⇒ 不能让客户端定期重发。
     * 但"我进来的时候别人已经站在这儿了"这个场景必须有人补一刀，而<b>服务端手上没有别人的样子时
     * 不能编</b>（编了就是"用我的参数画他的星空"）。⇒ 让服务端<b>点名</b>请他自己报一次。
     * 稳态下这条消息一个字节都不发（见 {@code ShanhaiHoloRemoteSync} 的巡检）。
     */
    public static final int ID_S2C_HOLO_ASK = 5;

    /**
     * 🆕 <b>渲染端</b>只画这个距离以内的其他玩家（用户拍板③："渲染按距离裁剪，只画 64 格以内的玩家"）。
     *
     * <p>⚠️ 它与"发给谁"是<b>两件事</b>：发包给同服所有持模块者（不看距离），
     * 画的时候才按距离剔。这一条把"服务器人多时全画"那种卡顿挡在外面。
     */
    public static final double RENDER_DISTANCE_BLOCKS = 64.0;

    /** 上面那个数的平方（避免每帧开方）。 */
    public static final double RENDER_DISTANCE_SQR = RENDER_DISTANCE_BLOCKS * RENDER_DISTANCE_BLOCKS;

    private static SimpleChannel channel;
    private static boolean registered;

    /**
     * 🆕 2026-10-07（联机 P2）<b>客户端侧的落点</b>（收到 S2C 之后要调谁）。
     *
     * <p>🔴 为什么用这个接口、而不是在 {@code common} 包里直接 {@code import} 客户端那个类：
     * 本类在 {@code common/holo}，<b>专服也会加载它</b>；一旦它在字节码里引用
     * {@code com.shanhai.client.holo.*}（那个包全是 {@code @OnlyIn(CLIENT)}），
     * 专服上就可能踩到"客户端类被加载"。⇒ 反向注册：客户端开机时
     * {@link #setClientSink} 一个实现进来，common 侧只认这个接口。
     * 没注册（= 专服）时 {@link #clientSink} 为 null，收到 S2C 只会打一行日志。
     */
    public interface ClientSink {

        /** 收到某人的 DEF。 */
        void acceptDef(java.util.UUID who, int surroundPreset, float[] params);

        /** 收到某人的 STATE。 */
        void acceptState(java.util.UUID who, boolean enabled, int layout, float distance,
                         int facing, int surroundPreset, boolean panelOpen);

        /** 服务端点名要你再报一次（可能因为别人刚进来 / 他手上那份太旧了）。 */
        void republish();
    }

    private static volatile ClientSink clientSink;

    /** 客户端开机时注册（专服永远不调）。 */
    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
        ShanhaiMod.LOGGER.info("{} holo_mp_client_sink_registered present={}",
                PREFIX, sink != null);
    }

    public static boolean hasClientSink() {
        return clientSink != null;
    }

    private ShanhaiHoloMenuNetwork() {}
    /**
     * 命令长度上限（字符）。两侧同一条口径：
     * <pre>
     *   客户端：{@code ShanhaiHoloMenuPanel.MAX_COMMAND_CHARS} = 256（多打的字根本收不进去）
     *   服务端：这一条 —— 就算客户端被改过，超长的也一律拒收
     * </pre>
     */
    public static final int MAX_COMMAND_CHARS = 256;

    /**
     * 执行命令时使用的权限等级（4 = 原版 op 的最高档）。
     * <p>用户点单逐字：「<b>全息输入框，可以执行cmd的命令，拥有管理员权限</b>」——
     * 所以 {@code withPermission(4)}，<b>而不是"看玩家自己是不是 op"</b>。
     * <p>🔴 门槛是<b>另一件事</b>：必须手里/身上有「创始现实修改模块」
     * （用户原话：「只有拿着『创始现实修改模块』的人才能开这个菜单」）——
     * 服务端在 {@link #onCommandRequest} 里<b>自己再查一次背包</b>，不信客户端。
     */
    public static final int COMMAND_PERMISSION_LEVEL = 4;

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            register();
            // 开机就把"五块板里只有一块可用"打成一行机器可判读数（专服与客户端都会打）
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuBoards.tableLine());
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuBoards.selfCheck());
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, roundTripSelfCheck());
            // 🆕 2026-10-07（联机 P2）：三条联机载荷的字节数与往返自检（专服与客户端都打同一行）
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloRemoteCodec.selfTest());
        });
    }

    /** 注册（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            ShanhaiMod.LOGGER.info("{} holo_net_channel_already_registered name={}", PREFIX, CHANNEL_NAME);
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(ID_C2S_OPEN_EDITOR, OpenEditorMessage.class,
                    OpenEditorMessage::encode, OpenEditorMessage::decode, OpenEditorMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            // 🆕 2026-10-06：第二条消息 = 全息命令输入框那条（C2S，服务端以管理员权限执行）
            channel.registerMessage(ID_C2S_RUN_COMMAND, RunCommandMessage.class,
                    RunCommandMessage::encode, RunCommandMessage::decode, RunCommandMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            // ---- 🆕 2026-10-07（联机 P2）：DEF / STATE（S2C）+ PUBLISH（C2S）----
            channel.registerMessage(ID_S2C_HOLO_DEF, HoloDefMessage.class,
                    HoloDefMessage::encode, HoloDefMessage::decode, HoloDefMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            channel.registerMessage(ID_S2C_HOLO_STATE, HoloStateMessage.class,
                    HoloStateMessage::encode, HoloStateMessage::decode, HoloStateMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            channel.registerMessage(ID_C2S_HOLO_PUBLISH, HoloPublishMessage.class,
                    HoloPublishMessage::encode, HoloPublishMessage::decode, HoloPublishMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_S2C_HOLO_ASK, HoloAskMessage.class,
                    HoloAskMessage::encode, HoloAskMessage::decode, HoloAskMessage::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            registered = true;
            ShanhaiMod.LOGGER.info("{} holo_net_channel_registered name={} version={} id={} "
                            + "c2s_id=[{},{}] c2s=[open_editor,run_command] direction={} "
                            + "mp_id=[s2c_def={},s2c_state={},c2s_publish={},s2c_ask={}] messages=6",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION,
                    new ResourceLocation("shanhai", CHANNEL_NAME), ID_C2S_OPEN_EDITOR, ID_C2S_RUN_COMMAND,
                    NetworkDirection.PLAY_TO_SERVER,
                    ID_S2C_HOLO_DEF, ID_S2C_HOLO_STATE, ID_C2S_HOLO_PUBLISH, ID_S2C_HOLO_ASK);
        } catch (Throwable t) {
            // 通道建不起来绝不能让服务端起不来（全息那块功能退化，但机器还能跑）
            ShanhaiMod.LOGGER.error("{} holo_net_channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    /** 装机读数（开机日志用）。 */
    public static String statsLine() {
        return "channel=" + CHANNEL_NAME + " ready=" + isReady() + " version=" + CHANNEL_VERSION
                + " messages=2";
    }

    // ------------------------------------------------------------------ 发送

    /**
     * 🆕 C2S：全息命令输入框里那条命令（<b>只能在客户端调用</b>）。
     *
     * <p>客户端在这里只做两件事：长度夹一下、发出去。<b>不判权限、不判自己是不是 op</b>
     * —— "这条命令该不该跑"是服务端的事（{@link #onCommandRequest}）。
     */
    public static void requestCommand(String command) {
        final String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            ShanhaiMod.LOGGER.info("{} holo_cmd_net_skip reason=empty", PREFIX);
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} holo_net_skip reason=channel_not_ready action=run_command len={}",
                    PREFIX, cmd.length());
            return;
        }
        final String capped = cmd.length() > MAX_COMMAND_CHARS ? cmd.substring(0, MAX_COMMAND_CHARS) : cmd;
        try {
            channel.sendToServer(new RunCommandMessage(capped));
            ShanhaiMod.LOGGER.info("{} holo_cmd_send len={} capped={} cmd={}",
                    PREFIX, cmd.length(), cmd.length() > MAX_COMMAND_CHARS, capped);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_net_send_failed action=run_command len={} err={}",
                    PREFIX, cmd.length(), t.toString(), t);
        }
    }

    /**
     * C2S：客户端点了「配方修改」那一格（<b>只能在客户端调用</b>）。
     *
     * @param boardIndex 0 基板号（日志里同时打 1 基，避免"看到 2 不知道是第几格"）
     */
    public static void requestEditor(int boardIndex) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} holo_net_skip reason=channel_not_ready action=open_editor board={}",
                    PREFIX, ShanhaiHoloMenuBoards.boardNumber(boardIndex));
            return;
        }
        try {
            channel.sendToServer(new OpenEditorMessage(boardIndex));
            ShanhaiMod.LOGGER.info("{} holo_editor_request_sent board={} idx={} label={}",
                    PREFIX, ShanhaiHoloMenuBoards.boardNumber(boardIndex), boardIndex,
                    ShanhaiHoloMenuBoards.labelOf(boardIndex));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_net_send_failed action=open_editor board={} err={}",
                    PREFIX, ShanhaiHoloMenuBoards.boardNumber(boardIndex), t.toString(), t);
        }
    }

    // ------------------------------------------------------------------ 服务端

    /**
     * 服务端那条唯一的判据：<b>这个板号能不能开面板</b>。
     * <p>实现落在 {@link ShanhaiHoloMenuBoards#canOpenEditor(int)}（纯算术、两侧都加载、
     * 离线也能跑 ⇒ 判据本身可以在没有游戏的机器上验）。
     */
    public static boolean accepts(int boardIndex) {
        return ShanhaiHoloMenuBoards.canOpenEditor(boardIndex);
    }

    /**
     * 收到"点了某块板"之后，服务端真正要做的事。
     *
     * <p>与 {@code /shanhai edit}（{@code ShanhaiRecipeEditorCommand#openPanel}）
     * 走的是<b>同一个</b> {@code ShanhaiRecipeEditorFactory.open} + 同一个 holder 初值
     * ⇒ 两条入口进到的是同一个面板的第一屏，行为不可能分叉。
     *
     * <p>🔴 <b>不要求玩家还拿着模块</b>：规格里"要进面板就得先让模块不在手上"，
     * 而 {@code /shanhai edit} 更是完全不拿模块也能开 ⇒ 服务端要是再加一条"必须拿模块"，
     * 就会把用户明确要的那条路堵死。
     */
    public static void onEditorRequest(ServerPlayer player, int boardIndex) {
        final int board = ShanhaiHoloMenuBoards.boardNumber(boardIndex);
        if (player == null) {
            ShanhaiMod.LOGGER.warn("{} holo_editor_request REJECTED reason=no_sender board={}", PREFIX, board);
            return;
        }
        if (!accepts(boardIndex)) {
            ShanhaiMod.LOGGER.warn("{} holo_editor_request REJECTED reason=board_not_enabled board={} idx={} "
                            + "label={} enabled={} action={} player={}",
                    PREFIX, board, boardIndex, ShanhaiHoloMenuBoards.labelOf(boardIndex),
                    ShanhaiHoloMenuBoards.isEnabled(boardIndex),
                    ShanhaiHoloMenuBoards.actionOf(boardIndex), player.getName().getString());
            return;
        }
        boolean ok = false;
        try {
            ok = com.shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory.open(
                    new com.shanhai.common.recipe.editor.ShanhaiRecipeEditorHolder(
                            null,
                            com.shanhai.common.recipe.editor.ShanhaiRecipeEditorHolder.STAGE_TYPES,
                            null, null, false),
                    player);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_editor_open_threw board={} player={} err={}",
                    PREFIX, board, player.getName().getString(), t.toString(), t);
        }
        ShanhaiMod.LOGGER.info("{} holo_editor_opened board={} idx={} label={} player={} ok={}",
                PREFIX, board, boardIndex, ShanhaiHoloMenuBoards.labelOf(boardIndex),
                player.getName().getString(), ok);
    }

    // ------------------------------------------------------------------ 🆕 命令（服务端）

    /** 被拒绝的原因码 ⇒ 中文（聊天栏给玩家看的那一句就由它拼）。 */
    private static String rejectText(String why) {
        return "§c[全息命令] 没执行：" + why;
    }

    /**
     * 🆕 2026-10-06：<b>服务端真正执行那条命令</b>（以管理员权限）。
     *
     * <h4>三道门，缺一不可</h4>
     * <pre>
     *   ① 有发送者（不是伪造的空 source）；
     *   ② 命令不空、长度 ≤ {@link #MAX_COMMAND_CHARS}（客户端夹过一次，这里【再夹一次】）；
     *   ③ 🔴 发命令的玩家身上【必须有】创始现实修改模块 —— 用户点单的"门槛"就在这里：
     *      「只有拿着『创始现实修改模块』的人才能开这个菜单 ⇒ 所以『拿模块』就是它的门槛」。
     *      服务端<b>不看客户端说了什么</b>，自己去主手/副手/背包里找那块模块
     *      （与客户端 {@code ShanhaiHoloMenuInput#moduleAnywhere} 同一口径，但是【服务端自己算的】）。
     * </pre>
     *
     * <h4>权限怎么给的（用户点单的原话）</h4>
     * 「全息输入框，可以执行cmd的命令，<b>拥有管理员权限</b>」⇒ {@code withPermission(4)}。
     * <b>不要求玩家自己是 op</b>：那是两条不同的路（用户明确把门槛放在了模块上）。
     *
     * <h4>玩家能看到什么</h4>
     * 命令自己的输出（成功/失败的原版反馈）照旧进玩家聊天栏（<b>不做 withSuppressedOutput</b>
     * —— 那会把"命令报的错"也一起吞掉，用户就永远不知道为什么没生效）；
     * 本模组再补一行带前缀的读数（返回码 + 是不是以管理员身份跑的）。
     */
    public static void onCommandRequest(ServerPlayer player, String rawCommand) {
        if (player == null) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd REJECTED reason=no_sender", PREFIX);
            return;
        }
        final String name = player.getName().getString();
        final String cmd = rawCommand == null ? "" : rawCommand.trim();
        if (cmd.isEmpty()) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd REJECTED reason=empty player={}", PREFIX, name);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(rejectText("命令是空的")));
            return;
        }
        if (cmd.length() > MAX_COMMAND_CHARS) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd REJECTED reason=too_long len={} player={}",
                    PREFIX, cmd.length(), name);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    rejectText("命令太长（上限 " + MAX_COMMAND_CHARS + " 个字符）")));
            return;
        }
        if (!moduleInInventory(player)) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd REJECTED reason=module_not_carried player={} cmd={}",
                    PREFIX, name, cmd);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    rejectText("身上没有「创始现实修改模块」")));
            return;
        }
        final net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd REJECTED reason=no_server player={}", PREFIX, name);
            return;
        }
        final String text = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        int result = -1;
        boolean threw = false;
        try {
            // 🔴 以管理员权限执行：把源栈拷一份再 withPermission(4)，【不】改玩家自己的源栈。
            final net.minecraft.commands.CommandSourceStack source =
                    player.createCommandSourceStack().withPermission(COMMAND_PERMISSION_LEVEL);
            result = server.getCommands().performPrefixedCommand(source, text);
        } catch (Throwable t) {
            threw = true;
            ShanhaiMod.LOGGER.error("{} holo_cmd_failed player={} cmd={} err={}",
                    PREFIX, name, text, t.toString(), t);
        }
        ShanhaiMod.LOGGER.info("{} holo_cmd_executed player={} perm_level={} is_op={} threw={} result={} cmd={}",
                PREFIX, name, COMMAND_PERMISSION_LEVEL, player.hasPermissions(2), threw, result, text);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§b[全息命令] §7已以管理员权限执行 §f/" + text
                        + " §7（返回码 " + result + (threw ? " · 抛异常了，看日志" : "") + "）"));
    }

    /**
     * 玩家身上有没有那块模块（主手 ∪ 副手 ∪ 背包 41 格）。
     * <p>与客户端 {@code ShanhaiHoloMenuInput#moduleAnywhere} <b>同一个口径</b>，但这是
     * <b>服务端自己算的</b> —— 客户端的读数只是 UI，不能当权限依据。
     */
    public static boolean moduleInInventory(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        final net.minecraft.world.item.Item module =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        new ResourceLocation("shanhai", "genesis_reality_modification_module"));
        if (module == null || module == net.minecraft.world.item.Items.AIR) {
            return false;                       // 还没注册好 ⇒ 一律当"没有"（宁可拒，不可放）
        }
        if (player.getMainHandItem().is(module) || player.getOffhandItem().is(module)) {
            return true;
        }
        final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(module)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 🆕 联机：发

    /**
     * 🆕 C2S：<b>客户端把自己的设置报上去</b>（联机 P2 的唯一入口，只能在客户端调用）。
     *
     * <p>🔴 <b>服务端不信这条包里的任何人/任何权限</b>：
     * <ul>
     *   <li>"我是谁"由服务端用 {@code ctx.getSender()} 自己取，<b>不读</b>载荷里的 UUID；</li>
     *   <li>"他能不能被广播"由服务端自己查背包（{@link #moduleInInventory}）；</li>
     *   <li>载荷里的数只是"他那边长什么样"，改它最多骗到别人眼里的<b>观感</b>，没有权限后果。</li>
     * </ul>
     */
    public static void publishHolo(java.util.UUID self, boolean enabled, int layout, float distance,
                                   int facing, int surroundPreset, float[] params, boolean panelOpen) {
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} holo_mp_publish_skip reason=channel_not_ready enabled={}", PREFIX, enabled);
            return;
        }
        try {
            channel.sendToServer(new HoloPublishMessage(self, enabled, layout, distance, facing,
                    surroundPreset, params, panelOpen));
            ShanhaiMod.LOGGER.info("{} holo_mp_publish_sent enabled={} layout={} distance={} facing={} "
                            + "preset={} params={} panel_open={} bytes={}",
                    PREFIX, enabled, layout, distance, facing, surroundPreset,
                    params == null ? -1 : params.length, panelOpen, ShanhaiHoloRemoteCodec.PUBLISH_BYTES);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_mp_publish_failed err={}", PREFIX, t.toString(), t);
        }
    }

    /** 🆕 S2C：把<b>某人</b>的 DEF 发给一个玩家（载荷 77 字节，其中"设置本身"61）。 */
    public static void sendDefTo(ServerPlayer to, java.util.UUID who, int surroundPreset, float[] params) {
        if (!isReady() || to == null || to.hasDisconnected()) {
            return;
        }
        channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
                new HoloDefMessage(who, surroundPreset, params));
    }

    /** 🆕 S2C：把<b>某人</b>的 STATE 发给一个玩家（载荷 25 字节）。 */
    public static void sendStateTo(ServerPlayer to, java.util.UUID who, boolean enabled, int layout,
                                   float distance, int facing, int surroundPreset, boolean panelOpen) {
        if (!isReady() || to == null || to.hasDisconnected()) {
            return;
        }
        channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
                new HoloStateMessage(who, enabled, layout, distance, facing, surroundPreset, panelOpen));
    }

    /** 🆕 S2C：请某个客户端再报一次（16 字节载荷）。 */
    public static void askPublish(ServerPlayer to) {
        if (!isReady() || to == null || to.hasDisconnected()) {
            return;
        }
        channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
                new HoloAskMessage(to.getUUID()));
        ShanhaiMod.LOGGER.info("{} holo_mp_ask_sent to={}", PREFIX, to.getUUID());
    }

    /**
     * 🆕 <b>收件人 = 同服所有持模块者（去掉自己）</b> —— 用户拍板③。     *
     * <p>判据全部由<b>服务端自己算</b>：{@link #moduleInInventory}（不信客户端，注册表没好 ⇒ 当没有）。
     * <b>不按距离过滤</b>（看不见的人反正会被渲染端那 64 格剔掉）。
     */
    public static java.util.List<ServerPlayer> moduleHoldersExcept(ServerPlayer self) {
        final java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        // ⚠️ 用 Entity#getServer()（public，javap 核实过），不要碰 protected 的 server 字段
        final net.minecraft.server.MinecraftServer server = self == null ? null : self.getServer();
        if (server == null) {
            return out;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == self || p.hasDisconnected()) {
                continue;
            }
            if (moduleInInventory(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 编解码往返 + 服务端判据的自检（<b>纯算术，专服开机就能跑</b>）。
     *
     * <p>判据 6 条，其中 <b>3 条是负对照/口径对照</b>：
     * <pre>
     *   ① C2S：boardIndex = 1 过一遍真编解码器 ⇒ 一个数都不差
     *   ② 【口径对照】boardIndex = 1 与 boardIndex = 3 解出来必须【不同】
     *      —— 否则"解回来了"可能只是常量对常量（本工程"检查器自证"那一类坑）
     *   ③ 【负对照】-1 也必须原样往返（它落在"没打到板"那一档，不能被悄悄改成 0）
     *   ④ 服务端判据：第 2 格（配方修改）必须【接受】
     *   ⑤ 【负对照】第 1 / 3 / 4 / 5 格与越界值（-1、5、99）必须【拒绝】
     *   ⑥ 【负对照】真表被换成"五块全可用"时，第 1 格必须【变成接受】
     *      —— 证明 ⑤ 不是恒绿的
     * </pre>
     */
    public static String roundTripSelfCheck() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        // ⚠️ 与下面 pass++ 的处数逐一点得上：①..⑥ = 6（板号那条链），⑦..⑪ = 5（命令那条链）。
        final int total = 11;

        // ① C2S 往返
        int decoded1 = Integer.MIN_VALUE;
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new OpenEditorMessage(ShanhaiHoloMenuBoards.ENABLED_INDEX).encode(buf);
            decoded1 = OpenEditorMessage.decode(buf).boardIndex;
            buf.release();
            if (decoded1 == ShanhaiHoloMenuBoards.ENABLED_INDEX) {
                pass++;
            } else {
                bad.append(" ①board=").append(decoded1).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ①threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ② 口径对照：两个不同的载荷必须解出不同的值
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new OpenEditorMessage(3).encode(buf);
            final int three = OpenEditorMessage.decode(buf).boardIndex;
            buf.release();
            if (three == 3 && three != decoded1) {
                pass++;
            } else {
                bad.append(" ②两个载荷解出同一个值=").append(three).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ②threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ③ 负对照：-1（"没打到板"）必须原样过来
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new OpenEditorMessage(-1).encode(buf);
            final int minus = OpenEditorMessage.decode(buf).boardIndex;
            buf.release();
            if (minus == -1) {
                pass++;
            } else {
                bad.append(" ③-1 变成了 ").append(minus).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ③threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ④ 服务端接受唯一可用的那一格
        if (accepts(ShanhaiHoloMenuBoards.ENABLED_INDEX)) {
            pass++;
        } else {
            bad.append(" ④服务端拒了第 ").append(ShanhaiHoloMenuBoards.boardNumber(
                    ShanhaiHoloMenuBoards.ENABLED_INDEX)).append(" 格;");
        }

        // ⑤ 负对照：其余四格与越界值都必须拒
        final StringBuilder accepted = new StringBuilder();
        for (int i = 0; i < ShanhaiHoloMenuBoards.BOARD_COUNT; i++) {
            if (i != ShanhaiHoloMenuBoards.ENABLED_INDEX && accepts(i)) {
                accepted.append(i).append(',');
            }
        }
        for (int rogue : new int[]{-1, ShanhaiHoloMenuBoards.BOARD_COUNT, 99}) {
            if (accepts(rogue)) {
                accepted.append(rogue).append(',');
            }
        }
        if (accepted.length() == 0) {
            pass++;
        } else {
            bad.append(" ⑤不该接受的却接受了 idx=").append(accepted).append(';');
        }

        // ⑥ 负对照：板表换成"五块全可用【且全挂配方编辑】"时，第 1 格必须【变成接受】
        //    —— 用的是同一套规则（canOpenEditorTable），只是换了张表 ⇒ 证明 ⑤ 不是恒绿的。
        //    ⚠️ 只翻 enabled 是不够的：真动作码表里第 1 格是 NONE，那样它【应当】仍被拒
        //    （这一条第一次就写错过，是自检的负对照当场把"两个条件都要求"这件事顶出来的）。
        final boolean[] allOn = {true, true, true, true, true};
        final int[] allEdit = {ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT,
                ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT, ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT,
                ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT, ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT};
        if (ShanhaiHoloMenuBoards.canOpenEditorTable(allOn, allEdit, 0)) {
            pass++;
        } else {
            bad.append(" ⑥负对照没响（五块全可用且全挂配方编辑时第 1 格仍被拒）;");
        }

        // ---- 🆕 2026-10-06：命令那一条消息的编解码往返 ----
        //    ⚠️ 这一族的坑：{@code writeUtf} 的长度上限、以及"中文会不会被截断"。
        //    所以这一条特意用【带中文和空格】的命令，而不是 "abc"。
        final String probe = "give @s minecraft:stone 64 中文";
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RunCommandMessage(probe).encode(buf);
            final String back = RunCommandMessage.decode(buf).command;
            buf.release();
            if (probe.equals(back)) {
                pass++;
            } else {
                bad.append(" ⑦命令过编解码器变了样：得到「").append(back).append("」;");
            }
        } catch (Throwable t) {
            bad.append(" ⑦threw ").append(t.getClass().getSimpleName()).append(';');
        }
        // ⑦ 口径对照：两条不同的命令必须解出不同的字符串（与上一条的 board 口径对照同一个用意）
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RunCommandMessage("time set day").encode(buf);
            final String a = RunCommandMessage.decode(buf).command;
            buf.release();
            if ("time set day".equals(a)) {
                pass++;
            } else {
                bad.append(" ⑧time set day 解出来是「").append(a).append("」;");
            }
        } catch (Throwable t) {
            bad.append(" ⑧threw ").append(t.getClass().getSimpleName()).append(';');
        }
        // ⑧ 负对照：空串必须原样往返（不许被悄悄变成 null / 变成别的）
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RunCommandMessage("").encode(buf);
            final String e = RunCommandMessage.decode(buf).command;
            buf.release();
            if (e != null && e.isEmpty()) {
                pass++;
            } else {
                bad.append(" ⑨空命令没原样往返：得到「").append(e).append("」;");
            }
        } catch (Throwable t) {
            bad.append(" ⑨threw ").append(t.getClass().getSimpleName()).append(';');
        }
        // ⑨ 负对照：超长命令必须在【编码】这一步就被夹到上限（而不是靠偶然）
        try {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_COMMAND_CHARS + 100; i++) {
                sb.append('x');
            }
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RunCommandMessage(sb.toString()).encode(buf);
            final int len = RunCommandMessage.decode(buf).command.length();
            buf.release();
            if (len == MAX_COMMAND_CHARS) {
                pass++;
            } else {
                bad.append(" ⑩超长命令编码后长度=").append(len).append("（应当是 ")
                        .append(MAX_COMMAND_CHARS).append("）;");
            }
        } catch (Throwable t) {
            bad.append(" ⑩threw ").append(t.getClass().getSimpleName()).append(';');
        }
        // ⑩ 权限等级必须是 4（用户点单原话「拥有管理员权限」）—— 常量被改小是一个静默的降级
        if (COMMAND_PERMISSION_LEVEL == 4) {
            pass++;
        } else {
            bad.append(" ⑪权限等级被改成了 ").append(COMMAND_PERMISSION_LEVEL).append("（应当是 4）;");
        }

        final String line = "holo_net_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：板号过真编解码器逐字回来；服务端只接受第 "
                + ShanhaiHoloMenuBoards.boardNumber(ShanhaiHoloMenuBoards.ENABLED_INDEX)
                + " 格；命令字符串逐字往返 + 超长夹到 " + MAX_COMMAND_CHARS
                + " + 权限等级=" + COMMAND_PERMISSION_LEVEL + "；含 5 条负对照）";
        if (bad.length() > 0) {
            ShanhaiMod.LOGGER.error("{} holo_net_selftest FAILED: {}", PREFIX, bad.toString());
        }
        return line;
    }

    // ------------------------------------------------------------------ 消息

    /**
     * C2S：客户端点了某一块全息板。
     * <p>载荷只有一个板号（0 基）—— <b>服务端不信任它</b>，收到之后自己再查一次
     * {@link #accepts(int)}。
     */
    public static final class OpenEditorMessage {

        public final int boardIndex;

        public OpenEditorMessage(int boardIndex) {
            this.boardIndex = boardIndex;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(boardIndex);
        }

        public static OpenEditorMessage decode(FriendlyByteBuf buf) {
            return new OpenEditorMessage(buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 排到服务端主线程：开面板要碰玩家容器与 UI 工厂，绝不能在网络线程上做。
            ctx.enqueueWork(() -> onEditorRequest(ctx.getSender(), boardIndex));
            ctx.setPacketHandled(true);
        }
    }

    /**
     * 🆕 C2S：全息命令输入框里那一条命令（原文）。
     * <p>载荷就是一个字符串 —— <b>服务端不信任它</b>：长度、空串、玩家身上有没有模块
     * 全在 {@link #onCommandRequest} 里自己查。
     */
    public static final class RunCommandMessage {

        public final String command;

        public RunCommandMessage(String command) {
            this.command = command == null ? "" : command;
        }

        public void encode(FriendlyByteBuf buf) {
            // ⚠️ 写之前先夹长度：{@code writeUtf(String)} 的上限是 32767 字节，
            //    但"发一条超长命令"本身就是我们要在两侧都拒掉的东西。
            final String s = command.length() > MAX_COMMAND_CHARS
                    ? command.substring(0, MAX_COMMAND_CHARS) : command;
            buf.writeUtf(s, MAX_COMMAND_CHARS);
        }

        public static RunCommandMessage decode(FriendlyByteBuf buf) {
            return new RunCommandMessage(buf.readUtf(MAX_COMMAND_CHARS));
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 排到服务端主线程：执行命令会碰世界（{@code /give} / {@code /tp} 都不许在网络线程做）。
            ctx.enqueueWork(() -> onCommandRequest(ctx.getSender(), command));
            ctx.setPacketHandled(true);
        }
    }

    // ================================================================== 🆕 联机三条消息

    /**
     * 🆕 S2C：<b>某人的环绕调参</b>（DEF）。
     *
     * <p>载荷就是 {@link ShanhaiHoloRemoteCodec} 编出来的那段字节（77 = 16 UUID + 61 设置）——
     * <b>编解码只有那一份实现</b>，本类只管搬运（{@code writeByteArray} / {@code readBytes}）。
     * 这样"字节数"与"字段顺序"这两件事在两侧不可能漂。
     */
    public static final class HoloDefMessage {

        private final byte[] payload;

        public HoloDefMessage(java.util.UUID who, int surroundPreset, float[] params) {
            this.payload = ShanhaiHoloRemoteCodec.encodeDef(who, surroundPreset, params);
        }

        private HoloDefMessage(byte[] payload) {
            this.payload = payload;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBytes(payload);
        }

        public static HoloDefMessage decode(FriendlyByteBuf buf) {
            final byte[] raw = new byte[ShanhaiHoloRemoteCodec.DEF_BYTES];
            buf.readBytes(raw);
            return new HoloDefMessage(raw);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 排到主线程再写表：渲染线程会读那张表，网络线程上写它就得加锁。
            ctx.enqueueWork(() -> {
                final ShanhaiHoloRemoteCodec.Def d = ShanhaiHoloRemoteCodec.decodeDef(payload);
                if (d == null) {
                    ShanhaiMod.LOGGER.warn("{} holo_mp_def_decode_failed len={}", PREFIX, payload.length);
                    return;
                }
                final ClientSink sink = clientSink;
                if (sink == null) {
                    ShanhaiMod.LOGGER.info("{} holo_mp_def_dropped reason=no_client_sink who={}",
                            PREFIX, d.id());
                    return;
                }
                sink.acceptDef(d.id(), d.surroundPreset(), d.params());
                ShanhaiMod.LOGGER.info("{} holo_mp_def_recv who={} preset={} params={}",
                        PREFIX, d.id(), d.surroundPreset(), d.params().length);
            });
            ctx.setPacketHandled(true);
        }
    }

    /** 🆕 S2C：<b>某人的开关/档位</b>（STATE）。载荷 25 = 16 UUID + 9 设置。 */
    public static final class HoloStateMessage {

        private final byte[] payload;

        public HoloStateMessage(java.util.UUID who, boolean enabled, int layout, float distance,
                                int facing, int surroundPreset, boolean panelOpen) {
            this.payload = ShanhaiHoloRemoteCodec.encodeState(who, enabled, layout, distance, facing,
                    surroundPreset, panelOpen);
        }

        private HoloStateMessage(byte[] payload) {
            this.payload = payload;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBytes(payload);
        }

        public static HoloStateMessage decode(FriendlyByteBuf buf) {
            final byte[] raw = new byte[ShanhaiHoloRemoteCodec.STATE_BYTES];
            buf.readBytes(raw);
            return new HoloStateMessage(raw);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final ShanhaiHoloRemoteCodec.State s = ShanhaiHoloRemoteCodec.decodeState(payload);
                if (s == null) {
                    ShanhaiMod.LOGGER.warn("{} holo_mp_state_decode_failed len={}", PREFIX, payload.length);
                    return;
                }
                final ClientSink sink = clientSink;
                if (sink == null) {
                    return;
                }
                sink.acceptState(s.id(), s.enabled(), s.layout(), s.distance(), s.facing(),
                        s.surroundPreset(), s.panelOpen());
                ShanhaiMod.LOGGER.info("{} holo_mp_state_recv who={} enabled={} layout={} distance={}",
                        PREFIX, s.id(), s.enabled(), s.layout(), s.distance());
            });
            ctx.setPacketHandled(true);
        }
    }

    /**
     * 🆕 C2S：客户端把自己的<b>全部设置</b>报上来（DEF + STATE 拼一条，102 字节）。
     *
     * <p>🔴 服务端收到之后<b>只做两件事</b>：① 用 {@code ctx.getSender()} 认人（不读载荷里的 UUID）；
     * ② {@link #moduleInInventory} 复核。然后才广播。
     */
    public static final class HoloPublishMessage {

        private final byte[] def;
        private final byte[] state;

        public HoloPublishMessage(java.util.UUID self, boolean enabled, int layout, float distance,
                                  int facing, int surroundPreset, float[] params, boolean panelOpen) {
            this.def = ShanhaiHoloRemoteCodec.encodeDef(self, surroundPreset, params);
            this.state = ShanhaiHoloRemoteCodec.encodeState(self, enabled, layout, distance, facing,
                    surroundPreset, panelOpen);
        }

        private HoloPublishMessage(byte[] def, byte[] state) {
            this.def = def;
            this.state = state;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBytes(def);
            buf.writeBytes(state);
        }

        public static HoloPublishMessage decode(FriendlyByteBuf buf) {
            final byte[] d = new byte[ShanhaiHoloRemoteCodec.DEF_BYTES];
            final byte[] s = new byte[ShanhaiHoloRemoteCodec.STATE_BYTES];
            buf.readBytes(d);
            buf.readBytes(s);
            return new HoloPublishMessage(d, s);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 🔴 排到服务端主线程：要念背包、要遍历玩家列表发消息，都不许在网络线程做。
            ctx.enqueueWork(() -> ShanhaiHoloRemoteSync.onPublish(ctx.getSender(), def, state));
            ctx.setPacketHandled(true);
        }
    }

    /**
     * 🆕 S2C：<b>服务端请这个客户端再报一次</b>（16 字节 = 它自己的 UUID，用来在日志里对得上人）。
     *
     * <p>载荷里的 UUID 是服务端填的"收件人自己"，客户端只把它打进日志 ——
     * <b>不参与任何判断</b>（客户端本来就该知道自己在哪台服上是谁）。
     */
    public static final class HoloAskMessage {

        private final byte[] payload;

        public HoloAskMessage(java.util.UUID who) {
            this.payload = ShanhaiHoloRemoteCodec.encodeAsk(who);
        }

        private HoloAskMessage(byte[] payload) {
            this.payload = payload;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBytes(payload);
        }

        public static HoloAskMessage decode(FriendlyByteBuf buf) {
            final byte[] raw = new byte[ShanhaiHoloRemoteCodec.ASK_BYTES];
            buf.readBytes(raw);
            return new HoloAskMessage(raw);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final java.util.UUID who = ShanhaiHoloRemoteCodec.decodeAsk(payload);
                final ClientSink sink = clientSink;
                if (sink == null) {
                    return;
                }
                ShanhaiMod.LOGGER.info("{} holo_mp_ask_recv who={}", PREFIX, who);
                sink.republish();
            });
            ctx.setPacketHandled(true);
        }
    }
}
