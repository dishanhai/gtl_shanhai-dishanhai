package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.Map;
import java.util.WeakHashMap;

/** 山海：只接管已繪出且主機仍有效的 AR；命中投影即取消攻擊，保留玻璃碰撞與結構。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HostFxGlassArInput {
    private static final Map<GuixuVisionCoreMachine, Panel> PANELS = new WeakHashMap<>();
    private static final HostFxGlassArTouch.Press PRESS = new HostFxGlassArTouch.Press();

    private HostFxGlassArInput() {}

    private static final class Panel {
        final HostFxGlassArTouch.Pages info = new HostFxGlassArTouch.Pages();
        final HostFxGlassArTouch.Pages modules = new HostFxGlassArTouch.Pages();
        HostFxGlassArModel.State state;
    }

    private record Target(Panel panel, HostFxGlassArTouch.Hit hit) {}

    static void rendered(GuixuVisionCoreMachine host, HostFxGlassArModel.State state) {
        Panel panel = PANELS.computeIfAbsent(host, ignored -> new Panel());
        panel.state = state;
        state.infoPages = panel.info;
        state.modulePages = panel.modules;
        Target target = target();
        state.hoverButton = target != null && target.panel == panel
                ? HostFxGlassArTouch.buttonAt(target.hit.u(), target.hit.v()) : -1;
    }

    static void reset() {
        PANELS.clear();
        PRESS.release();
    }

    private static Target target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null
                || !mc.isWindowActive()) return null;
        // 與原版方塊拾取同源，F5 相機後移不縮短玩家可觸及距離。
        var eye = mc.player.getEyePosition(mc.getFrameTime());
        var look = mc.player.getViewVector(mc.getFrameTime());
        var end = eye.add(look);
        double reach = mc.gameMode.getPickRange();
        Target nearest = null;
        for (var entry : PANELS.entrySet()) {
            var host = entry.getKey();
            var panel = entry.getValue();
            if (host.getLevel() != mc.level || !(host.isFormed() || host.isHostFormedSync())
                    || !mc.level.hasChunkAt(host.getPos())
                    || MetaMachine.getMachine(mc.level, host.getPos()) != host) continue;
            float[] local = HostFxBlackHole.cameraLocal(host.getFrontFacing(), eye.x, eye.y, eye.z, host.getPos());
            float[] ahead = HostFxBlackHole.cameraLocal(host.getFrontFacing(), end.x, end.y, end.z, host.getPos());
            var hit = HostFxGlassArTouch.raycast(local[0], local[1], local[2],
                    ahead[0] - local[0], ahead[1] - local[1], ahead[2] - local[2], reach);
            if (hit == null || nearest != null && hit.distance() >= nearest.hit.distance()) continue;
            // 與真正方塊比較前後距離：不穿牆，且玻璃位於投影之後。
            var point = eye.add(look.scale(hit.distance()));
            var block = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, mc.player));
            if (block.getType() != HitResult.Type.MISS
                    && eye.distanceTo(block.getLocation()) + 0.001 < hit.distance()) continue;
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY
                    && eye.distanceTo(mc.hitResult.getLocation()) < hit.distance()) continue;
            nearest = new Target(panel, hit);
        }
        return nearest;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Target target = target();
        if (target == null) return;
        // 原版 startAttack 與 continueAttack 都先發此事件，取消後不會送出挖掘包。
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft.getInstance().gameMode.stopDestroyBlock();
        if (!PRESS.begin()) return;
        int button = HostFxGlassArTouch.buttonAt(target.hit.u(), target.hit.v());
        if (button < 0) return;
        int section = button / 3;
        var pages = section == 0 ? target.panel.info : target.panel.modules;
        int count = section == 0 ? 4 : HostFxGlassArTouch.modulePageCount(target.panel.state);
        long now = System.nanoTime() / 1_000_000L;
        if (button % 3 == 1) pages.toggle(now, count);
        else pages.step(button % 3 == 0 ? -1 : 1, now, count);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (event.getScrollDelta() == 0) return;
        Target target = target();
        if (target == null) return;
        event.setCanceled(true);
        int section = HostFxGlassArTouch.sectionAt(target.hit.u(), target.hit.v());
        if (section < 0) return;
        var pages = section == 0 ? target.panel.info : target.panel.modules;
        pages.step(event.getScrollDelta() > 0 ? -1 : 1, System.nanoTime() / 1_000_000L,
                section == 0 ? 4 : HostFxGlassArTouch.modulePageCount(target.panel.state));
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_RELEASE
                && Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton())) PRESS.release();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_RELEASE
                && Minecraft.getInstance().options.keyAttack.matches(event.getKey(), event.getScanCode())) PRESS.release();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) reset();
        else if (mc.screen != null || !mc.isWindowActive() || !mc.options.keyAttack.isDown()) PRESS.release();
    }
}
