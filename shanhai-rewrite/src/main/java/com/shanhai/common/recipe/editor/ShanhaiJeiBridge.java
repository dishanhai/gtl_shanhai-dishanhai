package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 山海重构 · <b>本模组的第一条网络通道</b>（服务端 → 客户端，只为一件事：让 JEI 跟着变）。
 *
 * <h2>1. 为什么必须新建一条</h2>
 * 2026-10-05 全量扫过 {@code com/shanhai} 下所有源码：
 * {@code SimpleChannel} / {@code PacketDistributor} / {@code registerMessage} / {@code NetworkRegistry}
 * —— <b>零命中</b>。也就是说本模组此前<b>没有任何网络通道</b>，而"服务端改了配方 ⇒ 客户端 JEI 也得跟着变"
 * 这一跳必须把数据送过去 ⇒ 只能从零建。
 *
 * <h2>2. 🔴 客户端【不许】自己重算配方（这是本设计最关键的一条）</h2>
 * 侦察到的字节码事实（不是我推断的）：
 * <pre>
 *   客户端收配方走 ClientPacketListener.handleUpdateRecipes -> RecipeManager.replaceRecipes
 *   而 gtceu 唯一往 GTRecipeLookup 灌配方的地方挂在 datapack 的 apply 上
 *   ⇒ 专用服 + 远程客户端下，客户端的 GT 索引是【空的】
 * </pre>
 * ⇒ 任何"让客户端自己按 id 去查 GT 配方、再重算"的写法在本场景下都会拿到空，
 * 表现是 <b>那一页变空/缺条</b>。所以：<b>新数据一律由包带过去</b>，客户端只做"贴上去"。
 *
 * <h2>3. 载荷</h2>
 * <pre>
 *   typeId        配方类型 id（gtceu:assembler）—— 客户端按它对号入座
 *   recipeId      配方 id（gtceu:assembler/xxx）—— 【完整 id】去重的依据
 *   action        1 = 改（贴新值）；2 = 删（从 JEI 那一页拿掉）
 *   inputsJson    GT 自己的 codec 出的输入表（{@code null} = 不动）
 *   outputsJson   同上
 *   duration/eut  改了才带（带 {@code hasXxx} 标志，不是靠 0 当哨兵 —— 0 是合法值）
 * </pre>
 * 形状用的是 {@link ShanhaiRecipeIoApply} 那套（{@code Content.codec(cap)}）⇒ 两侧只有一种形状。
 */
public final class ShanhaiJeiBridge {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    public static final String CHANNEL_NAME = "shanhai_editor_sync";
    public static final String CHANNEL_VERSION = "1";

    public static final int ACTION_CHANGED = 1;
    public static final int ACTION_REMOVED = 2;
    /** 对账：这条消息带的是"服务端这次启动真正套用了的那些 id"，客户端据此丢掉其余的补丁。 */
    public static final int ACTION_RECONCILE = 3;

    private static SimpleChannel channel;
    private static boolean registered;

    private ShanhaiJeiBridge() {}

