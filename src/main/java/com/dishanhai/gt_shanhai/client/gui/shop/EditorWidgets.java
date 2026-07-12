package com.dishanhai.gt_shanhai.client.gui.shop;

import dev.architectury.fluid.FluidStack;
import dev.ftb.mods.ftblibrary.config.FluidConfig;
import dev.ftb.mods.ftblibrary.config.ItemStackConfig;
import dev.ftb.mods.ftblibrary.config.ui.SelectFluidScreen;
import dev.ftb.mods.ftblibrary.config.ui.SelectItemStackScreen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Consumer;

/**
 * FTBQ 式编辑器共享控件（山海署名，客户端）：物品/流体槽位绘制 + 调用 FTBLib 选择器。
 *
 * <p>选择器（{@link SelectItemStackScreen}/{@link SelectFluidScreen}）自带数量/mB 输入框，
 * 选中即把结果（含数量）写回临时 config，回调读回。FTBLib 屏关闭后自动返回打开它时的当前屏
 * （即调用方的 {@link com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen}），
 * 触发其 init() 重新按草稿布局。</p>
 */
final class EditorWidgets {

    private EditorWidgets() {}

    static final int SLOT_HOVER = -1;
    static final int SLOT_BORDER = -7697782;
    static final int FLUID_TINT = 0x66339ACC;

    /** 20×20 棋盘物品槽（仿 KE renderItemCheckerSlot）。 */
    static void checkerSlot(GuiGraphics g, int x, int y, boolean hover) {
        g.fill(x - 1, y - 1, x + 21, y + 21, hover ? SLOT_HOVER : SLOT_BORDER);
        g.fill(x, y, x + 20, y + 20, -1644826);
        for (int yy = 0; yy < 20; yy += 5) {
            for (int xx = 0; xx < 20; xx += 5) {
                int color = (((xx / 5) + (yy / 5)) & 1) == 0 ? -723724 : -2302756;
                g.fill(x + xx, y + yy, Math.min(x + xx + 5, x + 20), Math.min(y + yy + 5, y + 20), color);
            }
        }
        g.fill(x, y, x + 20, y + 1, -1);
        g.fill(x, y + 19, x + 20, y + 20, -6645094);
        g.fill(x, y, x + 1, y + 20, -1);
        g.fill(x + 19, y, x + 20, y + 20, -6645094);
    }

    /** 物品槽：棋盘底 + 物品图标 + 数量角标。 */
    static void itemSlot(GuiGraphics g, Font font, int x, int y, ItemStack stack, boolean hover) {
        checkerSlot(g, x, y, hover);
        if (stack != null && !stack.isEmpty()) {
            g.renderItem(stack, x + 2, y + 2);
            g.renderItemDecorations(font, stack, x + 2, y + 2); // 角标数量
        }
    }

    /** 流体槽：棋盘底 + 青色覆层 + 名字首字，数量另在槽下标注（Forge 下 getAmount() 即 mB）。 */
    static void fluidSlot(GuiGraphics g, Font font, int x, int y, FluidStack fs, boolean hover) {
        checkerSlot(g, x, y, hover);
        if (fs != null && !fs.isEmpty()) {
            g.fill(x + 2, y + 2, x + 18, y + 18, FLUID_TINT);
            String tag = fluidShort(fs);
            g.drawString(font, tag, x + 2, y + 7, -1, true);
        }
    }

