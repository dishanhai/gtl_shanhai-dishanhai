package com.shanhai.client.locate;

import appeng.api.stacks.GenericStack;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.crafting.CraftingCPUScreen;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.network.ShanhaiLocateNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 山海重构 · 定位的<b>客户端那一半</b>：触发（Shift+右键）+ 画 6 秒线框。
 *
 * <h2>1. 触发点：{@code ScreenEvent.MouseButtonPressed.Pre}（<b>没有 mixin</b>）</h2>
 * 上游 extendedae_plus 是在 {@code AEBaseScreen#mouseClicked} 的 HEAD 上插一刀
 * （{@code @Inject(..., at = @At("HEAD"), cancellable = true)}，priority 1000），而且它<b>无条件</b>
 * {@code cir.setReturnValue(true)} ⇒ 那一次点击的<b>原方法体整段不执行</b>。
 * 所以：
 * <pre>
 *   ❌ 不能挂 {@code ScreenEvent.MouseButtonPressed} 的"之后"那半边（它由 Screen#mouseClicked 里派发）；
 *   ❌ 不能自己再往 {@code mouseClicked} 插一刀 —— 两个 HEAD 注入的先后顺序由 mixin 决定，
 *      "谁先谁后"不是我们能保证的事，而顺序一旦反了就可能变成"抢它的返回值"= 改了它的行为；
 *   ✅ 正确的地方在<b>更外面一层</b>：Forge 在 {@code MouseHandler#onPress} 里、
 *      <b>调用 {@code screen.mouseClicked(...)} 之前</b>就派发了
 *      {@code ForgeHooksClient.onScreenMouseClickedPre(screen, x, y, button)}
 *      （反编译件：{@code MouseHandler.java:89-95}）⇒ 我们<b>必然</b>先被调到，
 *      而且只要不 cancel，那一次 {@code mouseClicked} 照旧执行 ⇒ extendedae_plus 一字不动。
 * </pre>
 * ⇒ 「我们先发我们的请求，它照样跑它那套」：它的闸只认 {@code PatternProviderLogic}，
 * 我们的两台总成天生过不去 ⇒ 那个物品的样板只在我们这儿时，它<b>静默什么都不做</b>，
 * 玩家只看到我们这一行 —— 正是用户要的"不挡"。
 *
 * <h2>2. 客户端不猜（硬约束②）</h2>
 * 这里只判断"界面对不对 / 有没有按 Shift / 是不是右键 / 鼠标指着哪个键"，
 * <b>不判断哪个键有没有样板</b>（那是服务端的事）。指到了就发一个键过去。
 *
 * <h2>3. 高亮：6 秒、最多 8 个、原版 {@code RenderType.lines()}</h2>
 * <pre>
 *   时序（6 秒/替换/上限）全在 {@link ShanhaiLocateTimeline}（纯算术，可离线断言）；
 *   本类只负责：把收到的行灌进它、每帧问它"现在该画哪几个"、用
 *   {@code LevelRenderer.renderLineBox(PoseStack, VertexConsumer, ...)} 画线框
 *   —— <b>不碰裸 GL</b>（用户规格 6），状态由 {@code RenderType.lines()} 自己开关，
 *   末尾一次 {@code endBatch()} 提交（本工程全息渲染器同一套写法）。
 * </pre>
 *
 * <h2>4. 线程</h2>
 * 两个入口（S2C 的 sink、渲染帧）都在客户端主线程；{@link #CLOCK} 也是在同一线程读。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiLocateClient {

    public static final String PREFIX = ShanhaiLocateNetwork.PREFIX;

    /** 线框颜色（金 → 偏暖白；与全息、黑潮那些用色不撞）。 */
    public static final float RED = 1.0F;
    public static final float GREEN = 0.82F;
    public static final float BLUE = 0.25F;
    public static final float ALPHA = 1.0F;

    /** 框比方块大一点点，免得和方块自己的面重叠导致 z-fighting。 */
    public static final double BOX_SHRINK = -0.01D;

    /** 每批顶点用的 builder 容量（8 个框 × 12 条边 × 2 顶点 = 192 ⇒ 512 足够，绝不 realloc）。 */
    public static final int BUFFER_CAPACITY = 512;

    /** 高亮时序（纯算术）。 */
    private static final ShanhaiLocateTimeline TIMELINE = new ShanhaiLocateTimeline();

    /**
     * 🔴 <b>可注入的时钟</b>（用户规格：「客户端侧'高亮 6 秒后消失'用一个可注入时钟断言」）。
     * 生产 = {@code System.currentTimeMillis}；离线断言不碰本类，直接调
     * {@link ShanhaiLocateTimeline#alive(long)} 并自己传时刻（同一份时序核，两种读法）。
     */
    private static final java.util.function.LongSupplier CLOCK = System::currentTimeMillis;

    /** 本批第一次真画出去的时刻（日志用；-1 = 本批还没画过）。 */
    private static long firstDrawLoggedAt = -1L;

    static {
        // 客户端开机就把 S2C 的落点挂上（common 那侧只认这个接口，专服上永远是 null）
        ShanhaiLocateNetwork.setClientSink(ShanhaiLocateClient::accept);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiLocateTimeline.selfTest());
    }

    private ShanhaiLocateClient() {}

    // ================================================================ 触发

    /**
     * 用户规格的触发：<b>合成状态 / 合成CPU 屏 + 按住 Shift + 右键点在表格里的物品图标上</b>。
     *
     * <p>逐条判据（与上游 extendedae_plus 的客户端那一刀<b>同源</b>，所以我们看到的是同一个手势）：
     * <pre>
     *   ① {@code screen instanceof CraftingCPUScreen} —— "合成CPU"屏与它的子类"合成状态"屏
     *      （{@code CraftingStatusScreen extends CraftingCPUScreen}）；
     *   ② {@code Screen.hasShiftDown()} —— 只认 Shift（Ctrl / Alt 不算）；
     *   ③ {@code button == 1} —— 只认右键（左键留给 extendedae_plus 的"跳到对面机器"）；
     *   ④ {@code getStackUnderMouse(x, y)} 非 null 且 {@code stack().what()} 非 null
     *      —— 必须指着<b>表格里的那行物品</b>（空白/处理器名/按钮都不算）。
     * </pre>
     */
    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        try {
            if (event.getButton() != 1) {
                return;
            }
            if (!Screen.hasShiftDown()) {
                return;
            }
            final Screen screen = event.getScreen();
            if (!(screen instanceof CraftingCPUScreen<?> craftingScreen)) {
                return;
            }
            final StackWithBounds under;
            try {
                under = craftingScreen.getStackUnderMouse(event.getMouseX(), event.getMouseY());
            } catch (Throwable t) {
                // 表格还没初始化时这里会抛 —— 那是"这一下不算"，不是崩客户端
                ShanhaiMod.LOGGER.debug("{} locate_stack_lookup_failed err={}", PREFIX, t.toString());
                return;
            }
            if (under == null || under.stack() == null) {
                return;
            }
            final GenericStack stack = under.stack();
            if (stack.what() == null) {
                return;
            }
            // 只发"我指着哪个键"；有没有样板、在哪台、多远 —— 全是服务端的事
            ShanhaiLocateNetwork.requestLocate(stack.what());
        } catch (Throwable t) {
            // 渲染/输入线程抛异常 = 直接崩客户端 ⇒ 兜一层（同全息渲染器的口径）
            ShanhaiMod.LOGGER.error("{} locate_click_failed (这一下没发出去，游戏继续)", PREFIX, t);
        }
    }

    // ================================================================ 收

    /** S2C 落点：把服务端那几行灌进时序核（<b>替换</b>上一批，不叠加）。 */
    private static void accept(List<ShanhaiLocateNetwork.Row> rows) {
        final long now = CLOCK.getAsLong();
        TIMELINE.begin(now);
        int added = 0;
        StringBuilder dims = new StringBuilder();
        for (ShanhaiLocateNetwork.Row row : rows) {
            if (row == null) {
                continue;
            }
            if (TIMELINE.add(row.dim, row.x, row.y, row.z)) {
                added++;
                if (dims.length() > 0) {
                    dims.append(',');
                }
                dims.append(row.dim);
            }
        }
        firstDrawLoggedAt = -1L;
        ShanhaiMod.LOGGER.info("{} locate_result_recv rows={} added={} dims={} alive_ms={}",
                PREFIX, Integer.valueOf(rows.size()), Integer.valueOf(added), dims,
                Long.valueOf(ShanhaiLocateTimeline.HIGHLIGHT_MILLIS));
    }

    // ================================================================ 画

    /**
     * 每帧：把还活着的那几个框画成线框（原版 {@code RenderType.lines()} + {@code renderLineBox}）。
     *
     * <p>stage 选 {@code AFTER_TRANSLUCENT_BLOCKS}：在所有实体/方块之后、天气之前
     * ⇒ 框不会被半透明方块盖掉，也不会画在云/粒子上面。
     *
     * <p>坐标系：{@code RenderLevelStageEvent} 给的 PoseStack <b>已经是相机视图矩阵</b>
     * （本工程 {@code ShanhaiHoloMenuRenderer#applyHoloFrame} 的注释逐字写着这条），
     * 所以这里按"世界坐标 − 相机位置"平移，画完立刻 pop。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        final long now = CLOCK.getAsLong();
        final List<ShanhaiLocateTimeline.Entry> alive = TIMELINE.alive(now);
        if (alive.isEmpty()) {
            return;
        }
        final String here = level.dimension().location().toString();
        final Vec3 camera = event.getCamera().getPosition();
        final PoseStack poseStack = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(new BufferBuilder(BUFFER_CAPACITY));
        int drawn = 0;
        try {
            final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            poseStack.pushPose();
            try {
                poseStack.translate(-camera.x, -camera.y, -camera.z);
                for (ShanhaiLocateTimeline.Entry entry : alive) {
                    // 只画"我在的那个维度"里的框：别的维度的坐标在本维度画出来是纯错误信息
                    if (!here.equals(entry.dim)) {
                        continue;
                    }
                    LevelRenderer.renderLineBox(poseStack, lines,
                            entry.x + BOX_SHRINK, entry.y + BOX_SHRINK, entry.z + BOX_SHRINK,
                            entry.x + 1.0D - BOX_SHRINK, entry.y + 1.0D - BOX_SHRINK,
                            entry.z + 1.0D - BOX_SHRINK,
                            RED, GREEN, BLUE, ALPHA);
                    drawn++;
                }
            } finally {
                poseStack.popPose();
            }
        } catch (RuntimeException e) {
            // 渲染线程抛异常 = 直接崩客户端。宁可这一帧少画东西，也不要打断存档会话。
            ShanhaiMod.LOGGER.error("{} locate_render_failed (这一帧未画完，游戏继续)", PREFIX, e);
        } finally {
            buffers.endBatch();
        }
        // 一批只打一行：证明"框真的进过提交"（否则只剩聊天栏那行，看不出画没画）
        if (drawn > 0 && firstDrawLoggedAt < 0L) {
            firstDrawLoggedAt = now;
            ShanhaiMod.LOGGER.info("{} locate_draw n={} dim={} at_ms={} alive_until_ms={}",
                    PREFIX, Integer.valueOf(drawn), here, Long.valueOf(now),
                    Long.valueOf(now + ShanhaiLocateTimeline.HIGHLIGHT_MILLIS));
        }
    }
}