    /** 注册（幂等）。挂点 = {@link ShanhaiRecipeEditorBootstrap} 的 {@code FMLCommonSetupEvent}。 */
    public static synchronized void register() {
        if (registered) {
            ShanhaiMod.LOGGER.info("{} net_channel_already_registered name={}", PREFIX, CHANNEL_NAME);
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(0, RecipeSyncMessage.class,
                    RecipeSyncMessage::encode, RecipeSyncMessage::decode, RecipeSyncMessage::handle);
            registered = true;
            ShanhaiMod.LOGGER.info("{} net_channel_registered name={} version={} id={} messages=1",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION, new ResourceLocation("shanhai", CHANNEL_NAME));
        } catch (Throwable t) {
            // 通道建不起来绝不能让服务端起不来（编辑器那半边的功能仍然可用）
            ShanhaiMod.LOGGER.error("{} net_channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    // ------------------------------------------------------------------ 广播

    /** 一条配方被改了 ⇒ 通知所有客户端"把这一页换成新值"。 */
    public static void broadcastRecipeChanged(MinecraftServer server, GTRecipe recipe) {
        if (recipe == null || recipe.id == null) {
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} net_skip reason=channel_not_ready id={}", PREFIX, recipe.id);
            return;
        }
        try {
            // 🔴🔴 2026-10-05 第 5 轮修 #4：载荷里【必须】带上额外条件。
            //    用户原话（逐字）：
            //      「JEI 没生效指的是jei里面没有写新条件，我删除条件了jei里面也没删除，
            //        jei一直显示的是老条件」
            //    根因（读代码即可确认，见 ShanhaiJeiRecipePatches.synthesize）：
            //      这条包原来只带 inputs / outputs / duration / eut 四样，
            //      条件【一个字节都没过去】⇒ 客户端造出来的替换配方永远是"老条件那一份"，
            //      而机器那一侧（服务端 GT 索引）是新的 ⇒ 正是他看到的"机器生效了、JEI 没有"。
            //    ⇒ 补上 conditionsJson（GT 自己的平铺形状；由 RecipeCondition.CODEC 编出来）。
            final com.google.gson.JsonArray conds = ShanhaiRecipeConditions.encodeOf(recipe);
            final RecipeSyncMessage msg = RecipeSyncMessage.changed(
                    recipe,
                    ShanhaiRecipeIoApply.tableJson(recipe, "inputs").toString(),
                    ShanhaiRecipeIoApply.tableJson(recipe, "outputs").toString(),
                    conds.toString());
            channel.send(PacketDistributor.ALL.noArg(), msg);
            RESTORE_OR_EDIT_NOTIFY.incrementAndGet();
            noteBackInForce(recipe.id.toString());   // 这条又回来了 ⇒ 不再替它保住"已删"补丁
            ShanhaiMod.LOGGER.info("{} net_sent action=changed id={} type={} dur={} eu={} in_bytes={} out_bytes={} "
                            + "cond_n={} cond_bytes={} cond_types={}",
                    PREFIX, recipe.id, recipe.getType() == null ? "?" : recipe.getType().registryName,
                    msg.duration, msg.eut, msg.inputsJson == null ? 0 : msg.inputsJson.length(),
                    msg.outputsJson == null ? 0 : msg.outputsJson.length(),
                    conds.size(), msg.conditionsJson == null ? 0 : msg.conditionsJson.length(),
                    ShanhaiRecipeConditions.summary(conds));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed id={} err={}", PREFIX, recipe.id, t.toString(), t);
        }
    }

    /** 一条配方被删了 ⇒ 通知客户端把它从 JEI 那一页拿掉。 */
    public static void broadcastRecipeRemoved(MinecraftServer server, GTRecipe recipe) {
        if (recipe == null || recipe.id == null) {
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} net_skip reason=channel_not_ready id={}", PREFIX, recipe.id);
            return;
        }
        try {
            channel.send(PacketDistributor.ALL.noArg(), RecipeSyncMessage.removed(recipe));
            RESTORE_OR_EDIT_NOTIFY.incrementAndGet();
            noteRemovedInForce(recipe.id.toString());   // 🔴 记下来：下一次对账要保住这条 ✓
            ShanhaiMod.LOGGER.info("{} net_sent action=removed id={}", PREFIX, recipe.id);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed id={} err={}", PREFIX, recipe.id, t.toString(), t);
        }
    }

    /**
     * 🆕🔴 <b>只按 id ＋ 类型通知"这条没了"</b>（{@code /shanhai edit restore} 那条路必须用它）。
     *
     * <h4>为什么需要单独一个重载（用户的现场读数）</h4>
     * 用户原话：「{@code /shanhai edit restore} 之后……它现在只有
     * {@code RESTORE_ALL … index_ms=13 vanilla_ms=105}，<b>整行没有 net_sent</b>」——
     * 因为"恢复原样"之后那条配方<b>已经不在配方表里了</b>，手里根本没有 {@code GTRecipe} 对象可传。
     * 而**已经验通的那条路（界面「删除这条」）**发的正是"单条通知"
     * （{@code vanilla_edit_remove … net_sent action=removed}）⇒ 这里就是把同一条路补到 restore 上。
     *
     * <p>上游老山海也是这个做法：每一条被抹掉的配方各发一次
     * （{@code DShanhaiRecipeModifierAPI.removeAndSync} → {@code syncToAll()}，
     * 出处 {@code handoff/outbound/参考-上游配方控制API.md} §5.2 第 554 行）。
     */
    public static void broadcastRecipeRemoved(MinecraftServer server, ResourceLocation recipeId,
                                              ResourceLocation typeId) {
        if (recipeId == null || !isReady()) {
            return;
        }
        if (typeId == null || "minecraft".equals(typeId.getNamespace())) {
            broadcastVanillaRemoved(recipeId, typeId);      // 非 GT 走 JEI 分类 uid 那条
            return;
        }
        try {
            channel.send(PacketDistributor.ALL.noArg(),
                    RecipeSyncMessage.removedById(typeId.toString(), recipeId.toString()));
            RESTORE_OR_EDIT_NOTIFY.incrementAndGet();
            noteRemovedInForce(recipeId.toString());    // 🔴 同上（restore/按 id 删那条路）
            ShanhaiMod.LOGGER.info("{} net_sent action=removed id={} type={} (by_id)",
                    PREFIX, recipeId, typeId);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed id={} err={}", PREFIX, recipeId, t.toString(), t);
        }
    }

    /**
     * 🆕🔴 <b>非 GT：按 id 现读活表 ⇒ 把这条整条推给客户端</b>（恢复/改动都用它）。
     *
     * <p>判据是"活表里还能不能读到"：读得到 ⇒ 发 changed（带整条字节）；读不到 ⇒ 发 removed。
     * 这就是用户点名的"照着已经通的那条路抄"。
     */
    public static void broadcastVanillaNow(MinecraftServer server, ResourceLocation recipeId,
                                           ResourceLocation typeId) {
        if (server == null || recipeId == null) {
            return;
        }
        final net.minecraft.world.item.crafting.Recipe<?> live =
                ShanhaiVanillaRecipeOps.readFromTable(server, recipeId);
        if (live == null) {
            broadcastVanillaRemoved(recipeId, typeId);
            return;
        }
        final String fields = "{}";
        broadcastVanillaChanged(server, recipeId, typeId, fields,
                encodeVanillaRecipe(live));
    }

    /** 读数：单条通知发出去过几次（自检判据用）。 */
    private static final java.util.concurrent.atomic.AtomicInteger RESTORE_OR_EDIT_NOTIFY =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int singleNotifyCount() {
        return RESTORE_OR_EDIT_NOTIFY.get();
    }

    /**
     * 🆕 2026-10-05（工作台 / 原版配方）：<b>一条非 GT 配方被改了</b> ⇒ 通知客户端换掉 JEI 上那一条。
     *
     * <h4>🔴 为什么不另造一套刷新机制</h4>
     * 我们本来就挂着的注入点
     * {@code mezz.jei.library.recipes.PluginManager#getRecipes(RecipeTypeData, IFocusGroup, boolean)}
     * <b>每一次查询都会跑到</b>（不是"只在 JEI load 时跑一次"；JEI 15.49 与 15.62 两份 jar 的调用链
     * {@code RecipeLookup.get → getRecipesStream → PluginManager.getRecipes} 都对齐过）
     * ⇒ 缺的只是 {@code ShanhaiJeiRecipePatches} 那边<b>认得出原版配方</b>。
     * 见 {@code handoff/.../参考-四个配方编辑mod.md} §1.0 的 J4。
     *
     * <p>⚠️ 载荷带的是 <b>JEI 分类 uid</b>（{@code minecraft:crafting}），不是配方类型 id
     * （{@code minecraft:crafting_shaped}）—— 客户端 {@code jei_sync} 那行判据是按 uid 比的。
     */
    public static void broadcastVanillaChanged(MinecraftServer server,
                                               net.minecraft.resources.ResourceLocation recipeId,
                                               net.minecraft.resources.ResourceLocation recipeTypeId,
                                               String fieldsJson) {
        broadcastVanillaChanged(server, recipeId, recipeTypeId, fieldsJson, null);
    }

    /**
     * 🆕 <b>2026-10-05（第 13 刀 · 用户实测回来那一刀）</b>：带<b>配方字节</b>的那一版。
     *
     * <h4>为什么非 GT 也必须带字节（现场读数，逐字）</h4>
     * <pre>
     *   用户实例 logs\latest.log：
     *     jei_patch_stored id=shanhai:crafting/new_recipe_1 type=minecraft:crafting action=changed patches=2 kind=vanilla recipe_bytes=0
     *     jei_append_skipped id=shanhai:crafting/new_recipe_1 reason=no_recipe_bytes
     *     jei_sync matched=0 hidden=0 added=0 … visible_expected=0 expected=1 PASS=false
     *     jei_sync_ACCOUNTING_MISMATCH …
     * </pre>
     * ⇒ <b>改一条已有的能实时（它走"按 id 覆盖"那条路），但新增的那条进不去</b>：
     * 客户端手里没有原件 ⇒ 只能靠服务端把这条配方编成字节带过来。
     * GT 那一支本来就有（{@code GTRecipeSerializer}），非 GT 这一支漏了 ⇒ 本方法补上。
     *
     * <p>编码用的是 <b>原版自己那条写入器</b>{@code ClientboundUpdateRecipesPacket.toNetwork}
     * （与 {@code fromNetwork} 严格对称，客户端一定解得回来）。
     *
     * @param recipeBytes 这条配方的网络字节；{@code null} 时退回"只贴字段"的老行为
     */
    public static void broadcastVanillaChanged(MinecraftServer server,
                                               net.minecraft.resources.ResourceLocation recipeId,
                                               net.minecraft.resources.ResourceLocation recipeTypeId,
                                               String fieldsJson, byte[] recipeBytes) {
        if (recipeId == null) {
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} net_skip reason=channel_not_ready id={} kind=vanilla", PREFIX, recipeId);
            return;
        }
        final String uid = ShanhaiVanillaRecipeView.jeiUidOf(recipeTypeId);
        if (uid == null) {
            ShanhaiMod.LOGGER.warn("{} net_skip reason=no_jei_category id={} type={} "
                            + "（这个配方类型没有对应的 JEI 分类 ⇒ 只能靠重启客户端刷新 JEI）",
                    PREFIX, recipeId, recipeTypeId);
            return;
        }
        try {
            channel.send(PacketDistributor.ALL.noArg(),
                    RecipeSyncMessage.changedVanilla(uid, recipeId.toString(), fieldsJson, recipeBytes));
            RESTORE_OR_EDIT_NOTIFY.incrementAndGet();
            noteBackInForce(recipeId.toString());   // 这条又回来了 ⇒ 不再替它保住"已删"补丁
            ShanhaiMod.LOGGER.info("{} net_sent action=changed kind=vanilla id={} jei_uid={} fields={} recipe_bytes={}",
                    PREFIX, recipeId, uid, fieldsJson, recipeBytes == null ? 0 : recipeBytes.length);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed id={} err={}", PREFIX, recipeId, t.toString(), t);
        }
    }

