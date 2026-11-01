package com.dishanhai.gt_shanhai.client.loading;

import net.minecraft.Util;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.commands.Commands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.progress.StartupNotificationManager;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import java.util.ArrayList;

/** 山海：資料適配與客戶端生命週期。所有 GL 工作限定在渲染執行緒。 */
@Mod.EventBusSubscriber(modid = "gt_shanhai", value = Dist.CLIENT)
public final class ShanhaiLoadingClient {
    private static final ShanhaiLoadingState WORLD = new ShanhaiLoadingState();
    private static final ThreadLocal<RecipeWork> RECIPE_WORK = new ThreadLocal<>();
    private static long startupStarted = -1;
    private static long lastWaitingFrame;
    private static boolean rendering;
    private static boolean renderFailed;
    private static boolean sawTerrain;

    private ShanhaiLoadingClient() {}

    @SubscribeEvent
    public static void onTitleInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen title) {
            event.addListener(Button.builder(Component.literal("載入分析"), button ->
                    Minecraft.getInstance().setScreen(new ShanhaiLoadingReportScreen(title, true)))
                    .bounds(title.width - 100, 6, 94, 20).build());
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shloading").executes(context -> {
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> mc.setScreen(new ShanhaiLoadingReportScreen(null, false)));
            return 1;
        }));
    }

    public static void renderStartup(GuiGraphics graphics, float progress, float opacity, boolean redrawMenu) {
        ShanhaiLoadingProfiler.observeStartup();
        if (!ShanhaiLoadingPreferences.startup() || renderFailed || opacity <= 0) return;
        long now = Util.getMillis();
        if (startupStarted < 0) startupStarted = now;
        var tasks = new ArrayList<ShanhaiLoadingState.Task>();
        for (var meter : StartupNotificationManager.getCurrentProgress()) {
            if (meter.name().equals("Minecraft Progress")) continue;
            tasks.add(new ShanhaiLoadingState.Task(meter.name(), meter.label() == null ? "" : meter.label().getText(),
                    Math.max(0, meter.current()), meter.steps(), false));
        }
        // 資源 reload 的實際進度，並非整體啟動百分比。
        tasks.add(new ShanhaiLoadingState.Task("資源重載", "Minecraft resources / textures / models",
                Math.max(0, Math.min(100, (int) (progress * 100))), 100, false));
        var messages = StartupNotificationManager.getMessages();
        if (!messages.isEmpty()) {
            tasks.add(0, new ShanhaiLoadingState.Task("初始化細節", messages.get(0).message().getText(), 0, -1, false));
        }
        render(graphics, true, new ShanhaiLoadingState.Frame(true, now - startupStarted, opacity, tasks), opacity, redrawMenu);
    }

    public static void beginWorld() {
        ShanhaiLoadingProfiler.beginWorld();
        startSession();
        WORLD.update("data", "資料包與登錄表", "正在準備世界 ...", 0, -1);
    }

    public static void loadingWorldData() {
        // 僅接續已由玩家選擇的世界；建立世界設定頁的預讀不啟動第二段動畫。
        if (WORLD.active()) WORLD.update("data", "資料包與登錄表", "正在準備世界 ...", 0, -1);
    }

    private static void startSession() {
        WORLD.begin(Util.getMillis());
        sawTerrain = false;
        lastWaitingFrame = 0;
        ShanhaiLoadingRenderer.resetPage();
    }

    public static void beginPreparedWorld(int recipes) {
        ShanhaiLoadingProfiler.beginWorld();
        startSession();
        WORLD.complete(WORLD.session(), "data", "資料包與登錄表", "沿用已準備的世界資料", -1);
        WORLD.complete(WORLD.session(), "recipes", "世界配方", "已準備的配方", recipes);
    }

    public static void dataReady() {
        WORLD.complete(WORLD.session(), "data", "資料包與登錄表", "資料包讀取完成", -1);
    }

    public static void connectionStatus(ConnectScreen owner, Component status) {
        if (WORLD.active() && Minecraft.getInstance().screen == owner) {
            WORLD.update("connect", "伺服器連線", status.getString(), 0, -1);
        }
    }

    public static void beginRecipes(int total) {
        if (!WORLD.active()) return;
        RecipeWork work = new RecipeWork(WORLD.session(), total);
        RECIPE_WORK.set(work);
        WORLD.update(work.session, "recipes", "解析配方", "讀取配方檔案 ...", 0, total);
    }

    public static void parsedRecipe(ResourceLocation id) {
        RecipeWork work = RECIPE_WORK.get();
        if (work == null) return;
        work.current++;
        if (work.current == 1 || work.current % 128 == 0) {
            WORLD.update(work.session, "recipes", "解析配方", id.toString(), work.current, work.total);
            // 某些整合包會在主執行緒連續解析配方，這時 managedBlock 本身不會再進入等待。
            renderWhileWaiting(Minecraft.getInstance());
        }
    }

    public static void endRecipes() {
        RecipeWork work = RECIPE_WORK.get();
        RECIPE_WORK.remove();
        if (work != null) WORLD.complete(work.session, "recipes", "配方解析結束",
                "候選檔案 " + work.total + "；實際解析 " + work.current + "（略過與錯誤詳見遊戲日誌）", work.current);
    }

    public static void syncRecipes(ClientPacketListener listener, int count, boolean done) {
        Minecraft mc = Minecraft.getInstance();
        if (!WORLD.active() || !mc.isSameThread() || listener != mc.getConnection()) return;
        if (done) WORLD.complete(WORLD.session(), "sync", "同步配方", "伺服器配方同步完成", count);
        else WORLD.update("sync", "同步配方", "正在接收伺服器配方 ...", 0, count);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpening(ScreenEvent.Opening event) {
        if (event.isCanceled()) return;
        Screen next = event.getNewScreen();
        if (next instanceof ConnectScreen) {
            if (!WORLD.active()) { ShanhaiLoadingProfiler.beginWorld(); startSession(); }
            WORLD.update("connect", "伺服器連線", "等待伺服器回應 ...", 0, -1);
        } else if (next instanceof LevelLoadingScreen || next instanceof ReceivingLevelScreen) {
            if (WORLD.active()) sawTerrain = true;
            if (WORLD.active() && next instanceof ReceivingLevelScreen) {
                WORLD.complete(WORLD.session(), "connect", "伺服器連線", "連線已建立", -1);
            }
        } else if (next != null && !isLoadingScreen(next)) {
            // 錯誤畫面、取消返回、重新選服：立即放行原畫面，清除舊會話。
            WORLD.reset();
            ShanhaiLoadingProfiler.finishWorld("cancelled");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = event.getScreen();
        if (mc.screen != screen || !WORLD.active() || !isLoadingScreen(screen)
                || !ShanhaiLoadingPreferences.world()) return;
        updateTerrain(screen);
        render(event.getGuiGraphics(), false, WORLD.snapshot(Util.getMillis(), ShanhaiLoadingPreferences.reduceMotion()), 1, false);
        // 原畫面仍執行 render（包括結束／錯誤處理）；將取消等原生控制項畫回最上層。
        if (!renderFailed) {
            for (var child : screen.renderables) child.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!WORLD.active() || !isLoadingScreen(event.getScreen()) || !ShanhaiLoadingPreferences.world()) return;
        ShanhaiLoadingRenderer.scroll(event.getScrollDelta() < 0 ? 1 : -1);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !WORLD.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (sawTerrain && mc.level != null && mc.player != null && mc.screen == null && mc.getOverlay() == null) {
            WORLD.finish(Util.getMillis());
            ShanhaiLoadingProfiler.finishWorld("ready");
        }
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null || !ShanhaiLoadingPreferences.world()) return;
        var frame = WORLD.snapshot(Util.getMillis(), ShanhaiLoadingPreferences.reduceMotion());
        if (!frame.active() && frame.opacity() > .02f) render(event.getGuiGraphics(), false, frame, frame.opacity(), false);
    }

    /** Vanilla managedBlock 不播放 UI；僅在讀取單人資料包時限速更新畫面，不 tick 遊戲。 */
    public static void renderWhileWaiting(Object eventLoop) {
        Minecraft mc = Minecraft.getInstance();
        if (eventLoop != mc || !WORLD.active() || rendering || renderFailed || !mc.isSameThread()
                || !(mc.screen instanceof GenericDirtMessageScreen) || mc.getOverlay() != null
                || !ShanhaiLoadingPreferences.world()) return;
        long now = Util.getMillis();
        if (now - lastWaitingFrame < 50) return;
        lastWaitingFrame = now;
        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var shader = RenderSystem.getShader();
        int texture = RenderSystem.getShaderTexture(0);
        try {
            mc.getMainRenderTarget().bindWrite(true);
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            render(graphics, false, WORLD.snapshot(now, ShanhaiLoadingPreferences.reduceMotion()), 1, false);
            mc.getMainRenderTarget().unbindWrite();
            mc.getMainRenderTarget().blitToScreen(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            mc.getWindow().updateDisplay();
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.depthMask(depthMask);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }

    private static boolean isLoadingScreen(Screen screen) {
        return ShanhaiLoadingScreens.contains(screen.getClass());
    }

    private static void updateTerrain(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (screen instanceof LevelLoadingScreen && mc.getProgressListener() != null) {
            int progress = Math.max(0, Math.min(100, mc.getProgressListener().getProgress()));
            WORLD.update("terrain", "出生區塊準備", "建立世界地形 ...", progress, 100);
        } else if (screen instanceof ReceivingLevelScreen) {
            WORLD.update("terrain", "接收世界區塊", "等待伺服器傳送地形 ...", 0, -1);
        }
    }

    private static void render(GuiGraphics graphics, boolean startup, ShanhaiLoadingState.Frame frame,
                               float opacity, boolean redrawMenu) {
        if (rendering || renderFailed) return;
        rendering = true;
        try {
            ShanhaiLoadingRenderer.render(graphics, startup, frame, opacity, ShanhaiLoadingPreferences.reduceMotion(), redrawMenu);
        } catch (RuntimeException error) {
            renderFailed = true;
            LogManager.getLogger("gt_shanhai:loading").error("山海載入動畫繪製失敗，本次啟動回退原生載入畫面", error);
        } finally { rendering = false; }
    }

    private static final class RecipeWork {
        private final long session;
        private final int total;
        private int current;
        private RecipeWork(long session, int total) { this.session = session; this.total = total; }
    }
}
