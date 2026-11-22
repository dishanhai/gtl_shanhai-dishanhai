package com.shanhai.client.saveedit;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.saveedit.ShanhaiPlayerDataEdit;
import com.shanhai.common.saveedit.ShanhaiPlayerDataEditor;
import com.shanhai.common.saveedit.ShanhaiSaveEditBridge;
import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditReopenGate;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「确认修改并重载」的<b>客户端侧驱动</b>：保存之后 → 停服 → <b>窗口内改盘</b> → 直接重开同一个存档。
 *
 * <h2>一、屏幕上会出现什么（用户口径：不许出现多余页面）</h2>
 * <pre>
 *   我们的加载屏（GenericDirtMessageScreen / "正在读取世界数据"）
 *     └─ clearLevel 等死期间：同一张屏（等待循环里 ClientPacketListener#onDisconnect 可能把它换成
 *        DisconnectedScreen ⇒ 我们 clearLevel 返回后<b>再 setScreen 一次</b>压回来）
 *     └─ 改盘期间：同一张屏（改盘在 IO 池上跑，客户端继续 tick，屏会正常画）
 *     └─ 重开期间：原版 LevelLoadingScreen（"正在生成世界"，由 doWorldLoad 内部 2042-2043 自己 set）
 *   全程<b>没有</b> TitleScreen / SelectWorldScreen。
 * </pre>
 * ⇒ 唯一"可能一闪"的是 {@code doWorldLoad} 开头 {@code clearLevel()} 无参版顶 1 tick 的
 * {@code ProgressScreen} —— 那也是加载类页面，而且此时 {@code this.level == null}，
 * 它连等待循环都不会进（{@code Minecraft.java:2088-2090}、{@code 2111}）。
 *
 * <h2>二、🔴 改盘为什么必须在这个函数体里"夹"着</h2>
 * 唯一安全窗口是 {@code clearLevel} 返回之后、{@code validateAndCreateAccess} 之前：
 * <ul>
 *   <li>{@code clearLevel} 内部 {@code while(!integratedserver.isShutdown()) runTick(false)}
 *       （{@code Minecraft.java:2113-2121}）跑到服务器线程死亡为止；</li>
 *   <li>服务器线程临死前最后一段 {@code stopServer} 的<b>最后一步</b>才是
 *       {@code storageSource.close()}（释放 {@code session.lock}、关掉所有 {@code RegionFile}）；</li>
 *   <li>早于它 ⇒ 后面的保存把改动打回；晚于它 ⇒ 新服已经在读盘。</li>
 * </ul>
 * 所以本类只做一件事：<b>把 {@link ShanhaiSaveEditFlow#markStopped()} 夹在
 * {@code clearLevel} 返回与 {@code tryApply} 之间</b>，别处一概不许落盘。
 *
 * <h2>三、重开用哪条路（2026-11-20 实机崩溃后的最终选择 = 任务书里的 <b>b</b>）</h2>
 *
 * <p>🔴 <b>结论先说</b>：重开走「<b>自己 {@code new PackRepository}，只喂原版 + 存档自带的数据包，
 * 跳过 {@code ModLoader.postEvent(AddPackFindersEvent)}</b>」这一条（= b）；
 * 而且<b>只在这个仓库能凑齐 {@code level.dat} 里点名的全部数据包时才真的重开</b>，
 * 凑不齐就<b>根本不尝试</b>，直接把"存档已改好、请重新进入这个存档"交给玩家。
 *
 * <h3>为什么不选 a（复用客户端仓库 {@code Minecraft#getResourcePackRepository()}）</h3>
 * <ul>
 *   <li>那个仓库<b>没有</b>挂存档自己的 {@code <world>/datapacks}（它是客户端资源包那套的源）；</li>
 *   <li>重开时 {@code WorldLoader.PackConfig#createResourceManager()} 会对传进去的仓库调
 *       {@code MinecraftServer.configurePackRepository(...)} → {@code PackRepository#setSelected(...)}
 *       ⇒ 把玩家<b>当前的资源包选择</b>就地改成这个世界的数据包清单。那是客户端视觉状态，
 *       我们没有任何离线手段能验证"改完画面还对" ⇒ 不能拿玩家的画面赌；</li>
 *   <li>它<b>省不掉</b> {@link WorldStem} 的构造：{@code WorldOpenFlows#loadWorldStem(access,false)}
 *       内部第 157 行自己还会调一次 {@code ServerPacksSource.createPackRepository}
 *       ⇒ 事件照发、崩溃照旧。</li>
 * </ul>
 *
 * <h3>为什么不调 {@code loadWorldStem(access, false)}（这一条是本次崩溃的真正根因）</h3>
 * <p>同一个理由：它内部第 157 行就是 {@code ServerPacksSource.createPackRepository(access)}。
 * 所以现在<b>整条重开链路上一个 {@code createPackRepository} 都没有</b>：
 * <pre>
 *   LevelStorageSource#validateAndCreateAccess(levelId)      ← 拿 session.lock
 *   LevelStorageAccess#readAdditionalLevelSaveData()         ← Forge 的世界额外数据（与 doLoadLevel 一致）
 *   new PackRepository(new ServerPacksSource(),
 *                      new FolderRepositorySource(access.getLevelPath(DATAPACK_DIR),
 *                                                SERVER_DATA, PackSource.WORLD))
 *        ↑ 这两行 = 原版 {@code createPackRepository} 的<b>全部函数体</b>，只去掉最后那两行 postEvent
 *   packs.reload();  逐个核对"要的包"（level.dat 的 Enabled ∪ Forge 的模组数据包）都在仓库里
 *                    —— 缺一个就不重开（见 {@code missingPacks}）
 *   WorldLoader.load(new WorldLoader.PackConfig(packs, cfg, false, false), &lt;原版 loadWorldStem 的 lambda&gt;,
 *                    WorldStem::new, Util.backgroundExecutor(), mc)
 *   Minecraft#managedBlock(future::isDone)                   ← = 原版 {@code driveUntil} 那一句
 *   Minecraft#doWorldLoad(levelId, access, packs, stem, false)
 * </pre>
 * <p>证据（{@code javap -p -c} 打在 mapped forge jar 1.20.1-47.4.16 上，2026-11-20 本机）：
 * {@code ServerPacksSource.createPackRepository(Path)} 的字节码是
 * {@code new PackRepository(new ServerPacksSource(), new FolderRepositorySource(dir, SERVER_DATA, PackSource.WORLD))}
 * 后面紧跟 {@code ModLoader.get().postEvent(new AddPackFindersEvent(SERVER_DATA, repo::addPackFinder))}；
 * {@code createPackRepository(access)} 只是把 {@code access.getLevelPath(DATAPACK_DIR)} 转给上一个重载。
 *
 * <p>⚠️ <b>代价（如实计入文案）</b>：这个自建仓库里<b>没有模组动态数据包</b>
 * （{@code mod:shanhai}、{@code gtceu:dynamic_data} 这些只在 {@code AddPackFindersEvent} 里被加进来）。
 * 而原版 {@code MinecraftServer#configurePackRepository} 对"配置里有、仓库里没有"的包<b>只打一行
 * {@code LOGGER.warn("Missing data pack {}")} 然后跳过</b>（字节码已核），<b>不会</b>替你拦住
 * ⇒ 硬开就等于"世界在没有 GT 动态数据包的状态下启动"，那是丢东西级别的风险。
 * <b>所以缺包时我们不重开</b>：原版那条路每次正常进世界都会发一次事件，让玩家重进存档是最安全的。
 *
 * <h3>为什么不直接选 c（根本不重开）</h3>
 * <p>纯原版存档（数据包清单只有 {@code vanilla}）时上面这条链路是完整可用的，白白放弃"原地重载"没道理。
 * 缺包的情形已被完整性检查挡在重开之前 ⇒ 合起来就是 c 的效果，但不牺牲 b 能覆盖的那一半。
 * <p>⚠️ 仍<b>不调</b> {@code PrimaryLevelData#withConfirmedWarning(true)}：它会把
 * {@code confirmedExperimentalWarning} 置真、随即被 {@code doWorldLoad} 的 {@code saveDataTag}
 * 写进 level.dat —— 那是"不碰 level.dat"的红线。
 * <p>⚠️ 也仍<b>不重新加载</b>"存档自带的服务器资源包"（{@code <world>/server-resources}）：
 * 这条链路和原来的 {@code loadWorldStem} 一样跳过了 {@code DownloadedPackSource#loadBundledResourcePack}
 * ⇒ {@link ShanhaiSaveEditBridge} 在启动前见到那个目录就直接拒绝（{@code confirm_rejected reason=server_resources}）。
 *
 * <h2>四、🔴 重开之前的四道闸（①② 于 2026-11-14 加，判据在 {@link ShanhaiSaveEditReopenGate}）</h2>
 * <pre>
 *   ② applyResult 说"没有实际变化"（no_effective_change）且一个字节都没写 ⇒ 不重开（结局 C 的专门版本）。
 *      （现场那行 region_page_failed:no_effective_change —— 这个位置本来就是 air —— 之后照样停了世界、照样重开。）
 *   ⓪ apply_ok=false（其余写入失败）⇒ 一个字节都没改 ⇒ 不重开、也绝不说"已改好"（结局 C）。
 *   ① levelId 算不出来（或 level.dat 不在）⇒ level_id_unresolved candidates=[…]，不重开（结局 B）。
 *   ③ 数据包凑不齐（模组动态包不在自建仓库里）⇒ 不重开（结局 B）。见上。
 * </pre>
 * 顺序是有意的：② 是 C 的<b>专门文案</b>（"没删掉 / 没改动"），所以排在通用闸 ⓪ 之前；
 * ⓪ 又必须排在 ① 之前 —— 写入都没成功的时候不许给 B 那一档"存档已改好"的文案。
 * 四道闸都在 {@code markReopening()} <b>之前</b>，所以停手时状态机会 FAILED 并放锁（不会永远 BUSY）。
 *
 * <h2>五、🔴 提示文案必须说真话（2026-11-20 那条"存档本身没有被改动"就是另一半 bug）</h2>
 * <pre>
 *   A  写入成功 ∧ 重开成功         ⇒「已写入 … 并已重新载入世界」（聊天栏 + 备份在哪）
 *   B  写入成功 ∧ 重开没成功/没尝试  ⇒「存档已改好」＋ 一行原因 ＋「请重新进入这个存档」
 *        B2 世界已经停了（getSingleplayerServer()==null 且 level==null）⇒ 重进存档即可，不用退出游戏
 *        B3 世界还在跑（停不下来）                                  ⇒ 才写"完全退出游戏再进一次"
 *   C  写入没成功                  ⇒「没有改动存档」＋ 一行原因 ＋ 备份在哪
 * </pre>
 * 🔴 判据只有一条：{@code applyResult != null && applyResult.ok()}（就是日志里 {@code apply_ok} 那个字段）。
 * <b>绝对不许</b>在 {@code apply_ok=true} 之后再出现"没有改动 / 没做完"这类字样。
 * B2 与 B3 的分界用"世界是否已停"（不用新造状态）：见 {@link #worldStillRunning(Minecraft)}。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiSaveEditReload {

    private static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 最近一次的结果（只给人/日志看；进世界后还会往聊天栏发一行）。 */
    public static volatile String LAST_OUTCOME = "";

    private ShanhaiSaveEditReload() {}

    // ================================================================== 入口

    /** 由 {@link ShanhaiSaveEditBridge} 在服务端线程调过来；这里立刻切到客户端主线程。 */
    public static void start(com.shanhai.common.saveedit.ShanhaiSaveEditRunContext ctx,
                             ShanhaiPlayerDataEdit edit, ShanhaiSaveEditFlow flow) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            flow.fail("no_minecraft");
            ShanhaiMod.LOGGER.error("{} start_failed reason=no_minecraft", PREFIX);
            return;
        }
        mc.execute(() -> {
            try {
                begin(mc, ctx, edit, flow);
            } catch (Throwable t) {
                fail(mc, flow, null, "begin_threw", t);
            }
        });
    }

    // ================================================================== ② 停服（窗口在这里打开）

    private static void begin(Minecraft mc, com.shanhai.common.saveedit.ShanhaiSaveEditRunContext ctx,
                              ShanhaiPlayerDataEdit edit, ShanhaiSaveEditFlow flow) {
        final Screen loading = loadingScreen();
        boolean stopped = false;
        mc.setScreen(loading);
        final ClientLevel level = mc.level;
        if (level == null) {
            // 🔴 没有客户端世界 ⇒ 停不了服 ⇒ 改盘窗口永远打不开 ⇒ 这一次运行已经死了。
            //    必须在这里放锁（fail 内部就是 fail+release），否则下一次确认永远 BUSY。
            flow.fail("no_client_level");
            logFlowNotify();
            ShanhaiMod.LOGGER.error("{} stop_skipped reason=no_client_level", PREFIX);
        } else {
            try {
                flow.markStopping();
                // 🔴 只发"我要下线"，让服务端自己走 halt(false)；
                //    ⚠️ 绝不能在这里直接 server.halt()：那条路会走 Connection#handleDisconnection
                //       ⇒ ClientPacketListener#onDisconnect ⇒ DisconnectedScreen（多余页面）。
                level.disconnect();
                // 阻塞到 stopServer 做完（session.lock 已释放、RegionFile 全关）
                mc.clearLevel(loading);
                // 坑 1：等待循环里 onDisconnect 可能把屏换成了 DisconnectedScreen ⇒ 再压一次
                mc.setScreen(loading);
                stopped = flow.markStopped();
                ShanhaiMod.LOGGER.info("{} stop_done window_open={} phase={}",
                        PREFIX, stopped, flow.phase());
            } catch (Throwable t) {
                // 🔴 停服失败 ⇒ 窗口打不开、一个字节都不会写 ⇒ 立刻放锁（不然后面全 BUSY）
                flow.fail("stop_failed:" + t);
                logFlowNotify();
                ShanhaiMod.LOGGER.error("{} stop_failed err={} phase={}", PREFIX, t.toString(), flow.phase());
            }
        }
        final boolean windowOpen = stopped;
        final AtomicReference<ShanhaiPlayerDataEditor.Result> result = new AtomicReference<>();
        // ③ 改盘（IO 池：加载屏继续画）+ ④ 重开（回到客户端线程）
        final CompletableFuture<Void> io = ShanhaiSaveEditBridge.runOnIoPool(
                () -> applyOnIo(ctx, edit, flow, windowOpen, result));
        io.whenComplete((ignored, err) -> mc.execute(() -> {
            try {
                reopen(mc, ctx, flow, result.get(), err);
            } catch (Throwable t) {
                // reopen() 自己已经兜了一层；能漏到这里的说明它连兜底都没走完 ⇒ 用这次真实的
                // applyResult 判"存档到底改了没有"，别默认当成"没改"（那样又会说假话）。
                fail(mc, flow, result.get(), "reopen_threw", t);
            }
        }));
    }

    // ================================================================== ③ 窗口内改盘

    private static void applyOnIo(com.shanhai.common.saveedit.ShanhaiSaveEditRunContext ctx,
                                  ShanhaiPlayerDataEdit edit, ShanhaiSaveEditFlow flow,
                                  boolean windowOpen,
                                  AtomicReference<ShanhaiPlayerDataEditor.Result> out) {
        // 🔴 双保险：调用方判断 + 状态机。任何一条说"窗口没开"就一个字节都不写。
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow.tryApply("reload");
        if (!attempt.permitted()) {
            ShanhaiMod.LOGGER.warn("{} apply_refused window_open_flag={} phase={} ⇒ 未改盘",
                    PREFIX, windowOpen, attempt.phaseAtCall());
            LAST_OUTCOME = "窗口没开（phase=" + attempt.phaseAtCall() + "），一个字节都没改";
            return;
        }
        final String stamp = ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
        final ShanhaiPlayerDataEditor.Result r =
                ShanhaiPlayerDataEditor.apply(ctx.playerDataFile, edit, ctx.backupRoot, stamp);
        if (r.ok()) {
            flow.markApplied();
        }
        out.set(r);
        LAST_OUTCOME = (r.ok() ? "已写入 " + r.changed() + " · 备份 "
                : "改盘失败（" + r.error() + "），存档未被改 · 备份 ")
                + (r.write() == null ? "-" : String.valueOf(r.write().backupPath()));
        if (r.ok()) {
            ShanhaiMod.LOGGER.info("{} apply_ok {}", PREFIX, r.line());
        } else {
            ShanhaiMod.LOGGER.error("{} apply_failed {}", PREFIX, r.line());
        }
        ShanhaiMod.LOGGER.info("{} apply_phase {}", PREFIX, flow.traceLine());
    }

    // ================================================================== ④ 重开（不经过标题）

    private static void reopen(Minecraft mc, com.shanhai.common.saveedit.ShanhaiSaveEditRunContext ctx,
                               ShanhaiSaveEditFlow flow, ShanhaiPlayerDataEditor.Result applyResult,
                               Throwable ioError) {
        // 🔴 结局三档的唯一判据（全部取自日志里已有的那两行，没有新造状态）：
        //    apply_ok    = applyResult.ok()  ⇒ 决定"存档到底改了没有"（A/B 还是 C）
        //    apply_phase = flow.phase()      ⇒ 决定"世界停了没有"（B2 / B3 的分界，见 worldStillRunning）
        final boolean applyOk = applyResult != null && applyResult.ok();
        // 🔴 闸 ②（2026-11-14）：这次一个字节都没改成（no_effective_change）⇒ 不许重开。
        //    现场那行 region_page_failed:no_effective_change（这个位置本来就是 air）之后照样停了世界、
        //    照样重开，最后掉到原版错误屏；停手更诚实（存档原样，玩家回标题自己进）。
        //    ⚠️ 它是结局 C 的一个专门版本（"没删掉/没改动"），所以必须排在闸 ⓪ 前面。
        if (ShanhaiSaveEditReopenGate.skipReopen(
                applyResult != null && applyResult.ok(),
                applyResult == null ? null : applyResult.error(),
                applyResult != null && applyResult.write() != null,
                ShanhaiSaveEditReopenGate::isNoEffectiveChange)) {
            skipReopenNoChange(mc, flow, applyResult);
            return;
        }
        // ── 闸 ⓪（结局 C，2026-11-20 加）：写入没成功 ⇒ 一个字节都没改 ⇒ 不重开，也绝不说"已改好"。
        if (!applyOk) {
            fail(mc, flow, applyResult, "apply_not_ok", ioError);
            return;
        }
        // ── 重开之前的闸（都在 markReopening 之前 ⇒ 一旦停手就 FAILED 放锁，不会永远 BUSY）
        // 🔴 闸 ①（本 bug 的根治点）：算不出 levelId ⇒ 一个字节都不许往重开走。
        //    现场（2026-11-14 09:57）：levelId 被算成 "."，于是
        //    validateAndCreateAccess(".")/loadLevel(".") 指向 saves\. 这个【不存在的世界目录】
        //    ⇒ getDataConfiguration() 必然 null ⇒ 掉回原版「数据包有错误」屏。
        //    ⚠️ 走到这里 applyOk 一定为真（闸 ⓪ 已拦掉另一支）⇒ 文案必须走结局 B，不许说"没有改动"。
        if (ctx == null || !ctx.hasLevelId()) {
            fail(mc, flow, applyResult, "level_id_unresolved candidates="
                    + (ctx == null ? "[]" : ctx.levelIdCandidatesLine()), null);
            return;
        }
        if (ioError != null) {
            ShanhaiMod.LOGGER.error("{} io_stage_threw err={}", PREFIX, ioError.toString());
        }
        LevelStorageSource.LevelStorageAccess access = null;
        WorldStem stem = null;
        PackRepository packs = null;
        final long t0 = System.nanoTime();
        try {
            access = mc.getLevelSource().validateAndCreateAccess(ctx.levelId);
            // 与 doLoadLevel 同一条顺序（WorldOpenFlows.java:180）：先喂 Forge 的世界额外数据
            // （createPackRepository 只读 getLevelPath，与这一步互不依赖，谁先谁后不承载语义）
            access.readAdditionalLevelSaveData();
            // 🔴 诊断行：以后 reopen 再失败，这一行必须能独立判定三件事：
            //    ①【目录对不对】saveDir 是不是那个世界目录、level.dat 在不在（<size|absent>）；
            //    ② 走哪条路（dataCfg=pack_reload / vanilla(cfg=null)）；
            //    ③ 数据包清单（packs=）。
            //    原版 null 的来历（javap 事实）：
            //      LevelStorageAccess#getDataConfiguration → LevelStorageSource#getDataConfiguration(Path,DataFixer)
            //      → readLevelData(dir, …)：level.dat 读不出来（读时抛 ⇒ 原版自己会打 "Exception reading {}"）
            //        或顶层不是 CompoundTag ⇒ 退到 level.dat_old；两者都不行才返回 null。
            //    ⚠️ 而 level.dat 根本不在 ⇒ 一定 null —— 那就是 2026-11-14 那次（levelId="."）的形态。
            final WorldDataConfiguration cfg = access.getDataConfiguration();
            final Path levelDat = ctx.worldDir == null ? null
                    : ctx.worldDir.resolve(ShanhaiSaveEditReopenGate.LEVEL_DAT);
            final Path levelDatOld = levelDat == null ? null : levelDat.resolveSibling("level.dat_old");
            ShanhaiMod.LOGGER.info("{} reopen_probe levelId={} saveDir={} level_dat={} level_dat_old={} "
                            + "root={} dataCfg={} packs={}",
                    PREFIX, ctx.levelId, ctx.worldDir, fileSize(levelDat), fileSize(levelDatOld),
                    access.getLevelPath(LevelResource.ROOT), probeConfig(cfg), probePacks(cfg));
            final ShanhaiSaveEditReopenRoute.Route route = ShanhaiSaveEditReopenRoute.of(cfg);
            if (route != ShanhaiSaveEditReopenRoute.Route.PACK_RELOAD) {
                // 🔴 2026-11-20：这里<b>不再</b>交回原版 {@code WorldOpenFlows#loadLevel}。
                //    理由（字节码事实）：loadLevel → createPackRepository → ModLoader.postEvent(AddPackFindersEvent)
                //    ⇒ 就是本次崩溃那条栈。level.dat 读不出来时宁可停手、如实说"请重新进入这个存档"。
                closeQuietly(access);
                access = null;
                ShanhaiMod.LOGGER.warn("{} reopen_skipped reason=data_cfg_missing route={} "
                                + "would_post_pack_event=true levelId={}",
                        PREFIX, route.logName(), ctx.levelId);
                fail(mc, flow, applyResult, "data_cfg_missing:" + route.logName(), null);
                return;
            }
            // ── 闸 ③（2026-11-20 加）：自建仓库里凑不齐这个世界点名的数据包 ⇒ 不重开。
            //    = 原版 createPackRepository 的<b>全部函数体</b>，只去掉那两行 postEvent
            //    （见 {@link #buildPackRepositoryWithoutEvent}）。
            ShanhaiMod.LOGGER.info("{} reopen_route={} levelId={} pack_event=skip", PREFIX,
                    ShanhaiSaveEditReopenRoute.Route.PACK_RELOAD.logName(), ctx.levelId);
            packs = buildPackRepositoryWithoutEvent(access);
            packs.reload();
            final List<String> missing = missingPacks(packs, cfg);
            ShanhaiMod.LOGGER.info("{} reopen_packs src=vanilla_plus_save available={} missing={}",
                    PREFIX, packs.getAvailableIds(), missing);
            if (!missing.isEmpty()) {
                // 🔴 缺包<b>不许</b>硬开：原版 MinecraftServer#configurePackRepository 对缺包
                //    只打一行 "Missing data pack {}" 就跳过（字节码已核）⇒ 世界会在没有 GT / 模组
                //    动态数据包的状态下启动，那是丢东西级别的风险。
                closeQuietly(access);
                access = null;
                // ⚠️ PackRepository <b>不是</b> AutoCloseable（字节码已核）：reload() 只读了 pack.mcmeta，
                //    没有留下任何需要关的句柄 ⇒ 这里只需要丢掉引用。
                packs = null;
                fail(mc, flow, applyResult, "pack_set_incomplete missing=" + missing, null);
                return;
            }
            // 所有闸都过了 ⇒ 真的重开（从这里起窗口永久关闭，不再回 APPLIED）
            mc.setScreen(loadingScreen());
            if (!flow.markReopening()) {
                ShanhaiMod.LOGGER.warn("{} reopen_phase_unexpected phase={}", PREFIX, flow.phase());
            }
            // 🔴 用<b>已有仓库</b>建 WorldStem：不调 loadWorldStem(access,false)（它内部还会再建一次仓库）
            stem = loadWorldStemWithoutPackEvent(access, cfg, packs, mc);
            ShanhaiMod.LOGGER.info("{} reopen_packs selected={}", PREFIX, packs.getSelectedIds());
            // 🔴 故意【不】调 PrimaryLevelData#withConfirmedWarning(true)：
            //    它会把 confirmedExperimentalWarning 置真、由 doWorldLoad 的 saveDataTag 写进 level.dat
            //    ⇒ 那就是"本期不碰 level.dat"的红线。而且这条路线根本不走 doLoadLevel 里那道
            //    实验性确认屏（WorldOpenFlows.java:201-206），所以这一步也不需要。
            ShanhaiMod.LOGGER.info("{} reopen_stem world={} level_stem_lifecycle={}",
                    PREFIX, stem.worldData().getLevelName(),
                    stem.worldData().worldGenSettingsLifecycle());
            // 🔴 这一句内部自己 setScreen(new LevelLoadingScreen(...))（Minecraft.java:2042-2043）
            //    ⇒ 玩家看到的是"正在生成世界"的进度条，不是标题
            final long tLoad = System.nanoTime();
            mc.doWorldLoad(ctx.levelId, access, packs, stem, false);
            access = null;      // 所有权已交给 IntegratedServer（它停服时自己 close）
            stem = null;        // 同上：WorldStem 归服务器所有
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            final long loadMs = (System.nanoTime() - tLoad) / 1_000_000L;
            if (!flow.markDone()) {
                // 状态机没走到 REOPENING（例如停服那段抛过）⇒ 直接放锁，免得下一次永远 BUSY
                ShanhaiMod.LOGGER.warn("{} reopen_done_but_phase={} ⇒ force release", PREFIX, flow.phase());
                flow.release();
            }
            ShanhaiMod.LOGGER.info("{} reopen_done total_ms={} do_world_load_ms={} apply_ok={} "
                            + "title_screen_shown=false select_world_screen_shown=false "
                            + "level_loading_screen_expected=true flow={}",
                    PREFIX, ms, loadMs, applyResult != null && applyResult.ok(), flow.traceLine());
            notifyOutcome(mc, applyResult);
        } catch (Throwable t) {
            if (stem != null) {
                try {
                    stem.close();
                } catch (Throwable ignored) {
                    // 关不掉也不改结论
                }
            }
            closeQuietly(access);
            // PackRepository 不是 AutoCloseable（见闸 ③ 那行注释）⇒ 只丢引用
            packs = null;
            // ⚠️ 这里 applyOk 一定是 true（闸 ⓪ 已拦掉另一支）⇒ fail() 会走结局 B 的文案：
            //    "存档已改好 / 重开没成功 / 请重新进入这个存档"，绝不再说"没有改动"。
            fail(mc, flow, applyResult, "reopen_failed", t);
        }
    }

    /**
     * 闸 ② 的落点：这次<b>什么都没改</b> ⇒ 不重开，给一张说清"世界已经停了、怎么回去"的屏。
     *
     * <p>⚠️ 这一路必然发生在 {@code clearLevel} 之后（窗口关掉之后才知道 Result），所以不能只是
     * "什么都不做" —— 玩家会卡在我们的加载屏上。给一张带按钮的屏，并<b>立刻放锁</b>
     * （否则下一次确认永远 BUSY）。
     */
    private static void skipReopenNoChange(Minecraft mc, ShanhaiSaveEditFlow flow,
                                           ShanhaiPlayerDataEditor.Result applyResult) {
        final String detail = applyResult == null || applyResult.error() == null
                ? "没有实际变化" : applyResult.error();
        flow.fail("no_effective_change:" + detail);
        LAST_OUTCOME = "没有实际变化（" + detail + "），没有重开世界";
        ShanhaiMod.LOGGER.warn("{} reopen_skipped reason=no_effective_change detail=\"{}\" "
                        + "apply_ok=false outcome=C_no_write apply_write=null screen=saveedit_no_change flow={}",
                PREFIX, detail, flow.traceLine());
        try {
            // 🆕 2026-11-20：文案改成诚实版 —— 这次<b>根本没写入</b>，
            //   所以不许再出现"世界已经停了／回标题重进"那套（那是"已经删了、要重开"的旧流程）。
            //   实机判据：退出世界写盘时那条实体会被重写（UUID 变了）⇒ 暂存时的 UUID 已经不存在。
            mc.setScreen(new AlertScreen(
                    () -> mc.setScreen(new TitleScreen()),
                    Component.literal("没删掉（存档一个字节都没改）"),
                    Component.literal("没删掉：那条实体在你退出世界时被重写了（UUID 变了或已被移除）。\n\n"
                            + "请重新打开这个世界、重新列一次再删。存档一个字节都没改。")));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} skip_screen_failed err={}", PREFIX, t.toString());
        }
    }

    // ================================================================== reopen 的诊断/兜底小件

    /**
     * {@code reopen_probe} 里 {@code dataCfg=} 那一段：一眼能看出"目录对不对 / 该走哪条路"。
     * <pre>
     *   dataCfg=vanilla(cfg=null)                          ← level.dat 没读到 ⇒ 交回原版（路线 A）
     *   dataCfg=pack_reload(features=[…], disabled=[…])    ← 正常路线（路线 B）
     * </pre>
     *
     * <p>⚠️ 诊断<b>不许</b>影响主流程：这里自己 catch 一切，最坏情况打成 {@code {unreadable:…}}。
     */
    private static String probeConfig(WorldDataConfiguration cfg) {
        if (cfg == null) {
            return "vanilla(cfg=null)";
        }
        try {
            return "pack_reload(features=" + FeatureFlags.REGISTRY.toNames(cfg.enabledFeatures())
                    + ", disabled=" + cfg.dataPacks().getDisabled() + ")";
        } catch (Throwable t) {
            return "pack_reload(unreadable:" + t.getClass().getSimpleName() + ")";
        }
    }

    /** {@code reopen_probe} 里 {@code packs=} 那一段：{@code WorldDataConfiguration} 里的启用清单。 */
    private static String probePacks(WorldDataConfiguration cfg) {
        if (cfg == null) {
            return "-";
        }
        try {
            return String.valueOf(cfg.dataPacks().getEnabled());
        } catch (Throwable t) {
            return "unreadable:" + t.getClass().getSimpleName();
        }
    }

    /**
     * {@code reopen_probe} 里 {@code level_dat=} 那一段：只读大小（🔴 一个字节都不写）。
     *
     * @return {@code 1234B} / {@code absent}（不存在或不是普通文件；原来那种 {@code -(NoSuchFileException)}
     *         在日志里不好认，也不好 grep）
     */
    private static String fileSize(Path p) {
        if (p == null) {
            return "absent";
        }
        try {
            return Files.isRegularFile(p) ? Files.size(p) + "B" : "absent";
        } catch (Throwable t) {
            return "absent(" + t.getClass().getSimpleName() + ")";
        }
    }

    // ================================================================== 重开用的两个"去掉事件"的小件（2026-11-20）

    /**
     * 🔴 = 原版 {@code ServerPacksSource#createPackRepository(Path)} 的<b>全部函数体</b>，
     * 只去掉最后那两行
     * {@code ModLoader.get().postEvent(new AddPackFindersEvent(SERVER_DATA, repo::addPackFinder))}。
     *
     * <p>字节码出处（{@code javap -p -c}，mapped forge 1.20.1-47.4.16，2026-11-20 本机）：
     * <pre>
     *   new PackRepository(new ServerPacksSource(),
     *                      new FolderRepositorySource(dir, PackType.SERVER_DATA, PackSource.WORLD));
     *   ModLoader.get().postEvent(new AddPackFindersEvent(SERVER_DATA, repo::addPackFinder));  ← 只删这两行
     * </pre>
     * 为什么非要删：GTCEu 的 {@code registerPackFinders} 在事件里会现场重跑整套配方生成
     * （{@code GTRecipes.recipeRemoval()} + {@code recipeAddition()}），第二次跑时
     * {@code ToolRecipeHandler.addElectricToolRecipe} 里 {@code powerUnit} 为 {@code null}
     * ⇒ 2026-11-20 21:29 那次 {@code ModLoadingException} 崩掉了重开。
     *
     * <p>⚠️ 代价：模组动态数据包（{@code mod:*}、{@code gtceu:dynamic_data}）不在这个仓库里 ——
     * 所以调用方必须先用 {@link #missingPacks} 核对，缺一个就不许往下走。
     */
    private static PackRepository buildPackRepositoryWithoutEvent(
            LevelStorageSource.LevelStorageAccess access) {
        return new PackRepository(
                new ServerPacksSource(),
                new FolderRepositorySource(access.getLevelPath(LevelResource.DATAPACK_DIR),
                        PackType.SERVER_DATA, PackSource.WORLD));
    }

    /**
     * 这个世界"要的"数据包里，自建仓库<b>给不出来</b>的那些（空 = 可以安全重开）。
     *
     * <p>要的集合 = {@code level.dat} 的 {@code Enabled} <b>∪</b> Forge 的模组数据包。
     * 后者必须算进来：{@code MinecraftServer#configurePackRepository} 第一步就是
     * {@code DataPackConfig#addModPacks(ForgeHooks.getModPacks())}（字节码已核）——
     * 也就是<b>Forge 自己会把模组数据包强行加进启用清单</b>。只看 {@code getEnabled()} 会漏掉它们，
     * 漏掉之后原版只会打一行 "Missing data pack {}" 然后跳过 ⇒ 世界在没有 GT 动态数据包的状态下启动。
     * <p>读不出来时<b>故意</b>塞一个占位符 ⇒ 闸门关上（宁可不重开，也不许瞎开）。
     */
    private static List<String> missingPacks(PackRepository packs, WorldDataConfiguration cfg) {
        final List<String> missing = new ArrayList<>();
        if (packs == null || cfg == null) {
            missing.add("<no_repo_or_no_config>");
            return missing;
        }
        final List<String> wanted = new ArrayList<>();
        try {
            wanted.addAll(cfg.dataPacks().getEnabled());
        } catch (Throwable t) {
            missing.add("<config_unreadable:" + t.getClass().getSimpleName() + ">");
        }
        try {
            for (String id : ForgeHooks.getModPacks()) {
                if (id != null && !wanted.contains(id)) {
                    wanted.add(id);
                }
            }
        } catch (Throwable t) {
            missing.add("<mod_packs_unreadable:" + t.getClass().getSimpleName() + ">");
        }
        if (!missing.isEmpty()) {
            return missing;
        }
        try {
            for (String id : wanted) {
                if (!packs.isAvailable(id)) {
                    missing.add(id);
                }
            }
        } catch (Throwable t) {
            missing.add("<repo_unreadable:" + t.getClass().getSimpleName() + ">");
        }
        return missing;
    }

    /**
     * 用<b>已有仓库</b>建 {@link WorldStem}，<b>不</b>调 {@code WorldOpenFlows#loadWorldStem(access,false)}。
     *
     * <p>🔴 为什么不调它：它内部第 157 行就是 {@code ServerPacksSource.createPackRepository(access)}
     * ⇒ 又会 post 一次 {@code AddPackFindersEvent} ⇒ 就是本次崩溃那条栈。
     * 这里抄的是它私有方法 {@code loadWorldStem(access, safeMode, packs)} 的<b>原样逻辑</b>：
     * <pre>
     *   Pair&lt;WorldData, WorldDimensions.Complete&gt; = access.getDataTag(…)
     *   PackConfig(packs, cfg, false, false) → InitConfig(…, INTEGRATED, 2)
     *     → WorldLoader.load(…, 上面那个 lambda, WorldStem::new, Util.backgroundExecutor(), mc)
     *     → mc.managedBlock(future::isDone)        ← 原版那一句 driveUntil
     * </pre>
     *
     * <p>失败<b>不许崩</b>：异常一律抛给调用方（{@code reopen()} 的 {@code catch (Throwable)}），
     * 由它落成一句"存档已改好、请重新进入这个存档"的提示屏。
     */
    private static WorldStem loadWorldStemWithoutPackEvent(
            LevelStorageSource.LevelStorageAccess access, WorldDataConfiguration cfg,
            PackRepository packs, Minecraft mc) throws Exception {
        final WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, cfg, false, false);
        final WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                packConfig, Commands.CommandSelection.INTEGRATED, 2);
        final CompletableFuture<WorldStem> future = WorldLoader.load(initConfig, context -> {
            final DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, context.datapackWorldgen());
            final Registry<LevelStem> registry =
                    context.datapackWorldgen().registryOrThrow(Registries.LEVEL_STEM);
            final Pair<WorldData, WorldDimensions.Complete> pair = access.getDataTag(
                    ops, context.dataConfiguration(), registry,
                    context.datapackWorldgen().allRegistriesLifecycle());
            if (pair == null) {
                throw new IllegalStateException("Failed to load world");
            }
            return new WorldLoader.DataLoadOutput<>(pair.getFirst(),
                    pair.getSecond().dimensionsRegistryAccess());
        }, WorldStem::new, Util.backgroundExecutor(), mc);
        mc.managedBlock(future::isDone);
        return future.get();
    }

    /** 静默关掉（{@code null} 安全）；关不掉也不改结论。 */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} close_failed err={}", PREFIX, t.toString());
        }
    }

    /**
     * 结局 A（写入成功 ∧ 重开成功）往聊天栏发的那一行。
     *
     * <p>🔴 只有这一档才可以说"已重新载入世界"：能走到这里就说明 {@code apply_ok=true}
     * 且 {@code doWorldLoad} 没抛。
     */
    private static void notifyOutcome(Minecraft mc, ShanhaiPlayerDataEditor.Result applyResult) {
        LAST_OUTCOME = "已写入 " + changedSummary(applyResult) + " 并已重新载入世界";
        notifyWhenInWorld(mc, "§8[存档直改] §f" + LAST_OUTCOME + " §8· 备份在 §7"
                + backupDir(applyResult));
    }

    /** 改了什么（最多 3 条 + 截断，够玩家认人；完整清单在日志 {@code apply_ok} 那行里）。 */
    private static String changedSummary(ShanhaiPlayerDataEditor.Result r) {
        try {
            final List<String> changed = r == null ? null : r.changed();
            if (changed == null || changed.isEmpty()) {
                return "（没有列出改动项）";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < changed.size() && i < 3; i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(changed.get(i));
            }
            if (changed.size() > 3) {
                sb.append(" 等 ").append(changed.size()).append(" 项");
            }
            final String s = sb.toString();
            return s.length() > 120 ? s.substring(0, 120) + "…" : s;
        } catch (Throwable t) {
            return "（改动项读不出来）";
        }
    }

    /** 备份在哪（{@code temp\saveedit-backup\<时间戳>}）；读不出来就给根目录。 */
    private static String backupDir(ShanhaiPlayerDataEditor.Result r) {
        try {
            if (r != null && r.write() != null && r.write().backupPath() != null) {
                final Path parent = r.write().backupPath().getParent();
                if (parent != null && parent.getFileName() != null) {
                    return "temp\\saveedit-backup\\" + parent.getFileName();
                }
            }
        } catch (Throwable ignored) {
            // 读不出来就给根目录
        }
        return "temp\\saveedit-backup";
    }

    /** 把异常压成<b>一行</b>（面板上只给一行；完整栈照旧进日志）。 */
    private static String oneLine(Throwable t) {
        if (t == null) {
            return "";
        }
        String msg = null;
        try {
            msg = t.getMessage();
        } catch (Throwable ignored) {
            // 连 getMessage 都抛 ⇒ 走 toString
        }
        if (msg == null || msg.isEmpty()) {
            msg = String.valueOf(t.getClass().getSimpleName());
        } else {
            msg = t.getClass().getSimpleName() + ": " + msg;
        }
        final int nl = msg.indexOf('\n');
        if (nl >= 0) {
            msg = msg.substring(0, nl);
        }
        msg = msg.replace('\r', ' ').trim();
        return msg.length() > 160 ? msg.substring(0, 160) + "…" : msg;
    }

    // ================================================================== 三档结局（文案必须说真话）

    /**
     * 任何一步出错/停手时的<b>唯一落点</b>：写日志 → 放锁 → 按<b>已有判据</b>选一条诚实的文案。
     *
     * <p>🔴 判据只有 {@code applyResult.ok()}（= 日志里 {@code apply_ok} 那个字段）：
     * <ul>
     *   <li>{@code true}  ⇒ 结局 <b>B</b>：存档<b>已经改好</b>了，重开没成 ⇒「请重新进入这个存档」；
     *       只有"世界还开着（停不下来）"才是 B3「完全退出游戏再进一次」（见 {@link #worldStillRunning}）。</li>
     *   <li>{@code false} ⇒ 结局 <b>C</b>：没有改动存档 ⇒ 原因 + 备份在哪。</li>
     * </ul>
     * 2026-11-20 21:29 的现场就是 {@code apply_ok=true} 之后走了老代码那一句
     * "存档本身没有被改动" —— <b>那是假话</b>；本类另一半修复就是把这条分叉做对。
     */
    private static void fail(Minecraft mc, ShanhaiSaveEditFlow flow,
                             ShanhaiPlayerDataEditor.Result applyResult, String why, Throwable t) {
        final boolean applyOk = applyResult != null && applyResult.ok();
        final String outcome = applyOk ? "B_reopen_failed" : "C_no_write";
        if (t != null) {
            ShanhaiMod.LOGGER.error("{} {} apply_ok={} outcome={} err={} flow={}",
                    PREFIX, why, applyOk, outcome, t.toString(), flow.traceLine(), t);
        } else {
            ShanhaiMod.LOGGER.error("{} {} apply_ok={} outcome={} flow={}",
                    PREFIX, why, applyOk, outcome, flow.traceLine());
        }
        flow.fail(why);
        logFlowNotify();
        final String errLine = oneLine(t);
        // 面板上那"一行原因"：认识的 token 换成玩家看得懂的话；不认识的（例如异常那一路）
        // 就把异常压成一行原样给他 —— 宁可稍技术，也不许编一句好听的。
        final String screenReason = playerReason(why, errLine.isEmpty() ? why : errLine);
        if (applyOk) {
            showAppliedNoReopen(mc, screenReason, applyResult);
        } else {
            showNoWrite(mc, screenReason, applyResult);
        }
    }

    /**
     * 日志 token ⇒ 玩家看得懂的<b>一行</b>原因（不认识就原样返回 {@code detail}）。
     *
     * <p>token 本身照旧写进日志（{@code pack_set_incomplete missing=[…]} 这类是排查用的，不给人看）。
     */
    private static String playerReason(String why, String detail) {
        final String token = why == null ? "" : why;
        if (token.startsWith("pack_set_incomplete")) {
            return "这台整合的格雷/模组动态数据包只能在玩家进世界时生成，改完盘再原地重开一次会让它崩";
        }
        if (token.startsWith("data_cfg_missing")) {
            return "这个存档的数据包配置读不出来";
        }
        if (token.startsWith("level_id_unresolved")) {
            return "算不出这个存档的目录名";
        }
        if (token.startsWith("apply_not_ok")) {
            return "写入那一步没有成功";
        }
        return detail;
    }

    /** 结局 B：写入成功、重开没成（B2 = 重进存档 / B3 = 才需要退出游戏）。 */
    private static void showAppliedNoReopen(Minecraft mc, String detail,
                                            ShanhaiPlayerDataEditor.Result applyResult) {
        final boolean stillRunning = worldStillRunning(mc);
        LAST_OUTCOME = "已写入 " + changedSummary(applyResult) + "；重开没成：" + detail;
        ShanhaiMod.LOGGER.warn("{} reopen_failed outcome=B{} world_still_running={} reason_human=\"{}\" backup={}",
                PREFIX, stillRunning ? "3" : "2", stillRunning, detail, backupDir(applyResult));
        final String body = "存档已经改好（" + changedSummary(applyResult) + "）。\n\n"
                + "但没能自动重新载入世界：" + detail + "\n\n"
                + (stillRunning
                ? "现在世界还开着、没能退出来：请完全退出游戏，再进一次。"
                : "请重新进入这个存档 —— 不用退出游戏。\n万一重进时游戏报错，再完全退出游戏进一次。")
                + "\n\n备份在 " + backupDir(applyResult) + "。";
        showScreen(mc, "存档已改好", body);
    }

    /** 结局 C：没有改动存档（写入失败 / 被拒 / 没有实际变化）。 */
    private static void showNoWrite(Minecraft mc, String detail,
                                    ShanhaiPlayerDataEditor.Result applyResult) {
        final boolean maybeWritten = applyResult != null && applyResult.write() != null;
        LAST_OUTCOME = "没有改动存档（" + detail + "）";
        final String body = "原因：" + detail + "\n\n"
                + (maybeWritten
                ? "⚠️ 这次写入没有完全成功：可能有部分内容已经落盘 —— 以备份为准；完全退出游戏后再进一次看看。"
                : "存档一个字节都没改。")
                + "\n\n备份在 " + backupDir(applyResult) + "。";
        showScreen(mc, "没有改动存档", body);
    }

    /** 统一的"带一个回标题按钮"的提示屏；连建屏都失败也不许把玩家卡在加载屏上。 */
    private static void showScreen(Minecraft mc, String title, String body) {
        try {
            mc.setScreen(new AlertScreen(
                    () -> mc.setScreen(new TitleScreen()),
                    Component.literal(title), Component.literal(body)));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} fail_screen_failed err={}", PREFIX, t.toString());
        }
    }

    /**
     * 世界是不是<b>还开着</b>（= 停服没成功）—— B2 与 B3 的唯一分界。
     *
     * <p>判据用原版自己的状态：{@code Minecraft#clearLevel(...)} 会把 {@code this.singleplayerServer}
     * 与 {@code this.level} 都置 {@code null}（{@code Minecraft.java:2044-2066}，字节码已核）⇒
     * 两者都是 {@code null} 就说明"世界已经停了、盘就是权威"，这时的正确提示是
     * "重新进入这个存档"，<b>不是</b>"退出游戏"。
     * <p>判不出来时按<b>最重</b>那一档说（B3）：宁可让他多重启一次，也不许在"世界可能还开着"的
     * 情况下骗他"重进就行" —— 那会让内存里的旧世界再写一遍、把改动盖掉。
     */
    private static boolean worldStillRunning(Minecraft mc) {
        try {
            return mc.getSingleplayerServer() != null || mc.level != null;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} world_running_probe_failed err={}", PREFIX, t.toString());
            return true;
        }
    }

    /**
     * 把 {@link ShanhaiSaveEditFlow} 记下的那一行（{@code flow_released reason=…} /
     * {@code flow_stolen reason=…}）写进日志；没有就不写。
     *
     * <p>那个状态机是纯 JDK（不带日志依赖），只"记账"；写日志由调用方做 —— 与
     * {@code ShanhaiSaveEditBridge#logFlowNotify()} 同一条口径。
     */
    private static void logFlowNotify() {
        final String line = ShanhaiSaveEditFlow.takeNotify();
        if (line != null && !line.isEmpty()) {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        }
    }

    /** 我们的加载屏（原版 QuickPlay 用的是同一句 key）。 */
    private static Screen loadingScreen() {
        return new GenericDirtMessageScreen(Component.translatable("selectWorld.data_read"));
    }

    /**
     * 回到世界之后再往聊天栏发一行（重开过程中发会丢：那时玩家还没登录）。
     *
     * <p>最多等 ~60 秒；拿到玩家就发一次然后退出，绝不留常驻线程。
     */
    private static void notifyWhenInWorld(Minecraft mc, String text) {
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 1200; i++) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (mc.player != null && mc.level != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.literal(text), false);
                        }
                    });
                    return;
                }
            }
            ShanhaiMod.LOGGER.warn("{} notify_timeout text={}", PREFIX, text);
        });
    }
}
