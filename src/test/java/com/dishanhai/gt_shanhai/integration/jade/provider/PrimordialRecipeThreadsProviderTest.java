package com.dishanhai.gt_shanhai.integration.jade.provider;

// Author: dishanhai
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.api.ui.IProgressStyle;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrimordialRecipeThreadsProviderTest {
    @Test void preservesMachineTooltipAndTagsThreadRowsForTheSidePanel() {
        CompoundTag data = new CompoundTag();
        CompoundTag view = new CompoundTag();
        view.putInt("active", 2);
        view.putLong("capacity", 17);
        view.putString("consumption", "123");
        view.putString("production", "45");
        view.putBoolean("enabled", true);
        ListTag rows = new ListTag();
        rows.add(row(0, 30, 100, "WORKING"));
        rows.getCompound(0).putInt("moreOutputs", 2);
        ListTag recipes = new ListTag();
        CompoundTag source = new CompoundTag();
        source.putString("recipeType", "gtceu:photon_matrix_etch");
        source.putString("recipeId", "gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6");
        recipes.add(source);
        rows.getCompound(0).put("recipes", recipes);
        rows.add(row(1, 2, 10, "WAITING"));
        view.put("threads", rows);
        data.put("sh_primordial_threads_display", view);
        List<ResourceLocation> removed = new ArrayList<>();
        List<ResourceLocation> tagged = new ArrayList<>();
        List<Component> text = new ArrayList<>();
        List<Float> progress = new ArrayList<>();
        List<String> sequence = new ArrayList<>();
        List<String> colorKeys = new ArrayList<>();
        IElement[] elementRef = new IElement[1];
        ResourceLocation[] elementTag = new ResourceLocation[1];
        IElement element = proxy(IElement.class, (method, args) -> {
            if (method.equals("tag")) { elementTag[0] = (ResourceLocation) args[0]; return elementRef[0]; }
            if (method.equals("getTag")) return elementTag[0];
            return null;
        });
        elementRef[0] = element;
        IProgressStyle style = proxy(IProgressStyle.class, (method, args) -> null);
        IProgressStyle fluentStyle = proxy(IProgressStyle.class, (method, args) -> style);
        IElementHelper helper = proxy(IElementHelper.class, (method, args) -> {
            if (method.equals("progressStyle")) return fluentStyle;
            if (method.equals("progress")) {
                progress.add((Float) args[0]); text.add((Component) args[1]);
                sequence.add("progress");
            }
            return element;
        });
        ITooltip tooltip = proxy(ITooltip.class, (method, args) -> {
            if (method.equals("remove")) removed.add((ResourceLocation) args[0]);
            if (method.equals("add") && args.length == 1 && args[0] instanceof PrimordialRecipeThreadText label) {
                Component detail = label.text();
                if (detail.getContents() instanceof TranslatableContents contents
                        && contents.getKey().startsWith("gt_shanhai.primordial.threads.thread_color.")) {
                    colorKeys.add(contents.getKey());
                    detail = (Component) contents.getArgs()[0];
                }
                if (!detail.getString().isEmpty()) text.add(detail);
                sequence.add(detail.getContents() instanceof TranslatableContents contents ? contents.getKey() : "separator");
                tagged.add(label.getTag());
            }
            if (method.equals("add") && args.length == 2 && args[1] instanceof ResourceLocation uid) tagged.add(uid);
            if (method.equals("append") && args.length == 2 && args[1] instanceof ResourceLocation uid) tagged.add(uid);
            if (method.equals("add") && args[0] instanceof Component component) text.add(component);
            return method.equals("getElementHelper") ? helper : null;
        });
        BlockAccessor accessor = proxy(BlockAccessor.class, (method, args) -> data);
        PrimordialRecipeThreadsProvider.INSTANCE.appendTooltip(tooltip, accessor, null);
        assertTrue(removed.isEmpty(), "原生機器資訊與配方資訊保留在主提示框");
        assertTrue(tagged.contains(PrimordialRecipeThreadsProvider.INSTANCE.getThreadElementUid()));
        assertEquals(List.of(0.3F, 0.2F), progress);
        assertTrue(text.stream().allMatch(component -> component.getContents() instanceof TranslatableContents));
        assertTrue(sequence.indexOf("progress") < sequence.indexOf("gt_shanhai.primordial.threads.more_outputs"));
        assertTrue(sequence.indexOf("gt_shanhai.primordial.threads.more_outputs")
                < sequence.indexOf("gt_shanhai.primordial.threads.timing"));
        assertEquals(1, sequence.stream().filter("separator"::equals).count());
        assertTrue(colorKeys.contains("gt_shanhai.primordial.threads.thread_color.0"));
        assertTrue(colorKeys.contains("gt_shanhai.primordial.threads.thread_color.1"));
        var idLine = text.stream().filter(component -> ((TranslatableContents) component.getContents()).getKey()
                .equals("gt_shanhai.primordial.threads.recipe_id")).findFirst().orElseThrow();
        assertEquals("gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6",
                ((TranslatableContents) idLine.getContents()).getArgs()[0]);
    }

    @Test void sidePanelExtractionLeavesMachineTooltipElementsUntouched() {
        ResourceLocation threadUid = PrimordialRecipeThreadsProvider.INSTANCE.getThreadElementUid();
        ResourceLocation machineUid = new ResourceLocation("gtceu", "workable_provider");
        IElement threadLine = proxy(IElement.class, (method, args) -> null);
        List<ResourceLocation> removed = new ArrayList<>();
        ITooltip tooltip = proxy(ITooltip.class, (method, args) -> {
            if (method.equals("get")) {
                assertEquals(threadUid, args[0]);
                return List.of(threadLine);
            }
            if (method.equals("remove")) removed.add((ResourceLocation) args[0]);
            return null;
        });

        assertEquals(List.of(threadLine), PrimordialRecipeThreadsOverlay.extractThreadElements(tooltip));
        assertEquals(List.of(threadUid), removed);
        assertFalse(removed.contains(machineUid), "原生機器詳情 UID 保留於主框");
    }

    @Test void sidePanelStaysLeftOfMainTooltipAndWithinScreenBounds() {
        var position = PrimordialRecipeThreadsOverlay.leftPanelPosition(230, 90, 180, 140, 1, 1280, 720);
        assertEquals(46, position.x);
        assertEquals(90, position.y);
        float scale = PrimordialRecipeThreadsOverlay.fitScale(1, 180, 80, 80, 720);
        var clamped = PrimordialRecipeThreadsOverlay.leftPanelPosition(80, 690, 180, 80, scale, 1280, 720);
        assertEquals(2, clamped.x);
        assertTrue(clamped.x + 180 * scale <= 76);
        assertTrue(clamped.y + 80 * scale <= 718);
        float larger = PrimordialRecipeThreadsOverlay.fitScale(2, 180, 140, 500, 720);
        assertEquals(2, larger);
        assertEquals(136, PrimordialRecipeThreadsOverlay.leftPanelPosition(500, 90, 180, 140,
                larger, 1280, 720).x);
        assertTrue(PrimordialRecipeThreadsOverlay.fitScale(2, 180, 1000, 500, 720) * 1000 <= 716);
    }

    @Test void collectedPanelSurvivesMultipleRenderFramesAndClearsWhenTargetChanges() {
        CompoundTag data = new CompoundTag();
        data.put(PrimordialRecipeThreadsProvider.getDataKey(), new CompoundTag());
        BlockAccessor accessor = proxy(BlockAccessor.class, (method, args) -> switch (method) {
            case "getServerData" -> data;
            case "getPosition" -> BlockPos.ZERO;
            default -> null;
        });
        IElement threadLine = proxy(IElement.class, (method, args) -> null);
        ITooltip tooltip = proxy(ITooltip.class, (method, args) -> method.equals("get") ? List.of(threadLine) : null);
        var overlay = PrimordialRecipeThreadsOverlay.INSTANCE;
        overlay.onTooltipCollected(tooltip, accessor);
        assertEquals(List.of(threadLine), overlay.threadElementsFor(tooltip, accessor));
        assertEquals(List.of(threadLine), overlay.threadElementsFor(tooltip, accessor));
        ITooltip other = proxy(ITooltip.class, (method, args) -> null);
        assertTrue(overlay.threadElementsFor(other, accessor).isEmpty());
        data.remove(PrimordialRecipeThreadsProvider.getDataKey());
        overlay.onTooltipCollected(other, accessor);
        assertTrue(overlay.threadElementsFor(tooltip, accessor).isEmpty());
    }

    @Test void unrelatedMachinesKeepTheirExistingTooltip() {
        BlockAccessor accessor = proxy(BlockAccessor.class, (method, args) -> new CompoundTag());
        ITooltip tooltip = proxy(ITooltip.class, (method, args) -> {
            fail("不應修改其他機器的 tooltip");
            return null;
        });
        PrimordialRecipeThreadsProvider.INSTANCE.appendTooltip(tooltip, accessor, null);
    }

    @Test void everyThreadTranslationExistsInBothLanguageFiles() throws Exception {
        var base = Path.of("src/main/resources/assets/gt_shanhai/lang");
        var chinese = JsonParser.parseString(Files.readString(base.resolve("zh_cn.json"))).getAsJsonObject();
        var english = JsonParser.parseString(Files.readString(base.resolve("en_us.json"))).getAsJsonObject();
        var prefix = "gt_shanhai.primordial.threads.";
        assertTrue(chinese.has(prefix + "summary"));
        for (var entry : chinese.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            assertTrue(english.has(entry.getKey()), entry.getKey());
            assertEquals(entry.getValue().getAsString().split("%s", -1).length,
                    english.get(entry.getKey()).getAsString().split("%s", -1).length, entry.getKey());
        }
    }

    private static CompoundTag row(long index, int progress, int duration, String status) {
        CompoundTag row = new CompoundTag();
        row.putLong("index", index);
        row.putInt("progress", progress);
        row.putInt("duration", duration);
        row.putString("status", status);
        return row;
    }

    private interface Call { Object invoke(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args));
    }
}
