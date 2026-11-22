package com.shanhai.client.pilot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.droplet.ShanhaiDropletEntity;
import com.shanhai.common.droplet.ShanhaiDropletPilotCore;
import com.shanhai.common.droplet.ShanhaiDropletPilotNetwork;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 驾驶功能的<b>客户端总入口</b>（一个 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)}，四件事）。
 *
 * <h2>一、四个挂点</h2>
 * <ol>
 *   <li>{@link TickEvent.ClientTickEvent}（END）—— ① 按键闸门；② 读 W/S 发意图包；③ 10 Hz 重算目标缓存。</li>
 *   <li>{@link RenderPlayerEvent.Pre} —— 骑乘期间<b>取消</b>玩家模型渲染（规格：隐藏玩家模型，不写 mixin）。</li>
 *   <li>{@link RenderLevelStageEvent} —— 3D 阶段只抓两样东西：本帧<b>投影矩阵</b>与<b>相机位姿</b>。</li>
 *   <li>{@link RenderGuiEvent.Post} —— 2D 阶段画绿框 / 标签 / 准星圈。</li>
 * </ol>
 *
 * <h2>二、为什么"取消渲染"就够了</h2>
 * {@code RenderPlayerEvent.Pre} 在 1.20.1 的声明里带 {@code @net.minecraftforge.eventbus.api.Cancelable}
 * （{@code javap -v} 实测：{@code RuntimeVisibleAnnotations: net.minecraftforge.eventbus.api.Cancelable}），
 * 取消即该玩家本帧不渲染。判据是"<b>载具是我们的水滴</b>"，所以：
 * ① 只影响驾驶中的那个玩家；② 未骑乘时一行代码都不跑；③ 不碰任何渲染器/模型类。
 *
 * <h2>三、为什么"每 tick 发包"而不是"只在变化时发"</h2>
 * 只在变化时发要依赖"服务端能收到最后一次状态"（丢一个 RELEASE 包就永远在加速）。
 * 每 tick 发（20 Hz、2–6 字节）既省掉这个假设，又让服务端侧"超时归零"（10 tick）成为可靠兜底。
 *
 * <h2>四、帧内开销（写进报告）</h2>
 * 见 {@link ShanhaiDropletPilotOverlay} 的类注释（最坏 32 目标 ≈ 9.2k 个 2×2 点 + 96 点圈 + 64 行文本）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletPilotClient {

    private static final String PREFIX = ShanhaiDropletPilotNetwork.PREFIX;

    /** 默认判定角度（在实体还没实现 {@link ShanhaiDropletPilotCore.PilotView} 时的退化值，与规格默认值一致）。 */
    private static final double DEFAULT_ASSIST_ANGLE = 5.0D;

    /** 本帧投影：{@code tan(fovY/2)}（= 1/m11）；≤0 表示本帧还没抓到。 */
    private static volatile double tanHalfFovY;

    /** 本帧相机位置/朝向（世界坐标）。 */
    private static volatile double camX;
    private static volatile double camY;
    private static volatile double camZ;
    private static volatile float camYaw;
    private static volatile float camPitch;

    /** 本 tick 的目标缓存（不可变列表，每 {@code TARGET_REFRESH_TICKS} tick 换一次）。 */
    private static volatile List<ShanhaiDropletPilotTargets.Mark> marks = List.of();

    /** 本 tick 的驾驶态（来自 {@link ShanhaiDropletPilotCore.PilotView}，没有实现则退化）。 */
    private static volatile boolean assistOn = true;
    private static volatile double assistAngleDeg = DEFAULT_ASSIST_ANGLE;
    private static volatile boolean autopilotOn;

    /** 意图包序号（只用于日志/丢包观测）。 */
    private static int intentSeq;

    /** 上一 tick 是否在驾驶（用于"进入/退出"各打一条日志，而不是每 tick 刷屏）。 */
    private static boolean piloting;

    /** 诊断计数。 */
    private static long ticksWhilePiloting;
    private static long keysSuppressedTotal;

    /** 被 cancel 的"攻击/使用/拾取"交互次数（鼠标键不走 KeyMapping 那条路）。 */
    private static long blockedInteractions;

    /** 按键诊断行的节流（1 秒）。 */
    private static final long KEY_LOG_INTERVAL_MS = 1000L;

    private static long lastKeyLogMs;

    private ShanhaiDropletPilotClient() {}

    // ------------------------------------------------------------------ ① 客户端 tick（PRE）

    /**
     * 🔴 必须在 {@link TickEvent.Phase#START} 做，不能等 {@code END}。
     *
     * <p>原因（1.20.1 原版顺序）：{@code Minecraft#tick()} <b>先</b>读按键（{@code Minecraft#handleKeybinds()}
     * 里用 {@code isDown()} / {@code consumeClick()} 派发攻击/使用/丢物品/热键栏/跳/潜行），
     * 之后才轮到 Forge 的 {@code ClientTickEvent.END}。第一版写在 END，
     * ⇒ 每一拍<b>已被消费掉一次</b>，所以"按住别的键还是有用"。
     * 现在改成 START（{@code handleKeybinds} 之前）清，并额外挂
     * {@link TickEvent.RenderTickEvent}（每帧再清一次）与
     * {@link net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered}（可取消）。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc == null ? null : mc.player;
        if (player == null || mc.level == null) {
            if (piloting) {
                reset("no_player");
            }
            return;
        }
        final Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof ShanhaiDropletEntity droplet)) {
            if (piloting) {
                reset("dismounted");
            }
            return;
        }
        if (!piloting) {
            piloting = true;
            ShanhaiMod.LOGGER.info("{} client_pilot_begin player={} droplet={} view=contract",
                    PREFIX, player.getUUID(), droplet.getUUID());
            // 白名单自检（F5 的放行落在 Keys 层，纯逻辑断言覆盖不到 ⇒ 这里给一条运行期读数：
            // 期望 f5=true … hotbar1_blocked=true，配合 keys_blocked 的 still_down=[] 一起判）
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiDropletPilotKeys.whitelistSelfTest(mc.options));
        }
        ticksWhilePiloting++;

        // ① 按键闸门（只放 W/S/Shift/E/Esc///T；其余每 tick + 每帧强制归零）
        suppressKeys(mc, "tick_pre");

        // ② W/S/Shift 意图：打开任何界面时按"没按"上报（否则"开着背包按住 W"会一直加速）
        final boolean guiOpen = mc.screen != null;
        final boolean forward = !guiOpen && ShanhaiDropletPilotKeys.forward(mc);
        final boolean backward = !guiOpen && ShanhaiDropletPilotKeys.backward(mc);
        final boolean sneak = !guiOpen && ShanhaiDropletPilotKeys.sneak(mc);
        ShanhaiDropletPilotNetwork.sendInput(forward, backward, sneak, ++intentSeq);

        // ③ 驾驶态：直接读水滴的同步字段（水滴实现了 PilotView 契约，见 ShanhaiDropletPilotCore.PilotView）
        autopilotOn = droplet.pilotAutopilot();
        assistOn = droplet.pilotAssist();
        final double angle = droplet.pilotAssistAngleDeg();
        assistAngleDeg = angle >= 1.0D && angle <= 45.0D ? angle : DEFAULT_ASSIST_ANGLE;

        // ④ 目标缓存：10 Hz 重算（128 格 AABB 扫描不便宜，逐帧重算没必要）
        if (player.tickCount % ShanhaiDropletPilotCore.TARGET_REFRESH_TICKS == 0) {
            marks = ShanhaiDropletPilotTargets.refresh(mc, droplet);
        }

        // ⑤ 每 200 tick 一条汇总（机器可判：能看出"到底有没有在跑"）
        if (ticksWhilePiloting % 200L == 0L) {
            ShanhaiMod.LOGGER.info("{} client_pilot_alive ticks={} keys_suppressed={} marks={} seq={} "
                            + "autopilot={} assist={}({}) teleports={} seat_delta={}",
                    PREFIX, Long.valueOf(ticksWhilePiloting), Long.valueOf(keysSuppressedTotal),
                    Integer.valueOf(marks.size()), Integer.valueOf(intentSeq),
                    Boolean.valueOf(autopilotOn), Boolean.valueOf(assistOn),
                    ShanhaiDropletPilotCore.speedText(assistAngleDeg),
                    Integer.valueOf(droplet.clientTeleportCount()),
                    ShanhaiDropletPilotCore.speedText(seatDelta(player, droplet)));
        }
    }

    /**
     * 每帧再清一次键（HUD 在帧里跑，某些逻辑会读 {@code isDown()}）—— 廉价、且能兜住"帧内被重新按下"。
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !piloting) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        if (!(mc.player.getVehicle() instanceof ShanhaiDropletEntity)) {
            return;
        }
        suppressKeys(mc, "render_pre");
    }

    /**
     * 鼠标左右键（攻击/使用/拾取）<b>不是</b> {@code KeyMapping} 的普通按键路径 ——
     * 它们由 {@code Minecraft#startAttack/startUseItem/pickBlock} 直接派发。
     * {@link net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered}
     * 带 {@code @Cancelable}（{@code javap -v} 实测：{@code RuntimeVisibleAnnotations: …Cancelable}）
     * ⇒ 在这三个动作真正发生前取消掉，比"清 KeyMapping"更彻底。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!piloting) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        if (!(mc.player.getVehicle() instanceof ShanhaiDropletEntity)) {
            return;
        }
        if (event.isAttack() || event.isUseItem() || event.isPickBlock()) {
            event.setCanceled(true);
            blockedInteractions++;
        }
    }

    /** 清一遍键 + 每秒一条"清了多少 / 还有哪些按着"的诊断行。 */
    private static void suppressKeys(Minecraft mc, String where) {
        final int cleared = ShanhaiDropletPilotKeys.enforce(mc);
        keysSuppressedTotal += cleared;
        final long now = System.currentTimeMillis();
        if (now - lastKeyLogMs < KEY_LOG_INTERVAL_MS) {
            return;
        }
        lastKeyLogMs = now;
        final String[] stillDown = ShanhaiDropletPilotKeys.stillDown(mc);
        ShanhaiMod.LOGGER.info("{} keys_blocked where={} clearing={} still_down=[{}] interactions_blocked={}",
                PREFIX, where, Integer.valueOf(cleared), String.join(",", stillDown),
                Long.valueOf(blockedInteractions));
    }

    /** 座位与水滴中心的位置差（格）。正常应 ≈ 0；明显不为 0 ⇒ 乘客位置被别的东西推着走。 */
    private static double seatDelta(LocalPlayer player, ShanhaiDropletEntity droplet) {
        return player.position().distanceTo(droplet.position());
    }

    // ------------------------------------------------------------------ ② 隐藏玩家模型

    /**
     * 骑乘我们的水滴时<b>不渲染该玩家</b>（规格：隐藏玩家模型）。
     *
     * <p>只判"载具是不是我们的水滴"，不判"是不是本地玩家"：别人开着自己的水滴从旁边过时，
     * 他那台水滴里的玩家也应该被隐藏（否则会看到一个人半身卡在水滴壳里）。
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (event == null || event.getEntity() == null) {
            return;
        }
        if (event.getEntity().getVehicle() instanceof ShanhaiDropletEntity) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ ③ 3D 阶段：抓投影与相机

    /**
     * 抓本帧的投影矩阵与相机位姿（只读，不画任何东西）。
     *
     * <p>抓 {@code m11} 而不是读 FOV 配置项：{@code m11 = 1/tan(fovY/2)}，这样动态 FOV
     * （冲刺/药水/水下/旁观者）都会自动跟随，准星圈的物理含义恒为"与视轴夹角 θ 的锥面"。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event == null || event.getCamera() == null) {
            return;
        }
        final Matrix4f projection = event.getProjectionMatrix();
        if (projection != null) {
            final float m11 = projection.m11();
            if (m11 > 1.0E-4F) {
                tanHalfFovY = 1.0D / (double) m11;
            }
        }
        final Camera camera = event.getCamera();
        camX = camera.getPosition().x;
        camY = camera.getPosition().y;
        camZ = camera.getPosition().z;
        camYaw = camera.getYRot();
        camPitch = camera.getXRot();
    }

    // ------------------------------------------------------------------ ④ 2D 阶段：画

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!piloting || event == null || event.getGuiGraphics() == null) {
            return;
        }
        final double tan = tanHalfFovY;
        if (tan <= 1.0E-6D) {
            return;             // 本帧还没抓到投影（进世界第一帧）：宁可这一帧不画
        }
        ShanhaiDropletPilotOverlay.render(Minecraft.getInstance(), event.getGuiGraphics(), marks,
                tan, camX, camY, camZ, camYaw, camPitch, assistOn, assistAngleDeg);
    }

    // ------------------------------------------------------------------ 清理

    /** 下线：把本类所有缓存清回初值（避免"退出服务器后还画上一局的目标框"）。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset("logout");
    }

    private static void reset(String reason) {
        piloting = false;
        marks = List.of();
        intentSeq = 0;
        autopilotOn = false;
        assistOn = true;
        assistAngleDeg = DEFAULT_ASSIST_ANGLE;
        ShanhaiMod.LOGGER.info("{} client_pilot_end reason={} ticks={} keys_suppressed={}",
                PREFIX, reason, Long.valueOf(ticksWhilePiloting), Long.valueOf(keysSuppressedTotal));
    }

    /** 诊断读数（调试用；不影响逻辑）。 */
    public static String statsLine() {
        return "piloting=" + piloting + " marks=" + marks.size() + " seq=" + intentSeq
                + " ticks=" + ticksWhilePiloting + " keys_suppressed=" + keysSuppressedTotal;
    }

    /** 供其它客户端渲染代码查询"当前是否驾驶中"（只读；本类不反向依赖任何渲染器）。 */
    public static boolean isPiloting() {
        return piloting;
    }

    /** 供其它客户端代码查询本帧 {@code tan(fovY/2)}（只读；≤0 表示尚未抓到）。 */
    public static double currentTanHalfFovY() {
        return tanHalfFovY;
    }

    /** 未使用的占位：保留 PoseStack 引用说明"本类不直接摆 3D 姿态"（避免误以为这里动了渲染栈）。 */
    static void unusedPoseDocumentation(PoseStack ignored) {
        // 故意留空：本类在 3D 阶段只读矩阵，不改 PoseStack。
    }
}