    /**
     * 🆕 <b>非 GT 那条配方现在长什么样 ⇒ 编成"客户端能直接用的"一份载荷</b>（服务端侧只读）。
     *
     * <p>本方法是<b>唯一</b>的"非 GT 配方 ⇒ 包"的构造点：读活表里那一条（刚写完的那份）→
     * 原版编码器编成字节 → 一起塞进消息。<b>新增的那条第一次发出去时就带字节</b>，
     * 这样客户端才补得进 JEI（原因见上面那段读数）。
     *
     * @return 装好的消息；拿不到配方时退回"只有字段"的那一版（不抛）
     */
    public static RecipeSyncMessage vanillaChangedMessage(MinecraftServer server,
                                                          net.minecraft.resources.ResourceLocation recipeId,
                                                          net.minecraft.resources.ResourceLocation recipeTypeId,
                                                          String fieldsJson) {
        byte[] bytes = null;
        try {
            final net.minecraft.world.item.crafting.Recipe<?> live =
                    ShanhaiVanillaRecipeOps.readFromTable(server, recipeId);
            if (live != null) {
                bytes = encodeVanillaRecipe(live);
                if (bytes == null) {
                    ShanhaiMod.LOGGER.warn("{} net_encode_vanilla_null id={} class={} "
                                    + "（这条包仍然会发：客户端那一支只能显示老的那份）",
                            PREFIX, recipeId, live.getClass().getSimpleName());
                }
            } else {
                ShanhaiMod.LOGGER.warn("{} net_encode_vanilla_no_live id={} "
                                + "（活表里按 id 找不到这条 ⇒ 只能发字段）", PREFIX, recipeId);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_encode_vanilla_threw id={} err={}", PREFIX, recipeId, t.toString(), t);
        }
        final String uid = ShanhaiVanillaRecipeView.jeiUidOf(recipeTypeId);
        return RecipeSyncMessage.changedVanilla(uid == null ? "" : uid,
                recipeId == null ? "" : recipeId.toString(), fieldsJson, bytes);
    }

    /**
     * <b>非 GT 配方的网络字节</b>（走原版自己的写入器 {@code ClientboundUpdateRecipesPacket.toNetwork}）。
     *
     * <p>⚠️ 边界：任何一步失败都返回 {@code null}（宁可"这一条不实时"，也<b>绝不发半截包</b>），
     * 并打一行 ERROR 交代原因。
     */
    public static byte[] encodeVanillaRecipe(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (recipe == null) {
            return null;
        }
        io.netty.buffer.ByteBuf bb = null;
        try {
            bb = io.netty.buffer.Unpooled.buffer();
            final FriendlyByteBuf out = new FriendlyByteBuf(bb);
            net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket.toNetwork(out, recipe);
            final byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_encode_recipe_failed id={} class={} err={} "
                            + "(这条包仍然会发，只是客户端【补不进 / 换不掉】JEI 上那一条)",
                    PREFIX, recipe.getId(), recipe.getClass().getSimpleName(), t.toString(), t);
            return null;
        } finally {
            if (bb != null) {
                try {
                    bb.release();
                } catch (Throwable ignored) {
                    // 释放失败不影响正确性
                }
            }
        }
    }

