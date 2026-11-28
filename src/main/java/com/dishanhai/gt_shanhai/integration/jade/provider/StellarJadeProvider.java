package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.starlifter.StarLifterHudCache;
import com.dishanhai.gt_shanhai.client.gui.starlifter.StellarHudPanel;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarHudMode;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import snownee.jade.JadeClient;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.Identifiers;
import snownee.jade.api.callback.JadeBeforeRenderCallback;
import snownee.jade.api.callback.JadeBeforeRenderCallback.ColorSetting;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.config.IWailaConfig;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.impl.ObjectDataCenter;
import snownee.jade.impl.Tooltip;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.overlay.TooltipRenderer;
import snownee.jade.overlay.WailaTickHandler;
import snownee.jade.util.ClientProxy;

/** Adapter for the Jade 11 tooltip, icon, and background callbacks. */
public final class StellarJadeProvider implements IBlockComponentProvider {
    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "stellar_jade");
    private static final ResourceLocation WORKABLE = new ResourceLocation("gtceu", "workable_provider");
    private static final ResourceLocation RECIPE = new ResourceLocation("gtceu", "recipe_logic_provider");
    private static final ResourceLocation STRUCTURE = new ResourceLocation("gtceu", "multiblock_structure");
    private static final ResourceLocation CONTROL = new ResourceLocation("gtceu", "controllable_provider");

    private final StellarHudPanel panel = new StellarHudPanel();
    private float lastScale = 1;
    private float lastOpacity;
    private int lastX, lastY, lastWidth, lastHeight;
    private PanelElement lastPanel;
    private boolean drewBackground;

    private StellarJadeProvider() {}

    public static void register(IWailaClientRegistration registration) {
        try {
            IWailaClientRegistration.class.getMethod("addBeforeRenderCallback", int.class, JadeBeforeRenderCallback.class);
            var provider = new StellarJadeProvider();
            registration.addConfig(UID, true);
            registration.addBeforeRenderCallback(100, provider::background);
            registration.addTooltipCollectedCallback(100, provider::collect);
            registration.registerBlockIcon(provider, Block.class);
            MinecraftForge.EVENT_BUS.addListener(provider::levelUnloaded);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, provider::clientTick);
            MinecraftForge.EVENT_BUS.addListener(provider::beginGui);
            MinecraftForge.EVENT_BUS.addListener(provider::finishGui);
        } catch (NoSuchMethodException | LinkageError incompatible) {
            GTDishanhaiMod.LOGGER.warn("Stellar Jade HUD requires the Jade 11 overlay callbacks", incompatible);
        }
    }

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public int getDefaultPriority() { return 100; }

    @Override
    public IElement getIcon(BlockAccessor accessor, IPluginConfig config, IElement original) {
        if (enabled(config) && target(accessor) != null) return IElementHelper.get().spacer(0, 0);
        return original;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        // Replacements run after Jade collects the contributors and tags their elements.
    }

    private void collect(ITooltip tooltip, Accessor<?> accessor) {
        var config = IWailaConfig.get();
        var plugins = config.getPlugin();
        if (!enabled(plugins) || !(accessor instanceof BlockAccessor block)) return;
        var target = target(block);
        if (target == null) return;
        var overlay = config.getOverlay();
        var detailsKey = JadeClient.showDetails.getTranslatedKeyMessage();
        // Jade 11's DistanceProvider tags both distance and position with its own UID.
        boolean nativeCoreCoordinates = target.role() == StarLifterHudTarget.CORE
                && plugins.get(Identifiers.CORE_COORDINATES)
                && !(plugins.get(Identifiers.CORE_REL_COORDINATES) && Screen.hasControlDown())
                && !tooltip.get(Identifiers.CORE_DISTANCE).isEmpty();
        int width = panel.configure(target, accessor.showDetails(), overlay.shouldShowIcon(),
                overlay.getAnimation(), detailsKey, StellarHudMode.current(), nativeCoreCoordinates);
        int height = panel.prepare(target, accessor.getPickedResult().isEmpty()
                ? block.getBlock().getName() : accessor.getPickedResult().getHoverName());
        tooltip.remove(Identifiers.CORE_OBJECT_NAME);
        tooltip.remove(Identifiers.CORE_MOD_NAME);
        if (target.role() == StarLifterHudTarget.CORE) {
            tooltip.remove(WORKABLE);
            tooltip.remove(RECIPE);
            tooltip.remove(STRUCTURE);
            tooltip.remove(CONTROL);
            tooltip.remove(Identifiers.UNIVERSAL_PROGRESS);
        }
        int nativeGap = tooltip.isEmpty() ? 0 : 4;
        int visibleHeight = fittedHeight((Tooltip) tooltip, overlay, width, height, nativeGap);
        tooltip.add(0, new PanelElement(panel, target, block, width, height, panel.bootHeight(), panel.detailHeight(),
                visibleHeight, nativeGap));
    }

    private static int fittedHeight(Tooltip tooltip, IWailaConfig.IConfigOverlay overlay, int width, int height, int gap) {
        var renderer = new TooltipRenderer(tooltip, true);
        renderer.setPaddingFromTheme(overlay.getTheme());
        var nativeSize = renderer.getSize();
        float totalWidth = Math.max(nativeSize.x, width + renderer.getPadding(1) + renderer.getPadding(3));
        renderer.setSize(new Vec2(totalWidth, nativeSize.y + height + gap));
        renderer.recalculateRealRect();
        // Jade adds up to two pixels for the themed border outside the measured content.
        return visiblePanelHeight(height, nativeSize.y + gap + 2,
                Minecraft.getInstance().getWindow().getGuiScaledHeight(), renderer.getPosition().getY(),
                overlay.getAnchorY(), renderer.getRealScale());
    }

    static int visiblePanelHeight(int height, float reserved, int viewport, int position, float anchor, float scale) {
        float above = anchor == 0 ? Float.POSITIVE_INFINITY : position / anchor;
        float below = anchor == 1 ? Float.POSITIVE_INFINITY : (viewport - position) / (1 - anchor);
        int available = (int) Math.floor(Math.min(above, below) / scale - reserved);
        return Math.clamp(available, 0, height);
    }

    private boolean background(ITooltip tooltip, Rect2i rect, GuiGraphics graphics, Accessor<?> accessor,
                               ColorSetting color) {
        if (tooltip.isEmpty()) {
            lostIfDeparted(accessor);
            return false;
        }
        var firstLine = tooltip.get(0, IElement.Align.LEFT);
        if (firstLine.isEmpty() || !(firstLine.get(0) instanceof PanelElement element)
                || !panel.hasTarget(element.target)) {
            lostIfDeparted(accessor);
            return false;
        }
        long now = ShBetterUiApi.motion().nowMs();
        boolean current = element.accessor == accessor && OverlayRenderer.shouldShow();
        if (current) panel.visible(element.target, now, OverlayRenderer.alpha);
        else if (overlayEnabled() && lookingAtStellar(accessor)) panel.handoff(now, 1);
        else panel.lost(now, 1);
        // 11.13 registers the background callback without finalizing its dispatch list. The before-render
        // hook runs before Jade applies the morphed rectangle and its actual scale.
        var renderer = WailaTickHandler.instance().tooltipRenderer;
        if (renderer != null && renderer.getTooltip() == tooltip) lastScale = renderer.getRealScale();
        float opacity = ShanhaiUiClientConfig.stellarHudBackground() * color.alpha;
        int width = Math.round(rect.getWidth() / lastScale), height = Math.round(rect.getHeight() / lastScale);
        drawBackground(graphics, rect.getX(), rect.getY(), width, height, opacity);
        drewBackground = true;
        if (current) {
            lastPanel = element;
            lastX = rect.getX();
            lastY = rect.getY();
            lastWidth = width;
            lastHeight = height;
            lastOpacity = opacity;
        }
        color.alpha = 0;
        return false;
    }

    private void drawBackground(GuiGraphics graphics, int x, int y, int width, int height, float opacity) {
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, Minecraft.getInstance().screen == null ? 1 : 100);
            pose.scale(lastScale, lastScale, 1);
            panel.background(graphics, width, height, opacity);
        } finally {
            pose.popPose();
        }
    }

    private static boolean enabled(IPluginConfig config) {
        return Minecraft.getInstance().isRunning() && StellarHudMode.current() != StellarHudMode.ORIGINAL && config.get(UID);
    }

    private void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            panel.reset();
            lastPanel = null;
        }
    }

    private void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!panel.tracking()) {
            lastPanel = null;
            return;
        }
        var client = Minecraft.getInstance();
        if (client.level == null || client.options.hideGui || client.getOverlay() != null
                || !ClientProxy.shouldShowWithOverlay(client, client.screen) || !overlayEnabled()
                || WailaTickHandler.instance().tooltipRenderer != null && !OverlayRenderer.shouldShow()
                || !lookingAtStellar(ObjectDataCenter.get())) {
            panel.idle(ShBetterUiApi.motion().nowMs());
            if (!panel.tracking()) lastPanel = null;
        }
    }

    private void beginGui(RenderGuiEvent.Pre event) { drewBackground = false; }

    private void lostIfDeparted(Accessor<?> accessor) {
        if (panel.tracking() && (!overlayEnabled() || !lookingAtStellar(accessor))) {
            panel.idle(ShBetterUiApi.motion().nowMs());
        }
    }

    private void finishGui(RenderGuiEvent.Post event) {
        var client = Minecraft.getInstance();
        var previous = lastPanel;
        // Jade can stop dispatching a departed tooltip before the terminal's close has finished.
        if (drewBackground || previous == null || !previous.rendered || client.options.hideGui || client.screen != null
                || client.getOverlay() != null || client.hitResult == null || client.hitResult.getType() != HitResult.Type.MISS
                || !overlayEnabled() || !panel.hasTarget(previous.target)) return;
        panel.idle(ShBetterUiApi.motion().nowMs());
        if (!panel.closing()) return;
        var gui = event.getGuiGraphics();
        drawBackground(gui, lastX, lastY, lastWidth, lastHeight, lastOpacity);
        var pose = gui.pose();
        pose.pushPose();
        try {
            pose.translate(lastX, lastY, 2);
            pose.scale(lastScale, lastScale, 1);
            previous.drawPanel(gui, previous.x, previous.y);
        } finally {
            pose.popPose();
        }
    }

    private static boolean overlayEnabled() {
        var config = IWailaConfig.get();
        var general = config.getGeneral();
        return enabled(config.getPlugin()) && general.getDisplayBlocks() && general.shouldDisplayTooltip()
                && (general.getDisplayMode() != IWailaConfig.DisplayMode.HOLD_KEY || JadeClient.showOverlay.isDown());
    }

    private static boolean lookingAtStellar(Accessor<?> accessor) {
        if (accessor instanceof BlockAccessor block) {
            return !block.isFakeBlock() && block.getLevel() instanceof ClientLevel
                    && StarLifterHudTarget.forClientBlock(block.getBlock(), block.getBlockEntity()) != null;
        }
        // A target-change tick can clear Jade's accessor before the replacement tooltip is collected.
        if (accessor != null) return false;
        var client = Minecraft.getInstance();
        if (client.level == null || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        var pos = hit.getBlockPos();
        return StarLifterHudTarget.forClientBlock(client.level.getBlockState(pos).getBlock(), client.level.getBlockEntity(pos)) != null;
    }

    private static StarLifterHudCache.Target target(BlockAccessor accessor) {
        if (accessor.isFakeBlock() || !(accessor.getLevel() instanceof ClientLevel level)) return null;
        var entity = accessor.getBlockEntity();
        var role = StarLifterHudTarget.forClientBlock(accessor.getBlock(), entity);
        return role == null ? null : new StarLifterHudCache.Target(level, accessor.getPosition(), accessor.getBlockState(), entity, role);
    }

    private static final class PanelElement extends Element {
        private final StellarHudPanel panel;
        private final StarLifterHudCache.Target target;
        private final BlockAccessor accessor;
        private final int width, panelHeight, bootHeight, detailHeight;
        private final float fitScale;
        private float x, y;
        private boolean rendered;

        PanelElement(StellarHudPanel panel, StarLifterHudCache.Target target, BlockAccessor accessor,
                     int width, int height, int bootHeight, int detailHeight, int visibleHeight, int gap) {
            this.panel = panel;
            this.target = target;
            this.accessor = accessor;
            this.width = width;
            panelHeight = height;
            this.bootHeight = bootHeight;
            this.detailHeight = detailHeight;
            fitScale = (float) visibleHeight / height;
            size = new Vec2(width, visibleHeight + gap);
            tag(UID);
        }

        @Override
        public Vec2 getSize() { return size; }

        @Override
        public String getMessage() { return panel.hasTarget(target) ? panel.narration() : ""; }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float right, float bottom) {
            this.x = x;
            this.y = y;
            rendered = true;
            if (panel.hasTarget(target)) drawPanel(graphics, x, y);
        }

        void drawPanel(GuiGraphics graphics, float x, float y) {
            if (fitScale == 1) {
                panel.draw(graphics, x, y, panelHeight, bootHeight, detailHeight);
            } else if (fitScale > 0) {
                var pose = graphics.pose();
                pose.pushPose();
                try {
                    pose.translate(x + width * (1 - fitScale) / 2, y, 0);
                    pose.scale(fitScale, fitScale, 1);
                    panel.draw(graphics, 0, 0, panelHeight, bootHeight, detailHeight);
                } finally {
                    pose.popPose();
                }
            }
        }
    }
}
