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
import java.util.Map;
import com.google.gson.JsonElement;
import net.minecraft.world.item.crafting.Recipe;
import static com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingText.text;

/** 山海：資料適配與客戶端生命週期。所有 GL 工作限定在渲染執行緒。 */
@Mod.EventBusSubscriber(modid = "gt_shanhai", value = Dist.CLIENT)
public final class ShanhaiLoadingClient {
    private static final ShanhaiLoadingState WORLD = new ShanhaiLoadingState();
    private static final ThreadLocal<RecipeWork> RECIPE_WORK = new ThreadLocal<>();
    private static long startupStarted = -1;
    private static long lastWaitingFrame;
    private static boolean rendering;
    private static boolean pumping;
    private static boolean renderFailed;
    private static boolean sawTerrain;

    private ShanhaiLoadingClient() {}

    @SubscribeEvent
    public static void onTitleInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen title) {
            event.addListener(Button.builder(Component.translatable("gui.gt_shanhai.loading.report.open"), button ->
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
        tasks.add(new ShanhaiLoadingState.Task(text("resources"), text("resources.detail"),
                Math.max(0, Math.min(100, (int) (progress * 100))), 100, false));
        var messages = StartupNotificationManager.getMessages();
        if (!messages.isEmpty()) {
            tasks.add(0, new ShanhaiLoadingState.Task(text("startup.detail"), messages.get(0).message().getText(), 0, -1, false));
        }
        render(graphics, true, new ShanhaiLoadingState.Frame(true, now - startupStarted, opacity, tasks), opacity, redrawMenu);
    }

    public static void beginWorld() {
        ShanhaiLoadingProfiler.beginWorld();
        startSession();
        WORLD.update("data", text("data"), text("data.preparing"), 0, -1);
    }

    public static void loadingWorldData() {
        // 僅接續已由玩家選擇的世界；建立世界設定頁的預讀不啟動第二段動畫。
        if (WORLD.active()) WORLD.update("data", text("data"), text("data.preparing"), 0, -1);
    }

    public static boolean asyncWorldData() { return WORLD.active() && ShanhaiLoadingPreferences.asyncWorldData(); }

    /** JEI Optimizer 保留 PARALLEL_FULL；等待背景索引時持續服務視窗與主執行緒回呼。 */
    public static Object awaitJei(java.util.concurrent.ForkJoinTask<?> task)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || !WORLD.active() || mc.screen == null || !isLoadingScreen(mc.screen)) return task.get();
        return ShanhaiLoadingAsync.await(task, () -> {
            checkpoint(text("jei.background"));
            mc.pollTask();
        });
    }

    /** JEI 等必須留在主執行緒的工作，在可中斷邊界限速補幀，不執行遊戲 tick。 */
    public static void checkpoint(String detail) {
        Minecraft mc = Minecraft.getInstance();
        if (!WORLD.active() || !mc.isSameThread() || mc.screen == null || !isLoadingScreen(mc.screen)) return;
        long now = Util.getMillis();
        if (now - lastWaitingFrame < 50) return;
        WORLD.update("client", text("client"), detail, 0, -1);
        renderWhileWaiting(mc);
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
        WORLD.complete(WORLD.session(), "data", text("data"), text("data.reused"), -1);
        WORLD.complete(WORLD.session(), "recipes", text("recipes.world"), text("recipes.prepared"), recipes);
    }

    public static void dataReady() {
        WORLD.complete(WORLD.session(), "data", text("data"), text("data.ready"), -1);
    }

    public static void connectionStatus(ConnectScreen owner, Component status) {
        if (WORLD.active() && Minecraft.getInstance().screen == owner) {
            WORLD.update("connect", text("connect"), status.getString(), 0, -1);
        }
    }

    public static Map<ResourceLocation, JsonElement> trackRecipes(Map<ResourceLocation, JsonElement> recipes) {
        if (!WORLD.active()) return recipes;
        RecipeWork work = new RecipeWork(WORLD.session(), recipes.size(), "recipes",
                "gui.gt_shanhai.loading.recipes", "gui.gt_shanhai.loading.recipes.reading");
        RECIPE_WORK.set(work);
        work.begin();
        return ShanhaiLoadingProgress.trackMap(recipes, entry -> work.processed(entry.getKey()));
    }

    public static void recipesReady() {
        RecipeWork work = RECIPE_WORK.get();
        if (work != null) WORLD.complete(work.session, "recipes", work.label,
                Component.translatable("gui.gt_shanhai.loading.recipes.ready", work.total).getString(), work.total);
    }

    public static void endRecipes() {
        RECIPE_WORK.remove();
    }

    public static Iterable<Recipe<?>> trackSyncedRecipes(ClientPacketListener listener, Iterable<Recipe<?>> recipes, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (!WORLD.active() || !mc.isSameThread() || listener != mc.getConnection()) return recipes;
        RecipeWork work = new RecipeWork(WORLD.session(), count, "sync",
                "gui.gt_shanhai.loading.sync", "gui.gt_shanhai.loading.sync.applying");
        work.begin();
        return ShanhaiLoadingProgress.track(recipes, recipe -> work.processed(recipe.getId()));
    }

    public static void syncRecipes(ClientPacketListener listener, int count, boolean done) {
        Minecraft mc = Minecraft.getInstance();
        if (!WORLD.active() || !mc.isSameThread() || listener != mc.getConnection()) return;
        if (done) WORLD.complete(WORLD.session(), "sync", Component.translatable("gui.gt_shanhai.loading.sync").getString(),
                Component.translatable("gui.gt_shanhai.loading.sync.ready").getString(), count);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpening(ScreenEvent.Opening event) {
        if (event.isCanceled()) return;
        Screen next = event.getNewScreen();
        if (next instanceof ConnectScreen) {
            if (!WORLD.active()) { ShanhaiLoadingProfiler.beginWorld(); startSession(); }
            WORLD.update("connect", text("connect"), text("connect.waiting"), 0, -1);
        } else if (next instanceof LevelLoadingScreen || next instanceof ReceivingLevelScreen) {
            if (WORLD.active()) sawTerrain = true;
            if (WORLD.active() && next instanceof ReceivingLevelScreen) {
                WORLD.completeExisting(WORLD.session(), "terrain");
                WORLD.complete(WORLD.session(), "connect", text("connect"), text("connect.ready"), -1);
            }
        } else if (next != null && !isLoadingScreen(next)) {
            // 錯誤畫面、取消返回、重新選服：立即放行原畫面，清除舊會話。
            WORLD.reset();
            ShanhaiLoadingRenderer.releaseBackdrop();
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
        else if (!frame.active()) ShanhaiLoadingRenderer.releaseBackdrop();
    }

    /** Vanilla managedBlock 不播放 UI；僅在讀取單人資料包時限速更新畫面，不 tick 遊戲。 */
    public static void renderWhileWaiting(Object eventLoop) {
        Minecraft mc = Minecraft.getInstance();
        if (eventLoop != mc || !mc.isSameThread() || !WORLD.active() || rendering || pumping || renderFailed
                || mc.screen == null || !isLoadingScreen(mc.screen) || mc.getOverlay() != null
                || !ShanhaiLoadingPreferences.world()) return;
        long now = Util.getMillis();
        if (now - lastWaitingFrame < 50) return;
        lastWaitingFrame = now;
        pumping = true;
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
            updateTerrain(mc.screen);
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
            pumping = false;
        }
    }

    private static boolean isLoadingScreen(Screen screen) {
        return ShanhaiLoadingScreens.contains(screen.getClass());
    }

    private static void updateTerrain(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (screen instanceof LevelLoadingScreen && mc.getProgressListener() != null) {
            int progress = Math.max(0, Math.min(100, mc.getProgressListener().getProgress()));
            WORLD.update("terrain", Component.translatable("gui.gt_shanhai.loading.terrain").getString(),
                    Component.translatable("gui.gt_shanhai.loading.terrain.preparing").getString(), progress, 100);
        } else if (screen instanceof ReceivingLevelScreen) {
            WORLD.update("chunks", Component.translatable("gui.gt_shanhai.loading.chunks").getString(),
                    Component.translatable("gui.gt_shanhai.loading.chunks.waiting").getString(), 0, -1);
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
        private final String key, label, initialDetail;
        private int current;
        private long lastPublished;
        private RecipeWork(long session, int total, String key, String labelKey, String detailKey) {
            this.session = session; this.total = total; this.key = key;
            this.label = Component.translatable(labelKey).getString();
            this.initialDetail = Component.translatable(detailKey).getString();
        }
        private void begin() { WORLD.update(session, key, label, initialDetail, 0, total); }
        private synchronized void processed(ResourceLocation id) {
            current++;
            if (current != 1 && current % 128 != 0 && current != total) return;
            long now = Util.getMillis();
            if (current != total && now - lastPublished < 50) return;
            lastPublished = now;
            WORLD.update(session, key, label, id == null ? initialDetail : id.toString(), current, total);
            renderWhileWaiting(Minecraft.getInstance());
        }
    }
}