    /** 🆕 非 GT 配方被删 ⇒ 通知客户端把它从 JEI 那一页拿掉。 */
    public static void broadcastVanillaRemoved(net.minecraft.resources.ResourceLocation recipeId,
                                               net.minecraft.resources.ResourceLocation recipeTypeId) {
        if (recipeId == null || !isReady()) {
            return;
        }
        final String uid = ShanhaiVanillaRecipeView.jeiUidOf(recipeTypeId);
        if (uid == null) {
            return;
        }
        try {
            channel.send(PacketDistributor.ALL.noArg(),
                    RecipeSyncMessage.removedVanilla(uid, recipeId.toString()));
            noteRemovedInForce(recipeId.toString());   // 🔴 记下来：下一次对账要保住这条 ✓
            ShanhaiMod.LOGGER.info("{} net_sent action=removed kind=vanilla id={} jei_uid={}",
                    PREFIX, recipeId, uid);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed id={} err={}", PREFIX, recipeId, t.toString(), t);
        }
    }

    /**
     * <b>对账广播</b>：把"这次启动真正在生效的 id"发给一个玩家，客户端据此清掉不在名单里的补丁。
     *
     * <p>修的是这个症状（用户原话）：「一重进存档就 KJS 报错（覆盖层不套用）／机器跑的是老配方／
     * <b>但 JEI 里还看得见改过的</b>」—— 第三条是客户端内存里那张补丁表还活着造成的假象。
     * 发在"开面板"那一刻是刻意的：那是唯一保证客户端一定在线、且用户马上要看到真实状态的一拍。
     */
    public static void sendReconcile(net.minecraft.server.level.ServerPlayer player,
                                     java.util.Collection<String> inForce) {
        if (player == null) {
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} net_skip reason=channel_not_ready action=reconcile", PREFIX);
            return;
        }
        // 🔴🔴 2026-10-06（用户：「工作台的 jei 和实际配方无法正确同步……删除的时候 jei 和实际都无法实时同步」）：
        //    名单必须并上"本局我让客户端删掉的那些 id"，否则下面这条链会当场翻车 —— 现场逐字读数：
        //      16:33:34.180 jei_patch_stored  … action=removed patches=2      ← 藏住了 ✓
        //      16:33:34.186 jei_runtime_hidden … hidden=2 total=12            ← 确实藏了 ✓
        //      16:34:29.122 jei_patch_dropped  … reason=not_in_force          ← 55 秒后丢了 ✗
        //      16:34:29.129 jei_runtime_unhidden … unhidden=2 total=4         ← 又还回来了 ✗✗
        //    机制：appliedIds() 给的是【账本里还有哪些条目】，而"本编辑器新建、随后被删"的那条
        //    配方，它的 op=add 条目已经从账本里抹掉了 ⇒ 天然不在名单里 ⇒ 客户端把那条
        //    action=removed 的补丁当"没在生效"丢掉，顺手把藏起来的原件还回去 ⇒ "删了还在"。
        //    ⚠️ 这不削弱对账本来要挡的事：REMOVED_IN_FORCE 是【进程内】的，服务端一重启就空
        //       ⇒ "重进存档后客户端还留着过时补丁"照旧会被清掉 ✓
        final java.util.List<String> keep = new java.util.ArrayList<>(
                inForce == null ? java.util.List.<String>of() : inForce);
        int removedKept = 0;
        for (String id : REMOVED_IN_FORCE) {
            if (!keep.contains(id)) {
                keep.add(id);
                removedKept++;
            }
        }
        try {
            channel.send(PacketDistributor.PLAYER.with(() -> player),
                    RecipeSyncMessage.reconcile(keep));
            ShanhaiMod.LOGGER.info("{} net_sent action=reconcile player={} in_force={} removed_kept={} "
                            + "(in_force = 账本条目 ＋ 本局发过 removed 的 id；后者不并进来，"
                            + "被删的配方会在下一次对账时从 JEI 里【回来】—— 见 2026-10-06 现场读数)",
                    PREFIX, player.getName().getString(), keep.size(), removedKept);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} net_send_failed action=reconcile err={}", PREFIX, t.toString(), t);
        }
    }

    /**
     * 🔴 <b>本局"已经让客户端删掉"的那些 id</b>（进程内；服务端一重启就空）。
     *
     * <p>唯一用途 = {@link #sendReconcile} 的保留名单（机理见那里的整段注释）。
     * 只增不减，例外只有一个：那条配方又回来了（{@link #noteBackInForce}）。
     * 集合大小 = 本局删过几条配方，量级极小。
     */
    private static final java.util.Set<String> REMOVED_IN_FORCE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 读数：本局记了几条"让客户端删掉"的 id（日志/自检用）。 */
    public static int removedInForceCount() {
        return REMOVED_IN_FORCE.size();
    }

    /** 发过 {@code action=removed} ⇒ 记下来（下一次对账要保住这条 id 的补丁）。 */
    private static void noteRemovedInForce(String id) {
        if (id != null && !id.isEmpty()) {
            REMOVED_IN_FORCE.add(id);
        }
    }

    /** 发过 {@code action=changed} ⇒ 这条配方又回来了（不再需要替它保住"已删"那个补丁）。 */
    private static void noteBackInForce(String id) {
        if (id != null && !id.isEmpty()) {
            REMOVED_IN_FORCE.remove(id);
        }
    }

    /** 装机读数（自检/日志用）。 */
    public static String statsLine() {
        return "channel=" + CHANNEL_NAME + " ready=" + isReady() + " version=" + CHANNEL_VERSION;
    }

    /**
     * 🔴 <b>第 5 轮修 #4 的机器判据</b>：一条带条件的同步消息<b>过一遍真正的编码器/解码器</b>
     * 之后，条件必须一个字节不差地回来。
     *
     * <p>为什么必须有这一条：客户端 JEI 那一份是<b>照这条包造出来的</b>（见
     * {@code ShanhaiJeiRecipePatches#synthesize}）。而这里验不了客户端 ⇒
     * 就把"能不能过网"这件事单独钉死：<b>编码 → 解码 → 逐字比</b>。
     * 上一轮这条包压根没有 conditions 字段，所以"加新条件 JEI 不显示、删条件 JEI 也不删"。
     *
     * <p>判据行：{@code JEI_MSG_SELFCHECK n/n PASS=…}。
     */
    public static boolean messageRoundTripSelfcheck() {
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();
        final String condJson = "[{\"cleanroom\":\"law_cleanroom\",\"type\":\"cleanroom\"},"
                + "{\"level\":4,\"module_id\":\"shanhai:virtual_image_material_module\",\"type\":\"module_level\"}]";

        // ① 正向：改了条件的那一条
        total++;
        try {
            final io.netty.buffer.UnpooledByteBufAllocator alloc = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
            final FriendlyByteBuf buf = new FriendlyByteBuf(alloc.buffer());
            new RecipeSyncMessage("gtceu:assembler", "gtceu:assembler/probe", ACTION_CHANGED,
                    "{\"item\":[]}", "{\"item\":[]}", condJson, true, 100, true, 30L, null).encode(buf);
            final RecipeSyncMessage back = RecipeSyncMessage.decode(buf);
            if (condJson.equals(back.conditionsJson) && back.hasDuration && back.duration == 100
                    && back.hasEut && back.eut == 30L && ACTION_CHANGED == back.action
                    && "gtceu:assembler/probe".equals(back.recipeId)) {
                pass++;
            } else {
                bad.append(" RT1(cond=").append(back.conditionsJson).append(" dur=").append(back.duration).append(')');
            }
            buf.release();
        } catch (Throwable t) {
            bad.append(" RT1 threw ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }

        // ② 负对照：不带条件的删除包 ⇒ 解出来 conditionsJson 必须是 null（不是空串、也不是"上一条的残留"）
        total++;
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RecipeSyncMessage("gtceu:assembler", "gtceu:assembler/probe2", ACTION_REMOVED,
                    null, null, null, false, 0, false, 0L, null).encode(buf);
            final RecipeSyncMessage back = RecipeSyncMessage.decode(buf);
            if (back.conditionsJson == null && ACTION_REMOVED == back.action) {
                pass++;
            } else {
                bad.append(" NEG(cond=").append(back.conditionsJson).append(")");
            }
            buf.release();
        } catch (Throwable t) {
            bad.append(" NEG threw ").append(t.getClass().getSimpleName());
        }

        // ③ 口径：对账包（reconcile）走到那条分支时，后面的字段一个都不许被读（否则会错位）
        total++;
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT.buffer());
            new RecipeSyncMessage(null, "", ACTION_RECONCILE, null, null, null,
                    false, 0, false, 0L, java.util.List.of("a", "b")).encode(buf);
            final RecipeSyncMessage back = RecipeSyncMessage.decode(buf);
            if (ACTION_RECONCILE == back.action && back.inForce != null && back.inForce.size() == 2) {
                pass++;
            } else {
                bad.append(" RECONCILE(").append(back.inForce).append(')');
            }
            buf.release();
        } catch (Throwable t) {
            bad.append(" RECONCILE threw ").append(t.getClass().getSimpleName());
        }

        final boolean ok = pass == total;
        ShanhaiMod.LOGGER.info("{} JEI_MSG_SELFCHECK {}/{} PASS={}（判据 = 带 conditions 的同步包"
                        + "过一遍真编解码器后逐字回来；②③ = 负对照/口径对照）",
                PREFIX, pass, total, ok);
        if (!ok) {
            ShanhaiMod.LOGGER.error("{} JEI_MSG_SELFCHECK 未过：{}", PREFIX, bad);
        }
        return ok;
    }

    // ------------------------------------------------------------------ 消息

    /** 一条同步消息（一条配方一条）。 */
    public static final class RecipeSyncMessage {

        public final String typeId;
        public final String recipeId;
        public final int action;
        public final String inputsJson;
        public final String outputsJson;
        /** 🆕 第 5 轮：额外条件的平铺 JSON（{@code null} = 不改动客户端那一份条件）。 */
        public final String conditionsJson;
        public final boolean hasDuration;
        public final int duration;
        public final boolean hasEut;
        public final long eut;
        /** 只为 {@link #ACTION_RECONCILE}：在生效的完整 recipe id 名单。 */
        public final java.util.List<String> inForce;

        /**
         * 🆕 2026-10-05（工作台 / 原版配方）：<b>非 GT 的载荷</b>。
         *
         * <p>形状 = 覆盖层 entry 里那同一份 {@code fields} 的 JSON
         * （{@code {"result":{…},"cookingtime":N,"experience":X}}），
         * 客户端用 {@link com.shanhai.client.jei.ShanhaiJeiRecipePatches} 里的原版分支
         * 重建一条同类新实例。{@code null} = 这条包不是原版配方那一支。
         *
         * <p>🔴 为什么不做成"再塞一条 GT 形状的包"：原版配方的字段词汇表完全不同
         * （没有 {@code inputs/outputs/tickInputs/duration/euTier}），
         * 硬套会在客户端造成"静默显示老值"（这正是第 5 轮条件那个 bug 的形态）。
         */
        public final String vanillaJson;

        /**
         * 🆕 2026-10-05（修复②：<b>新增的配方在 JEI 里不实时更新</b>）：
         * 这条配方的 <b>GT 网络编码字节</b>（{@code GTRecipeSerializer.SERIALIZER.toNetwork} 的产物）。
         *
         * <h4>为什么非要有它</h4>
         * 用户原话（逐字）：<b>「修改存在的配方 jei 是可以实时更新的，删除也可以，但是添加就不会了」</b>。
         * 实测读数（用户实例 {@code logs\latest.log} 原文）：
         * <pre>
         *   jei_sync matched=0 hidden=0 added=0 id=shanhai:worldline_probability_cracking/new_recipe_1 … visible_expected=0 expected=1 PASS=false channel=category
         *   jei_sync_ACCOUNTING_MISMATCH … visible=0 expected=1
         * </pre>
         * {@code matched=0} ⇒ <b>"新增的那条压根没出现在那个查询出口里"</b>。
         * 而 {@code patched()} 只会把<b>已经在列表里</b>的原件按 id 换成我们造的那一份
         * —— <b>它从来没有"往列表里补一条不存在的东西"的能力</b>。
         * <p>（这正是前置调查里那条红线的另一半：{@code PluginManager.getRecipes} 里的
         * {@code .distinct()} 按 {@code equals} 去重，而 GTRecipe 按实例身份比 ⇒
         * "再塞一条新对象"不会顶掉旧的 ⇒ 必须按 id 覆盖。<b>但"覆盖"只对已经存在的有用，
         * 对"本来就没有的"必须走"补进去"。</b>）
         *
         * <p>⇒ 服务端把这条配方<b>按 GT 自己的序列化器</b>编成字节带过来，客户端解回一个
         * {@code GTRecipe} 再包成 {@code GTRecipeWrapper} 补进那一页。
         * {@code null} = 这条包不带（原版配方那一支、以及删/对账包）。
         */
        public final byte[] recipeBytes;

        private RecipeSyncMessage(String typeId, String recipeId, int action,
                                  String inputsJson, String outputsJson, String conditionsJson,
                                  boolean hasDuration, int duration, boolean hasEut, long eut,
                                  java.util.List<String> inForce) {
            this(typeId, recipeId, action, inputsJson, outputsJson, conditionsJson,
                    hasDuration, duration, hasEut, eut, inForce, null, null);
        }

        private RecipeSyncMessage(String typeId, String recipeId, int action,
                                  String inputsJson, String outputsJson, String conditionsJson,
                                  boolean hasDuration, int duration, boolean hasEut, long eut,
                                  java.util.List<String> inForce, String vanillaJson) {
            this(typeId, recipeId, action, inputsJson, outputsJson, conditionsJson,
                    hasDuration, duration, hasEut, eut, inForce, vanillaJson, null);
        }

        private RecipeSyncMessage(String typeId, String recipeId, int action,
                                  String inputsJson, String outputsJson, String conditionsJson,
                                  boolean hasDuration, int duration, boolean hasEut, long eut,
                                  java.util.List<String> inForce, String vanillaJson, byte[] recipeBytes) {
            this.typeId = typeId;
            this.recipeId = recipeId;
            this.action = action;
            this.inputsJson = inputsJson;
            this.outputsJson = outputsJson;
            this.conditionsJson = conditionsJson;
            this.hasDuration = hasDuration;
            this.duration = duration;
            this.hasEut = hasEut;
            this.eut = eut;
            this.inForce = inForce;
            this.vanillaJson = vanillaJson;
            this.recipeBytes = recipeBytes;
        }

        public static RecipeSyncMessage changed(GTRecipe recipe, String inputsJson,
                                                String outputsJson, String conditionsJson) {
            return new RecipeSyncMessage(
                    recipe.getType() == null || recipe.getType().registryName == null
                            ? null : recipe.getType().registryName.toString(),
                    recipe.id.toString(), ACTION_CHANGED, inputsJson, outputsJson, conditionsJson,
                    true, recipe.duration, true, ShanhaiRecipeIoApply.euOf(recipe), null, null,
                    encodeRecipe(recipe));
        }

        /**
         * 一条 GT 配方 ⇒ <b>网络编码字节</b>（客户端解回来就是一模一样的一条）。
         *
         * <p>走的是 {@code GTRecipeSerializer.SERIALIZER.toNetwork} —— <b>GT 自己的序列化器</b>，
         * 也就是 {@code ClientboundUpdateRecipesPacket} 用的那一个 ⇒ 客户端一定解得回来。
         * 任何一步失败都返回 {@code null}（<b>宁可"新增那条不实时显示"，也不许发一条半截的包</b>）。
         */
        private static byte[] encodeRecipe(GTRecipe recipe) {
            if (recipe == null) {
                return null;
            }
            io.netty.buffer.ByteBuf bb = null;
            try {
                bb = io.netty.buffer.Unpooled.buffer();
                final FriendlyByteBuf out = new FriendlyByteBuf(bb);
                com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer.SERIALIZER.toNetwork(out, recipe);
                final byte[] bytes = new byte[out.readableBytes()];
                out.readBytes(bytes);
                return bytes;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} net_encode_recipe_failed id={} err={} "
                                + "(这条包仍然会发，只是客户端【没法把它补进 JEI 那一页】)",
                        PREFIX, recipe.id, t.toString(), t);
                return null;
            } finally {
                if (bb != null) {
                    try {
                        bb.release();
                    } catch (Throwable ignored) {
                        // 释放失败不影响正确性
                    }
                }
            }
        }

        public static RecipeSyncMessage removed(GTRecipe recipe) {
            return new RecipeSyncMessage(
                    recipe.getType() == null || recipe.getType().registryName == null
                            ? null : recipe.getType().registryName.toString(),
                    recipe.id.toString(), ACTION_REMOVED, null, null, null,
                    false, 0, false, 0L, null);
        }

        /** 🆕 只按 id ＋ 类型发"这条没了"（{@code /shanhai edit restore} 那条路要用）。 */
        public static RecipeSyncMessage removedById(String typeId, String recipeId) {
            return new RecipeSyncMessage(typeId == null ? "" : typeId, recipeId, ACTION_REMOVED,
                    null, null, null, false, 0, false, 0L, null);
        }

        public static RecipeSyncMessage reconcile(java.util.Collection<String> ids) {
            return new RecipeSyncMessage(null, "", ACTION_RECONCILE, null, null, null,
                    false, 0, false, 0L, ids == null ? java.util.List.of() : new java.util.ArrayList<>(ids));
        }

        /**
         * 🆕 <b>非 GT 配方</b>那一支的"改了"包。
         *
         * @param jeiUid         JEI 那个分类的 uid（{@code minecraft:crafting} / {@code minecraft:furnace} …）
         *                       —— <b>不是</b>配方类型 id，见 {@code ShanhaiVanillaRecipeView.jeiUidOf}
         * @param vanillaFieldsJson {@code fields} 形状（{@code result} / {@code cookingtime} / {@code experience}）
         */
        public static RecipeSyncMessage changedVanilla(String jeiUid, String recipeId, String vanillaFieldsJson) {
            return changedVanilla(jeiUid, recipeId, vanillaFieldsJson, null);
        }

        /**
         * 🆕 <b>2026-10-05 本轮（只修"卡几秒"与"JEI 不显示"两件）</b>：带<b>配方字节</b>的那一版。
         *
         * <p>为什么要字节：客户端 JEI 那一页里"本来没有"的那条（= 刚新建的）只能靠服务端把
         * 这条配方整条编过来（{@code ClientboundUpdateRecipesPacket} 那一对 toNetwork/fromNetwork）；
         * 而"本来有、但输入被改了"的那条，只贴 {@code result/cookingtime/experience} 三个字段
         * 也换不掉它显示的材料 —— 字节一带，这两件事一起解决。
         */
        public static RecipeSyncMessage changedVanilla(String jeiUid, String recipeId,
                                                       String vanillaFieldsJson, byte[] recipeBytes) {
            return new RecipeSyncMessage(jeiUid == null ? "" : jeiUid, recipeId, ACTION_CHANGED,
                    null, null, null, false, 0, false, 0L, null, vanillaFieldsJson, recipeBytes);
        }

        /** 🆕 非 GT 配方那一支的"删了"包。 */
        public static RecipeSyncMessage removedVanilla(String jeiUid, String recipeId) {
            return new RecipeSyncMessage(jeiUid, recipeId, ACTION_REMOVED,
                    null, null, null, false, 0, false, 0L, null, null);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(typeId == null ? "" : typeId, 512);
            buf.writeUtf(recipeId, 512);
            buf.writeByte(action);
            if (action == ACTION_RECONCILE) {
                final java.util.List<String> list = inForce == null ? java.util.List.of() : inForce;
                buf.writeVarInt(list.size());
                for (String s : list) {
                    buf.writeUtf(s, 512);
                }
                return;
            }
            writeOpt(buf, inputsJson);
            writeOpt(buf, outputsJson);
            writeOpt(buf, conditionsJson);
            writeOpt(buf, vanillaJson);
            // 🆕 修复②：GT 网络编码字节（客户端靠它把"新增的那条"补进 JEI 那一页）
            buf.writeBoolean(recipeBytes != null && recipeBytes.length > 0);
            if (recipeBytes != null && recipeBytes.length > 0) {
                buf.writeByteArray(recipeBytes);
            }
            buf.writeBoolean(hasDuration);
            if (hasDuration) {
                buf.writeVarInt(duration);
            }
            buf.writeBoolean(hasEut);
            if (hasEut) {
                buf.writeVarLong(eut);
            }
        }

        public static RecipeSyncMessage decode(FriendlyByteBuf buf) {
            final String typeId = buf.readUtf(512);
            final String recipeId = buf.readUtf(512);
            final int action = buf.readByte();
            if (action == ACTION_RECONCILE) {
                final int n = buf.readVarInt();
                final java.util.List<String> list = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(buf.readUtf(512));
                }
                return new RecipeSyncMessage(null, "", ACTION_RECONCILE, null, null, null,
                        false, 0, false, 0L, list);
            }
            final String inputs = readOpt(buf);
            final String outputs = readOpt(buf);
            final String conditions = readOpt(buf);
            final String vanilla = readOpt(buf);
            final byte[] recipeBytes = buf.readBoolean() ? buf.readByteArray() : null;
            final boolean hasDuration = buf.readBoolean();
            final int duration = hasDuration ? buf.readVarInt() : 0;
            final boolean hasEut = buf.readBoolean();
            final long eut = hasEut ? buf.readVarLong() : 0L;
            return new RecipeSyncMessage(typeId.isEmpty() ? null : typeId, recipeId, action,
                    inputs, outputs, conditions, hasDuration, duration, hasEut, eut, null, vanilla,
                    recipeBytes);
        }

        /**
         * 🔴 处理必须在客户端主线程上（{@code enqueueWork}），并且<b>用 {@link DistExecutor} 走向客户端类</b> ——
         * 本类在<b>两侧都会被加载</b>，直接写 {@code ShanhaiJeiRecipePatches.xxx} 会让专用服务端
         * 在做类校验时去找一个客户端类（那正是"专服起不来"那一族坑）。
         */
        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> {
                        if (action == ACTION_RECONCILE) {
                            com.shanhai.client.jei.ShanhaiJeiRecipePatches.reconcile(inForce);
                        } else {
                            com.shanhai.client.jei.ShanhaiJeiRecipePatches.accept(this);
                        }
                    }));
            ctx.setPacketHandled(true);
        }

        private static void writeOpt(FriendlyByteBuf buf, String s) {
            buf.writeBoolean(s != null);
            if (s != null) {
                buf.writeUtf(s, 262144);
            }
        }

        private static String readOpt(FriendlyByteBuf buf) {
            return buf.readBoolean() ? buf.readUtf(262144) : null;
        }
    }
}
