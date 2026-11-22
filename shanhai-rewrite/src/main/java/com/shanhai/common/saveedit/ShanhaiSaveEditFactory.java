package com.shanhai.common.saveedit;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.shanhai.ShanhaiMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「存档直改」面板的<b>服务端权威入口</b>（LDLib {@code UIFactory}，与本工程
 * {@code ShanhaiRealityInputFactory} / {@code ShanhaiCreativeItemsFactory} 同一套写法）。
 *
 * <h2>一、为什么必须走 UIFactory</h2>
 * 纯客户端 new 一个 {@code ModularUI} 时，LDLib 的控件动作在服务端只认
 * {@code player.containerMenu instanceof ModularUIContainer}，否则<b>直接丢弃、不报错</b>
 * —— 本工程在这个坑上付过账。走 UIFactory 会在服务端建一份 UI、开一个真的
 * {@code ModularUIContainer}，客户端那份由 {@link #readHolderFromSyncData} 重建。
 *
 * <h2>二、🔴 {@link #open(ServerPlayer)} 里做的那件"先保存"</h2>
 * 用户原话：「<b>打开面板时先保存存档</b>」。所以开面板这一步<b>立刻</b>调
 * {@code MinecraftServer#saveEverything(true, true, false)}（{@code flush=true} 才会走
 * {@code ChunkMap#saveAllChunks(true)} → {@code flushWorker()} 把异步写盘等干净）。
 * 好处是「读当前值」读到的一定是<b>刚存过的那一份</b>，而不是上一次自动存的旧值。
 * 保存失败不阻止开面板（只是状态行如实说"保存失败"，读到的可能是旧数据）。
 *
 * <h2>三、状态/摘要为什么是"服务端算好的字面串"</h2>
 * 与 {@code ShanhaiRealityInputFactory} 同一条口径：服务端<b>没有语言表</b>
 * （{@code Component.translatable(...).getString()} 会返回裸键/英文），而这些行是
 * 服务端权威的（靠 LDLib {@code LabelWidget} 的 update-info 推给客户端）。
 * 所以它们一律是<b>中文字面串</b>，两侧渲染逐字节相同；只有按钮/说明/提示这些
 * "客户端自己知道的字"才走 {@code setClientSideWidget()}。
 */
public final class ShanhaiSaveEditFactory extends UIFactory<ShanhaiSaveEditFactory.Holder> {

    /** 工厂 id（注册表键，也是"开界面"包里的分派键）。 */
    public static final ResourceLocation ID = new ResourceLocation("shanhai", "save_edit");

    public static final ShanhaiSaveEditFactory INSTANCE = new ShanhaiSaveEditFactory();

    /**
     * <b>每个玩家一份 Holder，复用同一个对象</b>（用户原话：「我要暂存一直存到玩家关闭这个存档修改面板，
     * 不然就一直存在」）。
     *
     * <h2>为什么必须是这张表、而不是 {@code open()} 里 {@code new} 一个</h2>
     * 页面上的"暂存"（页① 的 12 个格子、页④⑤⑥⑦ 的动作、物品栏逐格补丁）全都挂在这个 Holder 上。
     * 旧写法每次 {@code open()} 都 {@code new Holder(...)} ⇒ 只要面板被重建一次，暂存就没了。
     *
     * <h2>什么时候移除（"只有面板真关闭时才移除"）</h2>
     * <ul>
     *   <li>{@link #createUITemplate} 里挂的 {@code ModularUI#registerCloseListener}：
     *       容器被移除（= 面板真关）时 LDLib 会调它，见 {@code ModularUIContainer#removed} 的字节码
     *       （{@code invokespecial AbstractContainerMenu.removed} 之后紧跟
     *       {@code ModularUI.triggerCloseListeners}）；</li>
     *   <li>关闭确认层点「确认关闭（丢弃暂存）」时再兜一次（幂等）。</li>
     * </ul>
     *
     * <h2>🔴 换存档/换世界时必须丢弃（安全阀）</h2>
     * Holder 里握着 {@link ShanhaiSaveEditRunContext}（三个绝对路径）。复用一份属于<b>别的世界</b>的
     * Holder，会把改动写进旧世界的文件 —— 所以 {@link #open} 每次都比对 {@code worldDir}，
     * 不一样就丢掉旧的重新开（日志 {@code holder_ctx_stale}）。
     */
    private static final Map<UUID, Holder> HOLDERS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 状态行文案（字面串，见类注释 §三）

    /** 面板刚开、存档已保存。 */
    public static final String STATUS_OPEN_SAVED = "存档已保存 · 点「读当前值」看现状";
    /** 面板刚开、存档没保存成功。 */
    public static final String STATUS_OPEN_SAVE_FAILED = "存档没保存成功（看日志）· 读到的是旧数据";
    /** 读到了。 */
    public static final String STATUS_READ_OK = "已读当前值";
    /** 还没读到。 */
    public static final String STATUS_NO_READ = "还没读 · 点「读当前值」";
    /** 暂存成功的前缀（后面接内容）。 */
    public static final String STATUS_STAGED = "已暂存（点「确认修改并重载」才真落盘）：";
    /** 没有任何改动。 */
    public static final String STATUS_NO_CHANGE = "没有填任何要改的格子";
    /** 正在进行。 */
    public static final String STATUS_RUNNING = "正在重载这个世界…";
    /** 并发被拒。 */
    public static final String STATUS_BUSY = "另一次存档直改还没结束，等它完";
    /** 只在单机可用。 */
    public static final String STATUS_NOT_SINGLEPLAYER = "只支持单机存档（这条流程要用客户端主线程）";
    /** 存档带服务器资源包。 */
    public static final String STATUS_HAS_SERVER_PACK = "这个存档带 server-resources，本期不支持直接重载（请手动退出重进）";
    /** 打开面板时的横幅。 */
    public static final String STATUS_FAILED = "启动失败：";

    private ShanhaiSaveEditFactory() {
        super(ID);
    }

    /** 幂等注册（两侧各调一次；开面板时再兜一次）。 */
    public static void registerOnce() {
        if (!UIFactory.FACTORIES.containsKey(ID)) {
            UIFactory.register(INSTANCE);
            ShanhaiMod.LOGGER.info("{} uifactory_registered id={} factories_now={}",
                    ShanhaiSaveEditFlow.PREFIX, ID, UIFactory.FACTORIES.size());
        }
    }

    // ================================================================== 开面板

    /**
     * 开面板（<b>只在服务端调用才有意义</b>）。
     *
     * <p>顺序：① 先存一次（用户口径）→ ② 抓路径快照 → ③ 开 UI。
     */
    public static boolean open(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        registerOnce();
        final MinecraftServer server = player.getServer();
        boolean saved = false;
        if (server != null) {
            try {
                // 🔴 flush 必须 true：只有它会 flushWorker()，把 IOWorker 的异步写盘等干净
                saved = server.saveEverything(true, true, false);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} open_save_failed player={} err={}",
                        ShanhaiSaveEditFlow.PREFIX, player.getName().getString(), t.toString());
            }
        }
        final ShanhaiSaveEditRunContext ctx = capture(player);
        ShanhaiMod.LOGGER.info("{} panel_open saved={} {}", ShanhaiSaveEditFlow.PREFIX, saved,
                ctx == null ? "<no-context>" : ctx.line());
        if (ctx == null) {
            ShanhaiMod.LOGGER.error("{} panel_open_failed reason=no_context player={}",
                    ShanhaiSaveEditFlow.PREFIX, player.getName().getString());
            return false;
        }
        final String status = saved ? STATUS_OPEN_SAVED : STATUS_OPEN_SAVE_FAILED;
        final Holder fresh = new Holder(false, ctx, status);
        Holder holder = HOLDERS.computeIfAbsent(player.getUUID(), id -> fresh);
        if (holder != fresh && !sameWorld(holder.ctx(), ctx)) {
            // 🔴 换存档/换世界（或换号）⇒ 旧 Holder 里的路径与暂存一律作废
            ShanhaiMod.LOGGER.warn("{} holder_ctx_stale player={} from={} to={} staged_discarded={}",
                    ShanhaiSaveEditFlow.PREFIX, player.getName().getString(),
                    worldDesc(holder.ctx()), worldDesc(ctx), stagedCount(holder));
            HOLDERS.put(player.getUUID(), fresh);
            holder = fresh;
        }
        // 重新打开时状态行刷回"刚保存"（暂存本身不动）
        holder.setStatus(status);
        final boolean ok = INSTANCE.openUI(holder, player);
        ShanhaiMod.LOGGER.info("{} openUI ok={} player={} playerdata_exists={} holder_reused={} staged={} {}",
                ShanhaiSaveEditFlow.PREFIX, ok, player.getName().getString(),
                Files.isRegularFile(ctx.playerDataFile), holder != fresh, stagedCount(holder),
                stagedBreakdown(holder));
        return ok;
    }

    /** 两份 ctx 指的是不是同一个世界目录（{@code null} 只与 {@code null} 相同）。 */
    private static boolean sameWorld(ShanhaiSaveEditRunContext a, ShanhaiSaveEditRunContext b) {
        if (a == null || b == null) {
            return a == b;
        }
        final Path wa = a.worldDir;
        final Path wb = b.worldDir;
        if (wa == null || wb == null) {
            return wa == wb;
        }
        try {
            return wa.toAbsolutePath().normalize().equals(wb.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 日志里那个世界目录（拿不到 ⇒ {@code -}）。 */
    private static String worldDesc(ShanhaiSaveEditRunContext ctx) {
        return ctx == null || ctx.worldDir == null ? "-" : ctx.worldDir.toString();
    }

    /**
     * 把三个路径抓下来（<b>必须在停服前</b>，见 {@link ShanhaiSaveEditRunContext}）。
     *
     * <p>🔴 {@code levelId} 必须过 {@link ShanhaiSaveEditReopenGate#resolve(java.nio.file.Path)}：
     * 1.20.1 的 {@code LevelResource.ROOT} 尾段<b>就是 {@code "."}</b>，原来那句
     * {@code root.getFileName()} 会拿到 {@code "."}，把重开指到 {@code saves\.} 这个不存在的目录
     * （2026-11-14 09:57 的现场）。算不出来时<b>依然返回 ctx</b>（面板还能只读），
     * 但会打 {@code level_id_unresolved candidates=[…]}，且 {@code Bridge} 会在动任何东西之前拒绝确认。
     *
     * @return 拿不到（没有 server / 没有存档目录）就 {@code null}
     */
    public static ShanhaiSaveEditRunContext capture(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        try {
            final Path root = server.getWorldPath(LevelResource.ROOT);
            if (root == null) {
                ShanhaiMod.LOGGER.error("{} capture_failed reason=no_world_root player={}",
                        ShanhaiSaveEditFlow.PREFIX, player.getName().getString());
                return null;
            }
            final ShanhaiSaveEditReopenGate.Resolution res = ShanhaiSaveEditReopenGate.resolve(root);
            // 存档目录以"过了 level.dat 闸"的那个为准；没算出来时退回归一化后的 ROOT（只读仍然能用）
            final Path worldDir = res.worldDir != null ? res.worldDir : root.toAbsolutePath().normalize();
            if (!res.resolved()) {
                ShanhaiMod.LOGGER.error("{} level_id_unresolved candidates={} world_dir={} raw_root={} why={}",
                        ShanhaiSaveEditFlow.PREFIX, res.candidatesLine(), worldDir, root, res.why);
            }
            final Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            final Path playerDataFile = playerDataDir.resolve(player.getStringUUID() + ".dat");
            final Path backupRoot = server.getServerDirectory().toPath()
                    .resolve("temp").resolve("saveedit-backup");
            final boolean hasServerResources = Files.isDirectory(worldDir.resolve("server-resources"));
            return new ShanhaiSaveEditRunContext(res.levelId, res.candidates, worldDir, playerDataFile,
                    backupRoot, player.getName().getString(), player.getStringUUID(), hasServerResources);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} capture_failed player={} err={}",
                    ShanhaiSaveEditFlow.PREFIX, player.getName().getString(), t.toString());
            return null;
        }
    }

    // ================================================================== UIFactory

    @Override
    protected ModularUI createUITemplate(Holder holder, Player player) {
        // ⚠️ 两侧各跑一次：只许建"两侧都安全"的件
        final ModularUI ui = new ModularUI(ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H, holder, player)
                .widget(ShanhaiSaveEditPanel.create(holder, player));
        // 🔴 面板<b>真关闭</b>的权威信号：LDLib 在容器被移除时会调 triggerCloseListeners()
        //    （ModularUIContainer#removed 的字节码：invokespecial AbstractContainerMenu.removed 之后
        //     紧跟 invokevirtual ModularUI.triggerCloseListeners）。⇒ 暂存"只活到关闭面板"。
        //    只挂服务端那一份：复用表在服务端，客户端那份 Holder 是远程镜像（它没有暂存）。
        if (!holder.isRemote()) {
            ui.registerCloseListener(() -> deferForget(holder, player));
        }
        return ui;
    }

    /**
     * close listener 的落地：<b>延后一 tick 再判</b>，别把"重开面板"误判成"真关"。
     *
     * <p>为什么必须延后：{@code UIFactory#openUI} 装新容器之前会先
     * {@code ServerPlayer.m_6915_()}（= {@code doCloseContainer()}）把旧容器摘掉
     * ⇒ 旧容器的 {@code removed} 先触发一次本 listener。若在这里立刻清，就变成
     * "重开一次面板、暂存没了"—— 正是本期要修的那个现象。
     * 判定方式：下一 tick 看这个玩家的当前容器是不是<b>就是这个 Holder</b> 的 UI（{@link #isCurrentUi}）：
     * <ul>
     *   <li>是 ⇒ 说明是"重开"，<b>保留</b>（日志 {@code holder_kept}）；</li>
     *   <li>不是 ⇒ 真的关了，{@link #forget(Holder)} 清掉。</li>
     * </ul>
     */
    private static void deferForget(Holder holder, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            forget(holder);          // 客户端那份不在这张表里；真要是服务端没玩家对象就直接清
            return;
        }
        final MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            forget(holder);
            return;
        }
        try {
            server.execute(() -> {
                if (isCurrentUi(serverPlayer, holder)) {
                    ShanhaiMod.LOGGER.info("{} holder_kept reason=reopened player={} staged={} {}",
                            ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(),
                            stagedCount(holder), stagedBreakdown(holder));
                    return;
                }
                forget(holder);
            });
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} close_listener_defer_failed err={} ⇒ 直接清",
                    ShanhaiSaveEditFlow.PREFIX, t.toString());
            forget(holder);
        }
    }

    /** 这个玩家此刻的容器是不是<b>就是这个 Holder</b> 的 ModularUI（= 面板还开着）。 */
    private static boolean isCurrentUi(ServerPlayer player, Holder holder) {
        try {
            return player.containerMenu instanceof com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer c
                    && c.getModularUI() != null
                    && c.getModularUI().holder == holder;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== Holder 的取出 / 丢弃 + 暂存读数

    /**
     * 面板<b>真关闭</b>时把 Holder 从复用表里摘掉（暂存随之清空）。<b>幂等</b>。
     *
     * <p>两个触发点：① {@link ModularUI#registerCloseListener}（容器被移除，见上）；② 关闭确认层
     * 点「确认关闭（丢弃暂存）」时在服务端那一下（兜底：容器路径万一没走到也清得掉）。
     *
     * @return 真的从表里摘掉了一个（{@code false} = 本来就不在表里 / 已经清过）
     */
    public static boolean forget(Holder holder) {
        if (holder == null) {
            return false;
        }
        boolean removed = false;
        for (Iterator<Map.Entry<UUID, Holder>> it = HOLDERS.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() == holder) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            final int staged = stagedCount(holder);
            final String breakdown = stagedBreakdown(holder);
            holder.clearStaged();
            ShanhaiMod.LOGGER.info("{} panel_closed holder_dropped=true staged_discarded={} {}",
                    ShanhaiSaveEditFlow.PREFIX, staged, breakdown);
        }
        // 🔴 面板真关了 ⇒ 一定放锁（只放"还停在停服之前那三步"的那一次）。
        //    为什么必须在这里做：全局锁是在 ShanhaiSaveEditBridge.confirm 里抢的，而"停服窗口打开
        //    之前"的任何中断（抛异常 / 玩家把面板关掉 / 点确认关闭 / 取消）都没有第二个放锁的人。
        //    现场（GTL山海9.10test / 2026-11-15 14:13:03.743→04）：begin 之后 1 ms 一句日志 NPE，
        //    锁没放 ⇒ 之后每一次「确认修改并重载」都是 confirm_rejected reason=busy。
        releaseFlowFor(holder, "panel_closed");
        return removed;
    }

    /**
     * 把"这一次运行还挂在全局锁上、而它的面板已经没了"这件事收掉，并把那一行日志打出去。
     *
     * <p>幂等：没放（不是它 / 已经放过 / 阶段已经过窗口）就什么都不写。
     * 只放"停服之前"那三步（{@code ShanhaiSaveEditFlow#preWindow}）—— 窗口一开，世界已经停了，
     * 那条路必须由 {@code ShanhaiSaveEditReload} 自己走完或自己 fail，这里不许插手。
     */
    private static void releaseFlowFor(Holder holder, String reason) {
        if (!ShanhaiSaveEditFlow.releaseIfOwned(holder, reason)) {
            return;
        }
        final String line = ShanhaiSaveEditFlow.takeNotify();
        if (line != null && !line.isEmpty()) {
            ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX, line);
        }
    }

    /** 按玩家摘掉（{@code /saveedit} 重开时兜底用；面板真关闭那条路走的是 close listener）。 */
    public static boolean forgetPlayer(ServerPlayer player) {
        return player != null && forget(HOLDERS.get(player.getUUID()));
    }

    /** 这个 Holder 上有没有任何暂存（<b>纯 JDK</b>：只数容器，不碰 UI/MC ⇒ 离线可断言）。 */
    public static boolean hasAnyStaged(Holder holder) {
        return stagedCount(holder) > 0;
    }

    /**
     * 「已暂存 N 处」里的那个 N —— <b>关闭确认层、日志、判据共用这一个数</b>。
     *
     * <p>口径：① 玩家页整份算 <b>1 处</b>（不管里面填了几个字段、几格物品栏），其余五类
     * <b>一条请求算 1 处</b>。⇒ 与 {@link #stagedBreakdown(Holder)} 的六项之和恒等。
     */
    public static int stagedCount(Holder holder) {
        if (holder == null) {
            return 0;
        }
        int n = playerPageStaged(holder) ? 1 : 0;
        n += holder.stagedEntity.size();
        n += holder.stagedAdvancement.size();
        n += holder.stagedStats.size();
        n += holder.stagedEntityPatch.size();
        n += holder.stagedAudit.size();
        return n;
    }

    /**
     * 明细一行（日志/判据用，六个数相加 == {@link #stagedCount(Holder)}）：
     * {@code 玩家页1/实体0/成就2/统计0/实体字段1/体检0}。
     */
    public static String stagedBreakdown(Holder holder) {
        if (holder == null) {
            return "玩家页0/实体0/成就0/统计0/实体字段0/体检0";
        }
        return "玩家页" + (playerPageStaged(holder) ? 1 : 0)
                + "/实体" + holder.stagedEntity.size()
                + "/成就" + holder.stagedAdvancement.size()
                + "/统计" + holder.stagedStats.size()
                + "/实体字段" + holder.stagedEntityPatch.size()
                + "/体检" + holder.stagedAudit.size();
    }

    /** 玩家页那一份到底有没有要改的东西（{@code null} / 全空 ⇒ 没有）。 */
    private static boolean playerPageStaged(Holder holder) {
        return holder != null && holder.staged != null && holder.staged.hasPlayerFields();
    }

    /** 取容器里最后一条（{@code null} = 空）。 */
    private static <T> T last(Map<String, T> map) {
        T out = null;
        for (T value : map.values()) {
            out = value;
        }
        return out;
    }

    @Override
    protected Holder readHolderFromSyncData(FriendlyByteBuf buf) {
        buf.readVarInt();
        return new Holder(true, null, STATUS_NO_READ);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buf, Holder holder) {
        // 客户端那份<b>不需要</b>路径（真落盘只在服务端那份 Holder 上做）⇒ 只带一个版本号。
        // v2 = 面板扩到三页（页号/BE 模式各侧自持，当前值靠 LabelWidget push）
        // v3 = 扩到六页（+ 实体/成就/统计：模式、暂存请求、明细行同样各侧自持 + push）
        // v4 = 扩到七页（+ 存档体检：清单/摘要/选中同样服务端权威 + push；⚪ 客户端那份 holder 不持有扫描结果）
        // v5 = 第 ⑦ 页改语义（白名单之外一律算异常，7 行列表 + 翻页 + 搜索；双份登记那两个动作删掉）
        buf.writeVarInt(5);
    }

    // ================================================================== Holder

    /**
     * 面板持有者（两侧各一份；{@code remote} 由构造现场决定，不查全局 dist）。
     *
     * <p>数据：路径快照（只在服务端那份上有）、暂存的玩家改动、状态行、三行摘要，
     * 以及本期新增的三页状态（当前页号 / BE 模式 / level.dat 当前值 / 当前方块）。
     * 状态行、摘要、当前值都是<b>服务端权威的字面串</b>（见类注释 §三）。
     */
    public static final class Holder implements IUIHolder {

        private final boolean remote;
        private final ShanhaiSaveEditRunContext ctx;

        private ShanhaiPlayerDataEdit staged;
        private String status;
        private final String[] summary = new String[] {"", "", ""};
        private boolean readOnce;

        // ── 三页共存用的状态（两侧各一份；服务端那份才是权威）
        private int page;
        private String beAction = ShanhaiRegionEdit.BE_NONE;
        private ShanhaiLevelDataSnapshot levelSnapshot;
        private String blockCurrent = "";
        private String blockEntityCurrent = "";

        // ── 第四期加的三页（页面名/明细行都是服务端权威的字面串，靠 LabelWidget push）
        /** 明细行数（面板与这里共用同一个数，见 {@code ShanhaiSaveEditLayout.NEW_LINES}）。 */
        public static final int LINES = ShanhaiSaveEditLayout.NEW_LINES;
        /** 统计页的动作：{@code set} / {@code clear}。 */
        private String statsAction = ShanhaiStatsEdit.ACTION_SET;
        /**
         * ④ 实体页暂存的删除请求：<b>键 = 维度 + 区 + 定位方式</b>（见 {@link #entityKey}）。
         *
         * <p>🔴 从"单槽"改成<b>累积容器</b>：一次面板会话里可以暂存删好几条，直到关闭面板为止。
         * 同键再暂存 = <b>覆盖</b>（后改的盖前改的，与物品栏"同槽后改的盖前改的"同一条口径）。
         */
        private final Map<String, ShanhaiEntityEdit.Request> stagedEntity = new LinkedHashMap<>();
        /** ⑤ 成就页：<b>键 = 成就 id</b>（同一个成就给/撤只留最后一次）。 */
        private final Map<String, ShanhaiAdvancementEdit.Request> stagedAdvancement = new LinkedHashMap<>();
        /** ⑥ 统计页：<b>键 = 分类 + "/" + 键</b>（同一条统计设值/删只留最后一次）。 */
        private final Map<String, ShanhaiStatsEdit.Request> stagedStats = new LinkedHashMap<>();
        private final String[] advancementLines = new String[LINES];
        private final String[] statsLines = new String[LINES];

        // ── 第五期：第 ④ 页（实体）改成"按维度列表 → 点一行 → 覆盖层里改 / 删"
        /**
         * 上一次「列出」的读数（<b>只有服务端那份有</b>：客户端靠行按钮的 update-info 看字）。
         *
         * <p>行 → {@code Row}（含 uuid）的对应关系必须留在服务端：客户端点第 3 行时，
         * 它自己并不知道第 3 行是哪个 uuid。
         */
        private ShanhaiEntityEdit.DimensionListing entityListing;
        /** 概况那一行（服务端算好的字面串）。 */
        private String entitySummary = "";
        /** 残留开关旁边那个计数（"已折叠 N 条"）。 */
        private String entityResidueNote = "";
        /** 视觉残留是"折叠"还是"显示"（默认折叠）。两侧各持一份，点一下两侧都翻。 */
        private boolean entityIncludeResidue;
        /**
         * 当前是第几页（0 起）。🔴 <b>服务端权威</b>：客户端只显示被推过来的页码与行；
         * 点「上一页 / 下一页」时客户端那次什么都不做（{@code isServerSide()} 拦掉），
         * 等服务端重列之后把新的一页推回来。
         */
        private int entityPage;
        /** 页码那一行（{@code 第 2/52 页}，服务端算好推过来）。 */
        private String entityPageText = "";
        /** 页码旁边的计数（{@code 共 358 条 · 本页 8-14}）。 */
        private String entityPageCountText = "";
        /**
         * 覆盖层「写入」暂存下来的 <b>10 项</b>补丁（服务端那份才有；空表 = 没按过写入）。
         *
         * <p>为什么要暂存：数据层只许在停服窗口里写，而窗口里能执行的东西只能通过
         * {@link ShanhaiPlayerDataEdit} 带进去 —— 所以这里存一份，"确认修改并重载"时交出去
         * （{@code ShanhaiSaveEditWindow} 的 ⑤b 段）。
         *
         * <p>🔴 <b>键 = 维度 + UUID</b>；同一个目标先后按两次「写入」时，两份补丁按<b>字段</b>合并
         * （{@link #mergePatch}：后写的非空字段盖前写的）。这样"先改 Pos、再改 Health"两条都在，
         * 而"先改 Pos、又改 Pos"只留最后一次。不同目标各占一条（{@link #stagedEntityPatchEntries()}）。
         */
        private final Map<String, EntityPatch> stagedEntityPatch = new LinkedHashMap<>();

        /** 一份"某个实体要改哪几项"：目标（维度 + UUID）+ 合并后的补丁。 */
        public record EntityPatch(String dimension, String uuid, ShanhaiEntityEdit.Patch patch) {

            /** 一行读数（日志用）。 */
            public String line() {
                return "实体字段 " + (this.patch == null ? "[]" : this.patch.keys())
                        + " @ " + this.dimension + " / " + this.uuid;
            }
        }

        // ── 第五期加的第 ⑦ 页（存档体检 · 白名单之外一律算异常，可逐条删）
        /**
         * 体检页暂存的动作（累积容器；<b>键 = 归一后的相对路径</b>，空表 = 没按过「删除选中项」）。
         *
         * <p>扫描结果/选中项/页码都在 {@code ShanhaiSaveEditPanel} 那一侧（服务端那份），
         * 不在这里 —— holder 只管"跨面板存在的那一份"（暂存清单）。
         */
        private final Map<String, com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request> stagedAudit
                = new LinkedHashMap<>();

        public Holder(boolean remote, ShanhaiSaveEditRunContext ctx, String status) {
            this.remote = remote;
            this.ctx = ctx;
            this.status = status == null ? "" : status;
        }

        public ShanhaiSaveEditRunContext ctx() {
            return this.ctx;
        }

        public String status() {
            return this.status;
        }

        public void setStatus(String value) {
            this.status = value == null ? "" : value;
        }

        public boolean readOnce() {
            return this.readOnce;
        }

        public ShanhaiPlayerDataEdit staged() {
            return this.staged;
        }

        public void setStaged(ShanhaiPlayerDataEdit edit) {
            this.staged = edit;
        }

        /** 摘要第 {@code index} 行（0/1/2）。<b>永不返回 null</b>（LDLib 的 LabelWidget 会对 null NPE）。 */
        public String summary(int index) {
            if (index < 0 || index >= this.summary.length) {
                return "";
            }
            final String s = this.summary[index];
            return s == null ? "" : s;
        }

        /** 用一份摘要填三行。 */
        public void setSummary(ShanhaiPlayerDataSnapshot snap) {
            this.summary[0] = snap == null ? "" : snap.line1();
            this.summary[1] = snap == null ? "" : snap.line2();
            this.summary[2] = snap == null ? "" : snap.line3();
            this.readOnce = snap != null && snap.present;
        }

        // ──────────────────────────────────────────────────────────── 三页状态

        /** 当前页（0 玩家 / 1 世界 / 2 方块）。 */
        public int page() {
            return this.page;
        }

        public void setPage(int page) {
            this.page = Math.max(0, Math.min(ShanhaiSaveEditLayout.PAGE_COUNT - 1, page));
        }

        /** 方块实体的动作（{@link ShanhaiRegionEdit#BE_NONE}/{@code BE_CLEAR}/{@code BE_REPLACE}）。 */
        public String beAction() {
            return this.beAction;
        }

        public void setBeAction(String action) {
            this.beAction = action == null ? ShanhaiRegionEdit.BE_NONE : action;
        }

        /** level.dat 的当前值快照（只有服务端那份有；客户端靠 LabelWidget push 显示）。 */
        public void setLevelSnapshot(ShanhaiLevelDataSnapshot snapshot) {
            this.levelSnapshot = snapshot;
        }

        public ShanhaiLevelDataSnapshot levelSnapshot() {
            return this.levelSnapshot;
        }

        /** 某个 level.dat 字段的当前值（永不 {@code null}；取不到 = 空串）。 */
        public String levelCurrent(String id) {
            return this.levelSnapshot == null ? "" : this.levelSnapshot.value(id);
        }

        /** 第 ③ 页「当前」那一行的字（服务端读盘后写进来）。 */
        public String blockCurrent() {
            return this.blockCurrent == null ? "" : this.blockCurrent;
        }

        public void setBlockCurrent(String value) {
            this.blockCurrent = value == null ? "" : value;
        }

        /** 第 ③ 页「当前BE」那一行的字。 */
        public String blockEntityCurrent() {
            return this.blockEntityCurrent == null ? "" : this.blockEntityCurrent;
        }

        public void setBlockEntityCurrent(String value) {
            this.blockEntityCurrent = value == null ? "" : value;
        }

        // ──────────────────────────────────────────────────────────── ④⑤⑥ 页

        /** 统计页的动作。 */
        public String statsAction() {
            return this.statsAction;
        }

        public void setStatsAction(String action) {
            this.statsAction = action == null ? ShanhaiStatsEdit.ACTION_SET : action;
        }

        /** 容器里<b>最后一条</b>实体删除请求（旧的单槽 API；完整清单见 {@link #stagedEntityRequests()}）。 */
        public ShanhaiEntityEdit.Request stagedEntity() {
            return last(this.stagedEntity);
        }

        /** 实体删除请求的<b>完整清单</b>（累积到面板关闭；Phase 2 落盘要遍历它）。 */
        public List<ShanhaiEntityEdit.Request> stagedEntityRequests() {
            return new ArrayList<>(this.stagedEntity.values());
        }

        /** 实体积了几条（红点/计数用）。 */
        public int stagedEntityCount() {
            return this.stagedEntity.size();
        }

        /**
         * 暂存一条删除请求（<b>累加</b>；同键覆盖）。
         *
         * <p>{@code null} / {@link ShanhaiEntityEdit.Request#isEmpty()} ⇒ <b>清空这一页</b>
         * （与旧语义"传 null = 清空暂存"一致）。
         */
        public void setStagedEntity(ShanhaiEntityEdit.Request request) {
            if (request == null || request.isEmpty()) {
                this.stagedEntity.clear();
                return;
            }
            this.stagedEntity.put(entityKey(request), request);
        }

        /** 撤掉这一条（{@code key} = {@link #entityKey}；返回是否真的撤掉了）。 */
        public boolean cancelStagedEntity(String key) {
            return key != null && this.stagedEntity.remove(key) != null;
        }

        /** 容器里<b>最后一条</b>成就请求（旧的单槽 API；完整清单见 {@link #stagedAdvancementRequests()}）。 */
        public ShanhaiAdvancementEdit.Request stagedAdvancement() {
            return last(this.stagedAdvancement);
        }

        /** 成就请求的完整清单。 */
        public List<ShanhaiAdvancementEdit.Request> stagedAdvancementRequests() {
            return new ArrayList<>(this.stagedAdvancement.values());
        }

        /** 成就暂存了几条。 */
        public int stagedAdvancementCount() {
            return this.stagedAdvancement.size();
        }

        /** 暂存一条成就请求（<b>累加</b>；同一个成就只留最后一次）。{@code null}/空 ⇒ 清空这一页。 */
        public void setStagedAdvancement(ShanhaiAdvancementEdit.Request request) {
            if (request == null || request.isEmpty()) {
                this.stagedAdvancement.clear();
                return;
            }
            this.stagedAdvancement.put(advancementKey(request), request);
        }

        /** 容器里<b>最后一条</b>统计请求（旧的单槽 API；完整清单见 {@link #stagedStatsRequests()}）。 */
        public ShanhaiStatsEdit.Request stagedStats() {
            return last(this.stagedStats);
        }

        /** 统计请求的完整清单。 */
        public List<ShanhaiStatsEdit.Request> stagedStatsRequests() {
            return new ArrayList<>(this.stagedStats.values());
        }

        /** 统计暂存了几条。 */
        public int stagedStatsCount() {
            return this.stagedStats.size();
        }

        /** 暂存一条统计请求（<b>累加</b>；同一条统计只留最后一次）。{@code null}/空 ⇒ 清空这一页。 */
        public void setStagedStats(ShanhaiStatsEdit.Request request) {
            if (request == null || request.isEmpty()) {
                this.stagedStats.clear();
                return;
            }
            this.stagedStats.put(statsKey(request), request);
        }

        /**
         * 上一次「列出」的读数（服务端那份才有；{@code null} = 还没列过）。
         *
         * <p>面板靠它把"点了第几行"翻译成"哪个 uuid"。第五期起第 ④ 页的明细不再是一大段只读文字
         * （那一排已经是可点的行按钮了），所以 {@code entityLines} 整组字段已删除。
         */
        public ShanhaiEntityEdit.DimensionListing entityListing() {
            return this.entityListing;
        }

        public void setEntityListing(ShanhaiEntityEdit.DimensionListing listing) {
            this.entityListing = listing;
        }

        /** 概况那一行（服务端算好的字面串，永不 {@code null}）。 */
        public String entitySummary() {
            return this.entitySummary == null ? "" : this.entitySummary;
        }

        public void setEntitySummary(String value) {
            this.entitySummary = value == null ? "" : value;
        }

        /** 残留开关旁边那个计数（"已折叠 N 条"）。 */
        public String entityResidueNote() {
            return this.entityResidueNote == null ? "" : this.entityResidueNote;
        }

        public void setEntityResidueNote(String value) {
            this.entityResidueNote = value == null ? "" : value;
        }

        /** 视觉残留是折叠（{@code false}，默认）还是显示（{@code true}）。 */
        public boolean entityIncludeResidue() {
            return this.entityIncludeResidue;
        }

        public void setEntityIncludeResidue(boolean include) {
            this.entityIncludeResidue = include;
        }

        // ──────────────────────────────────────────────────────────── 第 ④ 页：翻页 + 暂存的写入

        /** 当前第几页（0 起）。 */
        public int entityPage() {
            return this.entityPage;
        }

        /** 设页号（夹在 [0, 页数-1]；页数由 {@code matched} 与每页行数算，见 Ui#clampPage）。 */
        public void setEntityPage(int page) {
            this.entityPage = Math.max(0, page);
        }

        /** 页码那一行（服务端算好的字面串）。 */
        public String entityPageText() {
            return this.entityPageText == null ? "" : this.entityPageText;
        }

        public void setEntityPageText(String value) {
            this.entityPageText = value == null ? "" : value;
        }

        /** 页码旁边的计数（服务端算好的字面串）。 */
        public String entityPageCountText() {
            return this.entityPageCountText == null ? "" : this.entityPageCountText;
        }

        public void setEntityPageCountText(String value) {
            this.entityPageCountText = value == null ? "" : value;
        }

        /**
         * 容器里<b>最后一份</b>实体字段补丁（旧的单槽 API，{@code null} = 没按过「写入」）。
         *
         * <p>⚠️ 累积之后可能有多份（不同目标各一份）⇒ 真正一次落盘多目标的清单见
         * {@link #stagedEntityPatchEntries()}；旧三件套（本方法 + 维度 + UUID）永远指向<b>同一个</b>目标。
         */
        public ShanhaiEntityEdit.Patch stagedEntityPatch() {
            final EntityPatch entry = last(this.stagedEntityPatch);
            return entry == null ? null : entry.patch();
        }

        /** 暂存补丁的目标维度（与 {@link #stagedEntityPatch()} 同一份）。 */
        public String stagedEntityPatchDimension() {
            final EntityPatch entry = last(this.stagedEntityPatch);
            return entry == null || entry.dimension() == null ? "" : entry.dimension();
        }

        /** 暂存补丁的目标 UUID（与 {@link #stagedEntityPatch()} 同一份）。 */
        public String stagedEntityPatchUuid() {
            final EntityPatch entry = last(this.stagedEntityPatch);
            return entry == null || entry.uuid() == null ? "" : entry.uuid();
        }

        /** 实体字段补丁的<b>完整清单</b>（每个目标一条，按首次暂存顺序）。 */
        public List<EntityPatch> stagedEntityPatchEntries() {
            return new ArrayList<>(this.stagedEntityPatch.values());
        }

        /** 实体字段暂存了几个目标。 */
        public int stagedEntityPatchCount() {
            return this.stagedEntityPatch.size();
        }

        /**
         * 暂存一批"改 10 项"（<b>累加</b>；同一个目标按字段合并，后写的非空字段盖前写的）。
         *
         * <p>{@code patch == null} / 空补丁 ⇒ <b>清空这一页</b>（与旧语义一致）。
         */
        public void setStagedEntityPatch(ShanhaiEntityEdit.Patch patch, String dimension, String uuid) {
            if (patch == null || patch.isEmpty()) {
                this.stagedEntityPatch.clear();
                return;
            }
            final String key = entityPatchKey(dimension, uuid);
            final EntityPatch old = this.stagedEntityPatch.get(key);
            final ShanhaiEntityEdit.Patch merged = old == null ? patch : mergePatch(old.patch(), patch);
            this.stagedEntityPatch.put(key, new EntityPatch(
                    dimension == null ? "" : dimension, uuid == null ? "" : uuid, merged));
        }

        /**
         * 同一个目标的两份补丁按<b>字段</b>合并：{@code newer} 里非 {@code null} 的项盖掉 {@code older}。
         *
         * <p>{@link ShanhaiEntityEdit.Patch} 的 11 个分量各自 {@code null} = "这一项不动"
         * ⇒ "累加"就是逐分量取"新的不是 null 就用新的"。{@code Item} 那两个分量
         * （{@code itemId}/{@code itemCount}）各算各的，与数据层同一口径。
         */
        static ShanhaiEntityEdit.Patch mergePatch(ShanhaiEntityEdit.Patch older,
                                                  ShanhaiEntityEdit.Patch newer) {
            if (older == null) {
                return newer;
            }
            if (newer == null) {
                return older;
            }
            return new ShanhaiEntityEdit.Patch(
                    newer.pos() != null ? newer.pos() : older.pos(),
                    newer.rotation() != null ? newer.rotation() : older.rotation(),
                    newer.motion() != null ? newer.motion() : older.motion(),
                    newer.customName() != null ? newer.customName() : older.customName(),
                    newer.customNameVisible() != null ? newer.customNameVisible() : older.customNameVisible(),
                    newer.noAi() != null ? newer.noAi() : older.noAi(),
                    newer.invulnerable() != null ? newer.invulnerable() : older.invulnerable(),
                    newer.persistenceRequired() != null ? newer.persistenceRequired() : older.persistenceRequired(),
                    newer.health() != null ? newer.health() : older.health(),
                    newer.itemId() != null ? newer.itemId() : older.itemId(),
                    newer.itemCount() != null ? newer.itemCount() : older.itemCount());
        }

        // ── 天然唯一键（累积容器的键；也是 Phase 2 展示"哪几条"的唯一口径）

        /** 实体删除请求的键：{@code 维度|区X,区Z|uuid:…} 或 {@code 维度|区X,区Z|type:…}。 */
        static String entityKey(ShanhaiEntityEdit.Request request) {
            if (request == null) {
                return "";
            }
            final String by = request.hasUuid()
                    ? "uuid:" + request.normalizedUuid()
                    : "type:" + ShanhaiEntityEdit.normalizeType(request.typeId());
            return request.dimension() + "|" + request.regionX() + "," + request.regionZ() + "|" + by;
        }

        /** 成就请求的键 = 归一化后的成就 id（同一个成就给/撤只留最后一次）。 */
        static String advancementKey(ShanhaiAdvancementEdit.Request request) {
            return request == null ? "" : request.id();
        }

        /** 统计请求的键 = {@code 分类/键}。 */
        static String statsKey(ShanhaiStatsEdit.Request request) {
            return request == null ? "" : request.cat() + "/" + request.statKey();
        }

        /** 体检请求的键 = 归一后的相对路径（同一条路径重复暂存 = 覆盖）。 */
        static String auditKey(com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request request) {
            final String rel = request == null ? null : request.normalizedRel();
            return rel == null ? "" : rel;
        }

        /** 实体字段补丁的键 = {@code 维度|UUID}（UUID 统一小写）。 */
        static String entityPatchKey(String dimension, String uuid) {
            return (dimension == null ? "" : dimension.trim()) + "|"
                    + (uuid == null ? "" : uuid.trim().toLowerCase(java.util.Locale.ROOT));
        }

        public void setAdvancementLines(java.util.List<String> lines) {
            fill(this.advancementLines, lines);
        }

        public String advancementLine(int index) {
            return at(this.advancementLines, index);
        }

        public void setStatsLines(java.util.List<String> lines) {
            fill(this.statsLines, lines);
        }

        public String statsLine(int index) {
            return at(this.statsLines, index);
        }

        private static void fill(String[] target, java.util.List<String> lines) {
            for (int i = 0; i < target.length; i++) {
                target[i] = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
            }
        }

        // ──────────────────────────────────────────────────────────── ⑦ 页（存档体检）

        /** 容器里<b>最后一条</b>体检动作（旧的单槽 API；完整清单见 {@link #stagedAuditRequests()}）。 */
        public com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request stagedAudit() {
            return last(this.stagedAudit);
        }

        /** 体检动作的完整清单。 */
        public List<com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request> stagedAuditRequests() {
            return new ArrayList<>(this.stagedAudit.values());
        }

        /** 体检暂存了几条。 */
        public int stagedAuditCount() {
            return this.stagedAudit.size();
        }

        /** 暂存一条体检动作（<b>累加</b>；同一路径只留最后一次）。{@code null}/空 ⇒ 清空这一页。 */
        public void setStagedAudit(com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request request) {
            if (request == null || request.isEmpty()) {
                this.stagedAudit.clear();
                return;
            }
            this.stagedAudit.put(auditKey(request), request);
        }

        /**
         * 把<b>全部</b>暂存清干净（面板真关闭时走这一条；也用于"玩家自己放弃"）。
         *
         * <p>⚠️ 只清暂存，<b>不动</b>读盘结果/列表/页码这些"看现状"的状态（它们重新读一次就有）。
         */
        public void clearStaged() {
            this.staged = null;
            this.stagedEntity.clear();
            this.stagedAdvancement.clear();
            this.stagedStats.clear();
            this.stagedEntityPatch.clear();
            this.stagedAudit.clear();
        }

        private static String at(String[] source, int index) {
            if (index < 0 || index >= source.length) {
                return "";
            }
            return source[index] == null ? "" : source[index];
        }

        @Override
        public ModularUI createUI(Player player) {
            return new ModularUI(ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H, this, player)
                    .widget(ShanhaiSaveEditPanel.create(this, player));
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
            // 没有需要回写的脏状态：权威状态是盘上的 playerdata/<uuid>.dat
        }

        @Override
        public String toString() {
            return "ShanhaiSaveEditHolder[remote=" + this.remote
                    + ", status=" + this.status + ", read=" + this.readOnce + "]";
        }
    }
}
