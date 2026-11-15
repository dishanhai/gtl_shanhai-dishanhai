package com.shanhai.client.saveedit;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.saveedit.ShanhaiPlayerDataEdit;
import com.shanhai.common.saveedit.ShanhaiPlayerDataEditor;
import com.shanhai.common.saveedit.ShanhaiSaveEditBridge;
import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditReopenGate;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * <h2>三、重开用哪条路（路线 B，避开实验性/数据包确认屏）</h2>
 * <pre>
 *   LevelStorageSource#validateAndCreateAccess(levelId)      ← 拿 session.lock
 *   LevelStorageAccess#readAdditionalLevelSaveData()         ← Forge 的世界额外数据（与 doLoadLevel 一致）
 *   ServerPacksSource#createPackRepository(access)
 *   MinecraftServer#configurePackRepository(packs, dataPacks, false, features)  ← 让 /reload 还是对的
 *   WorldOpenFlows#loadWorldStem(access, false)              ← 阻塞，这段就是加载屏时间
 *   Minecraft#doWorldLoad(levelId, access, packs, stem, false)
 * </pre>
 * 🔴 <b>路线 B 只在"数据包配置读得出来"时成立</b>：{@code access.getDataConfiguration()} 为 null 时
 * <b>不抛异常</b>，整段交回原版的 {@code WorldOpenFlows#loadLevel}（路线 A，即 QuickPlay 那条），
 * 并在日志打 {@code reopen_route=vanilla_loadLevel}。分支判断在
 * {@link ShanhaiSaveEditReopenRoute#of(Object)}（纯函数，离线可断言）。
 * <p>为什么不是"抛"：原版确实会在 {@code getPackConfigFromLevelData} 抛
 * {@code ISE("Failed to load data pack config")}，但它<b>发生在 {@code loadWorldStem} 内部、被
 * {@code doLoadLevel} 的 {@code catch (Exception)} 兜成 {@code DatapackLoadFailureScreen}</b>
 * —— 在原版里"读不到配置"是<b>降级</b>，不是致命；我们照抄它、不要在入口自己加一道更硬的闸。
 * <p>⚠️ <b>不调</b> {@code PrimaryLevelData#withConfirmedWarning(true)}：它会把
 * {@code confirmedExperimentalWarning} 置真、随即被 {@code doWorldLoad:2013} 的
 * {@code saveDataTag} 写进 level.dat —— 那是"本期不碰 level.dat"的红线；而路线 B 根本不走
 * {@code doLoadLevel} 里那道实验性确认屏（{@code WorldOpenFlows.java:201-206}），所以也不需要它。
 *
 * <p>⚠️ 已知差异（本期<b>主动挡掉而不是装作没有</b>）：这条路线跳过了
 * {@code DownloadedPackSource#loadBundledResourcePack}（{@code WorldOpenFlows.java:207}），
 * 也就是"存档自带的服务器资源包"不会重新加载。所以 {@link ShanhaiSaveEditBridge} 在启动前
 * 检查 {@code <存档>/server-resources}：存在就直接拒绝，让人手动退出重进。
 *
 * <h2>四、🔴 重开之前的两道闸（2026-11-14 加，判据都在 {@link ShanhaiSaveEditReopenGate}）</h2>
 * <pre>
 *   ① levelId 算不出来（或 level.dat 不在）⇒ fail(level_id_unresolved candidates=[…])，不重开。
 *       根因：1.20.1 的 LevelResource.ROOT 的 id 就是 "." ⇒ 旧实现拿到的 levelId 是 "."
 *             ⇒ validateAndCreateAccess(".") 指到 saves\. 这个不存在的目录 ⇒ 原版「数据包有错误」屏。
 *   ② applyResult 说"没有实际变化"（no_effective_change）且一个字节都没写 ⇒ 不重开，
 *       停在"没改"的返回屏。现场那行 region_page_failed:no_effective_change（这个位置本来就是 air）
 *       之后照样停了世界、照样重开。
 * </pre>
 * 两道闸都在 {@code markReopening()} <b>之前</b>，所以停手时状态机会 FAILED 并放锁（不会永远 BUSY）。
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
                fail(mc, flow, "begin_threw", t);
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
                fail(mc, flow, "reopen_threw", t);
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
        // ── 重开之前的两道闸（都在 markReopening 之前 ⇒ 一旦停手就 FAILED 放锁，不会永远 BUSY）
        // 🔴 闸 ①（本 bug 的根治点）：算不出 levelId ⇒ 一个字节都不许往重开走。
        //    现场（2026-11-14 09:57）：levelId 被算成 "."，于是
        //    validateAndCreateAccess(".")/loadLevel(".") 指向 saves\. 这个【不存在的世界目录】
        //    ⇒ getDataConfiguration() 必然 null ⇒ 掉回原版「数据包有错误」屏。
        if (ctx == null || !ctx.hasLevelId()) {
            fail(mc, flow, "level_id_unresolved candidates="
                    + (ctx == null ? "[]" : ctx.levelIdCandidatesLine()), null);
            return;
        }
        // 🔴 闸 ②：这次一个字节都没改成（no_effective_change）⇒ 不许重开。
        //    现场那行 region_page_failed:no_effective_change（这个位置本来就是 air）之后照样停了世界、
        //    照样重开，最后掉到原版错误屏；停手更诚实（存档原样，玩家回标题自己进）。
        if (ShanhaiSaveEditReopenGate.skipReopen(
                applyResult != null && applyResult.ok(),
                applyResult == null ? null : applyResult.error(),
                applyResult != null && applyResult.write() != null,
                ShanhaiSaveEditReopenGate::isNoEffectiveChange)) {
            skipReopenNoChange(mc, flow, applyResult);
            return;
        }
        final Screen loading = loadingScreen();
        mc.setScreen(loading);
        if (!flow.markReopening()) {
            ShanhaiMod.LOGGER.warn("{} reopen_phase_unexpected phase={}", PREFIX, flow.phase());
        }
        if (ioError != null) {
            ShanhaiMod.LOGGER.error("{} io_stage_threw err={}", PREFIX, ioError.toString());
        }
        LevelStorageSource.LevelStorageAccess access = null;
        WorldStem stem = null;
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
            if (ShanhaiSaveEditReopenRoute.of(cfg) != ShanhaiSaveEditReopenRoute.Route.PACK_RELOAD) {
                // 🔴 cfg 读不出来 ⇒ 不抛，整段交回原版（路线 A）。理由：
                //    原版也会在 loadWorldStem → getPackConfigFromLevelData 抛 ISE("Failed to load data pack config")，
                //    但那句在 doLoadLevel 里被 catch (Exception) 兜成 DatapackLoadFailureScreen ⇒ 在原版里这是"降级"。
                //    我们入口自己抛，就把它变成了"改完盘的人直接掉到失败屏"，比原版更硬 —— 那不是我们该加的检查。
                //    loadLevel 内部会自己重读一次配置：真只是瞬时读失败的话，世界照样能进。
                try {
                    access.close();     // 必须先放 session.lock：loadLevel 内部会 createWorldAccess 再拿一次
                } catch (Throwable ignored) {
                    // 关不掉也不改结论
                }
                access = null;
                ShanhaiMod.LOGGER.warn("{} reopen_route={} levelId={} reason=data_cfg_missing fallback_screen=saveedit_failed",
                        PREFIX, ShanhaiSaveEditReopenRoute.Route.VANILLA_LOAD_LEVEL.logName(), ctx.levelId);
                mc.createWorldOpenFlows().loadLevel(fallbackScreen(mc), ctx.levelId);
                if (!flow.markDone()) {
                    // 交回原版后我们不再持有任何资源；状态机万一没到 REOPENING 就直接放锁，免得下一次永远 BUSY
                    ShanhaiMod.LOGGER.warn("{} reopen_handoff_but_phase={} ⇒ force release", PREFIX, flow.phase());
                    flow.release();
                }
                ShanhaiMod.LOGGER.info("{} reopen_done route={} total_ms={} apply_ok={} "
                                + "title_screen_shown=false level_loading_screen_expected=true flow={}",
                        PREFIX, ShanhaiSaveEditReopenRoute.Route.VANILLA_LOAD_LEVEL.logName(),
                        (System.nanoTime() - t0) / 1_000_000L, applyResult != null && applyResult.ok(),
                        flow.traceLine());
                notifyOutcome(mc);
                return;
            }
            // 让新服的 getPackRepository() 是"装好了数据包"的那一个（否则以后 /reload 会读到空表）
            ShanhaiMod.LOGGER.info("{} reopen_route={} levelId={}", PREFIX,
                    ShanhaiSaveEditReopenRoute.Route.PACK_RELOAD.logName(), ctx.levelId);
            final PackRepository packs = ServerPacksSource.createPackRepository(access);
            MinecraftServer.configurePackRepository(packs, cfg.dataPacks(), false, cfg.enabledFeatures());
            ShanhaiMod.LOGGER.info("{} reopen_packs selected={}", PREFIX, packs.getSelectedIds());

            stem = mc.createWorldOpenFlows().loadWorldStem(access, false);
            // 🔴 故意【不】调 PrimaryLevelData#withConfirmedWarning(true)：
            //    它会把 confirmedExperimentalWarning 置真、由 doWorldLoad 的 saveDataTag 写进 level.dat
            //    ⇒ 那就是"本期不碰 level.dat"的红线。而且路线 B 根本不走 doLoadLevel 里那道
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
            notifyOutcome(mc);
        } catch (Throwable t) {
            if (stem != null) {
                try {
                    stem.close();
                } catch (Throwable ignored) {
                    // 关不掉也不改结论
                }
            }
            if (access != null) {
                try {
                    access.close();
                } catch (Throwable ignored) {
                    // 关不掉也不改结论
                }
            }
            fail(mc, flow, "reopen_failed", t);
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
                        + "apply_ok=false apply_write=null screen=saveedit_no_change flow={}",
                PREFIX, detail, flow.traceLine());
        try {
            mc.setScreen(new AlertScreen(
                    () -> mc.setScreen(new TitleScreen()),
                    Component.literal("这次没有实际变化，没有重开世界"),
                    Component.literal("存档一个字节都没改（" + detail + "）。\n\n"
                            + "世界已经停了：点下面的按钮回标题，再从「单人游戏」进这个世界就行。")));
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

    /** 交回原版后，万一数据包还是加载失败：原版的取消按钮会把玩家送到这张屏 ⇒ 再点一次才回标题。 */
    private static Screen fallbackScreen(Minecraft mc) {
        return new AlertScreen(
                () -> mc.setScreen(new TitleScreen()),
                Component.literal("存档直改没做完"),
                Component.literal("数据包配置读不出来，已经交回原版重开流程。\n\n"
                        + "存档本身没有被改动（备份在 temp\\saveedit-backup）。"));
    }

    /** 成功路径往聊天栏发的那一行（路线 B 与兜底共用）。 */
    private static void notifyOutcome(Minecraft mc) {
        final String outcome = outcomeText();
        notifyWhenInWorld(mc, "§8[存档直改] §f" + outcome + " §8· 备份在 §7temp\\saveedit-backup\\");
    }

    private static String outcomeText() {
        return LAST_OUTCOME == null || LAST_OUTCOME.isEmpty() ? "没有改盘" : LAST_OUTCOME;
    }

    // ================================================================== 兜底

    /**
     * 失败兜底：给一张带说明的屏 + 一个"回标题"按钮。
     *
     * <p>🔴 只有<b>出错</b>这条路才会出现 TitleScreen（成功后一个字都不会退到标题，用户原话：
     * "不要退到标题（没沉浸感）"）。宁可给一个能点回去的屏，也不许把玩家卡在全黑的加载屏上。
     */
    private static void fail(Minecraft mc, ShanhaiSaveEditFlow flow, String why, Throwable t) {
        if (t != null) {
            ShanhaiMod.LOGGER.error("{} {} err={} flow={}", PREFIX, why, t.toString(), flow.traceLine(), t);
        } else {
            ShanhaiMod.LOGGER.error("{} {} flow={}", PREFIX, why, flow.traceLine());
        }
        flow.fail(why);
        logFlowNotify();
        final String detail = (t == null ? why : why + ": " + t);
        LAST_OUTCOME = "失败：" + detail;
        try {
            mc.setScreen(new AlertScreen(
                    () -> mc.setScreen(new TitleScreen()),
                    Component.literal("存档直改没做完"),
                    Component.literal(detail + "\n\n存档本身没有被改动（备份在 temp\\saveedit-backup）。")));
        } catch (Throwable t2) {
            ShanhaiMod.LOGGER.error("{} fail_screen_failed err={}", PREFIX, t2.toString());
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