    /** 流体名简写（前 2~3 字符），无则用 path。 */
    static String fluidShort(FluidStack fs) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fs.getFluid());
        String p = id == null ? "?" : id.getPath();
        return p.length() <= 3 ? p : p.substring(0, 3);
    }

    /** 空槽提示「+」。 */
    static void plusSlot(GuiGraphics g, Font font, int x, int y, boolean hover) {
        checkerSlot(g, x, y, hover);
        g.drawCenteredString(font, "§a+", x + 10, y + 6, -1);
    }

    /**
     * 单帧尺寸（frameW/frameH，供 letterbox 用）+ 原图完整尺寸（imgW/imgH，供 blit UV 归一化用）+
     * 动画播放表（frameOrder/frameTicks，按 .mcmeta 的 animation.frames/frametime 展开；无动画则为空数组）。
     */
    private static final class TexInfo {
        final int frameW, frameH, imgW, imgH;
        final int[] frameOrder;   // 播放顺序对应的帧索引（竖排第几帧），空=静态贴图
        final int[] frameTicks;   // 与 frameOrder 对齐，每步持续的 tick 数
        final int totalTicks;     // frameTicks 之和，用于取模定位播放位置
        final boolean interpolate; // .mcmeta animation.interpolate：相邻帧间做透明度过渡，原版才有的顺滑感
        TexInfo(int frameW, int frameH, int imgW, int imgH, int[] frameOrder, int[] frameTicks, boolean interpolate) {
            this.frameW = frameW; this.frameH = frameH; this.imgW = imgW; this.imgH = imgH;
            this.frameOrder = frameOrder; this.frameTicks = frameTicks; this.interpolate = interpolate;
            int sum = 0;
            for (int t : frameTicks) sum += t;
            this.totalTicks = sum;
        }
    }

    private static final int[] EMPTY_INTS = new int[0];
    private static final java.util.Map<ResourceLocation, TexInfo> TEXTURE_INFO_CACHE = new java.util.HashMap<>();

    /**
     * 任意贴图缩略图（非物品图集 sprite，直接读原始 PNG）：懒读一次真实宽高+动画元数据并缓存，
     * 按比例内切（letterbox）绘入 size×size 方框，避免非方形贴图（如实体贴图）被拉伸变形；
     * 有 .mcmeta 动画描述的帧序列贴图（整卷竖排多帧，如熔岩/自定义动画材质）按真实帧序+frametime
     * 播放动画（同 FTBLib 那套「读完整动态材质」的效果），不再只画死第一帧；声明了
     * {@code interpolate:true} 的额外叠一层下一帧做透明度过渡，还原原版那种顺滑感（否则就是
     * 按 tick 硬切，切太快会显得「跳」）。失败（贴图不存在等）静默留空。
     */
    static void textureThumb(GuiGraphics g, int x, int y, int size, ResourceLocation tex) {
        if (tex == null) return;
        TexInfo info = TEXTURE_INFO_CACHE.computeIfAbsent(tex, EditorWidgets::readTextureInfo);
        if (info.frameW <= 0 || info.frameH <= 0) return;
        float scale = Math.min(size / (float) info.frameW, size / (float) info.frameH);
        int dw = Math.max(1, Math.round(info.frameW * scale));
        int dh = Math.max(1, Math.round(info.frameH * scale));
        int dx = x + (size - dw) / 2, dy = y + (size - dh) / 2;
        if (info.totalTicks <= 0) {
            g.blit(tex, dx, dy, dw, dh, 0f, 0f, info.frameW, info.frameH, info.imgW, info.imgH);
            return;
        }
        // 带小数的当前 tick 位置（非整数，供 interpolate 算帧内过渡进度，逐渲染帧刷新以求顺滑）
        float posTicks = (System.currentTimeMillis() % (info.totalTicks * 50L)) / 50f;
        int curIdx = info.frameOrder[info.frameOrder.length - 1];
        int nextIdx = curIdx;
        float blend = 0f;
        float acc = 0f;
        for (int i = 0; i < info.frameOrder.length; i++) {
            float stepEnd = acc + info.frameTicks[i];
            if (posTicks < stepEnd) {
                curIdx = info.frameOrder[i];
                nextIdx = info.frameOrder[(i + 1) % info.frameOrder.length];
                blend = info.interpolate ? (posTicks - acc) / info.frameTicks[i] : 0f;
                break;
            }
            acc = stepEnd;
        }
        g.blit(tex, dx, dy, dw, dh, 0f, curIdx * (float) info.frameH, info.frameW, info.frameH, info.imgW, info.imgH);
        if (blend > 0.001f) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, blend);
            g.blit(tex, dx, dy, dw, dh, 0f, nextIdx * (float) info.frameH, info.frameW, info.frameH, info.imgW, info.imgH);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }

    private static TexInfo readTextureInfo(ResourceLocation tex) {
        var mgr = net.minecraft.client.Minecraft.getInstance().getResourceManager();
        var opt = mgr.getResource(tex);
        if (opt.isEmpty()) return new TexInfo(0, 0, 0, 0, EMPTY_INTS, EMPTY_INTS, false);
        int imgW, imgH;
        try (java.io.InputStream is = opt.get().open();
             com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(is)) {
            imgW = img.getWidth();
            imgH = img.getHeight();
        } catch (Exception e) {
            return new TexInfo(0, 0, 0, 0, EMPTY_INTS, EMPTY_INTS, false);
        }
        // 默认：非动画贴图，一帧 = 整图
        int frameW = imgW, frameH = imgH;
        int[] frameOrder = EMPTY_INTS, frameTicks = EMPTY_INTS;
        boolean interpolate = false;
        var metaOpt = mgr.getResource(new ResourceLocation(tex.getNamespace(), tex.getPath() + ".mcmeta"));
        if (metaOpt.isPresent()) {
            try (java.io.InputStream ms = metaOpt.get().open()) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(ms, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("animation")) {
                    com.google.gson.JsonObject anim = root.getAsJsonObject("animation");
                    // vanilla 规则：width 缺省 = 整图宽；height 缺省 = width（方形帧，竖排多帧）
                    int w = anim.has("width") ? anim.get("width").getAsInt() : imgW;
                    int h = anim.has("height") ? anim.get("height").getAsInt() : w;
                    frameW = Math.max(1, w);
                    frameH = Math.max(1, h);
                    interpolate = anim.has("interpolate") && anim.get("interpolate").getAsBoolean();
                    int defaultTime = anim.has("frametime") ? Math.max(1, anim.get("frametime").getAsInt()) : 1;
                    if (anim.has("frames") && anim.get("frames").isJsonArray()) {
                        // 自定义帧序（可打乱顺序/重复/单帧覆写 time）
                        com.google.gson.JsonArray arr = anim.getAsJsonArray("frames");
                        int[] order = new int[arr.size()];
                        int[] ticks = new int[arr.size()];
                        for (int i = 0; i < arr.size(); i++) {
                            com.google.gson.JsonElement el = arr.get(i);
                            if (el.isJsonObject()) {
                                com.google.gson.JsonObject fo = el.getAsJsonObject();
                                order[i] = fo.has("index") ? fo.get("index").getAsInt() : 0;
                                ticks[i] = fo.has("time") ? Math.max(1, fo.get("time").getAsInt()) : defaultTime;
                            } else {
                                order[i] = el.getAsInt();
                                ticks[i] = defaultTime;
                            }
                        }
                        frameOrder = order;
                        frameTicks = ticks;
                    } else {
                        // 未自定义帧序：按竖排行数顺序播放，每帧 defaultTime tick
                        int frameCount = Math.max(1, imgH / frameH);
                        frameOrder = new int[frameCount];
                        frameTicks = new int[frameCount];
                        for (int i = 0; i < frameCount; i++) {
                            frameOrder[i] = i;
                            frameTicks[i] = defaultTime;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return new TexInfo(frameW, frameH, imgW, imgH, frameOrder, frameTicks, interpolate);
    }

    /** 渲染流体真实静态贴图（Forge 图集 sprite + tint），失败静默。供多选选择器网格/清单用。 */
    static void fluidIcon(GuiGraphics g, int x, int y, int size, net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == null) return;
        try {
            var ext = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            ResourceLocation still = ext.getStillTexture();
            if (still == null) return;
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = net.minecraft.client.Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(still);
            int tint = ext.getTintColor();
            float r = ((tint >> 16) & 0xFF) / 255f, gg = ((tint >> 8) & 0xFF) / 255f, b = (tint & 0xFF) / 255f;
            float a = ((tint >>> 24) & 0xFF) / 255f;
            if (a <= 0f) a = 1f; // 无 alpha 的 tint 视为不透明
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, gg, b, a);
            g.blit(x, y, 0, size, size, sprite);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        } catch (Exception ignored) {}
    }

    /**
     * 打开 FTBLib 物品选择器（含数量）。current 预填，选中/取消后回到调用时的当前屏。
     * onPicked 收到最终 stack（取消则为 current，空表示清除）。
     *
     * <p><b>关键</b>：FTBLib 选择器的确认/取消按钮只调 {@code doAccept/doCancel → callback.save}，
     * <b>不自行关闭界面</b>——关闭（返回上一屏）是回调的职责。故此处在回调里显式
     * {@code setScreen(returnTo)}，否则点确认/取消不退出（只能 ESC）。</p>
     */
    static void openItemPicker(ItemStack current, Consumer<ItemStack> onPicked) {
        net.minecraft.client.gui.screens.Screen returnTo = net.minecraft.client.Minecraft.getInstance().screen;
        ItemStackConfig cfg = new ItemStackConfig(true, true); // 非固定数量 → 显示数量框；允许空
        cfg.setValue(current == null ? ItemStack.EMPTY : current.copy());
        new SelectItemStackScreen(cfg, changed -> {
            onPicked.accept(cfg.getValue());
            net.minecraft.client.Minecraft.getInstance().setScreen(returnTo);
        }).openGui();
    }

    /** 打开 FTBLib 流体选择器（含 mB）。语义同 {@link #openItemPicker}（回调负责返回上一屏）。 */
    static void openFluidPicker(FluidStack current, Consumer<FluidStack> onPicked) {
        net.minecraft.client.gui.screens.Screen returnTo = net.minecraft.client.Minecraft.getInstance().screen;
        FluidConfig cfg = new FluidConfig(true).showAmount(true);
        cfg.setValue(current == null ? FluidStack.empty() : current.copy());
        new SelectFluidScreen(cfg, changed -> {
            onPicked.accept(cfg.getValue());
            net.minecraft.client.Minecraft.getInstance().setScreen(returnTo);
        }).openGui();
    }
}
