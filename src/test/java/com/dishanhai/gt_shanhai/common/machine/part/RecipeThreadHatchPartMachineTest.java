package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.integration.jade.provider.RecipeThreadHatchInfoProvider;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：容量包含一次基础线程；关闭配置只收回额外接单容量。 */
class RecipeThreadHatchPartMachineTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeThreadHatchPartMachine.java");
    private static final Path MACHINES = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "DShanhaiMachines.java");
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang");
    private static final Path GUIDE = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "guides", "gt_shanhai", "guide", "ae", "recipe_thread_hatch.md");
    private static final Path PARALLEL_GUIDE = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "guides", "gt_shanhai", "guide", "ae", "parallel_index.md");
    private static final Path JADE_PLUGIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "integration", "jade", "DShanhaiJadePlugin.java");

    @Test
    void threadHatchMustNotClaimNativeParallelHatchCapability() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("implements IRecipeThreadSource"));
        assertFalse(source.contains("IParallelHatch"),
                "线程仓不能注册为 GTLCore 的原生并行仓，否则会覆盖实际并行仓选择");

        String machines = Files.readString(MACHINES);
        int registration = machines.indexOf("\"recipe_thread_hatch\"");
        int nextRegistration = machines.indexOf("\n        // ========== 山海维护仓", registration);
        assertTrue(registration >= 0 && nextRegistration > registration, "线程仓机器注册块缺失");
        String registrationBlock = machines.substring(registration, nextRegistration);
        assertTrue(registrationBlock.contains(".abilities(PartAbility.PARALLEL_HATCH, PartAbility.IMPORT_ITEMS)"),
                "线程仓仍需匹配并行仓及物品输入仓结构位置");
    }

    @Test
    void enabledCapacityIncludesOneBaseThreadAndSaturates() {
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(true, 0L));
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(true, -1L));
        assertEquals(65L, RecipeThreadHatchPartMachine.capacityForBoost(true, 64L));
        assertEquals(Long.MAX_VALUE, RecipeThreadHatchPartMachine.capacityForBoost(true, Long.MAX_VALUE));
    }

    @Test
    void disablingOnlyRemovesAdditionalCapacity() {
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(false, 64L));
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(false, Long.MAX_VALUE));
    }

    @Test
    void structuredTooltipAndGuideDocumentationAreRegistered() throws IOException {
        String machines = Files.readString(MACHINES);
        int registration = machines.indexOf("\"recipe_thread_hatch\"");
        int nextRegistration = machines.indexOf("\n        // ========== 山海维护仓", registration);
        String registrationBlock = machines.substring(registration, nextRegistration);
        assertTrue(registrationBlock.contains("DShanhaiTextUtil.createElectricText(\"配方线程仓\")"));
        assertTrue(registrationBlock.contains("for (int line = 0; line < 7; line++)"));

        var zh = JsonParser.parseString(Files.readString(LANG.resolve("zh_cn.json"))).getAsJsonObject();
        var en = JsonParser.parseString(Files.readString(LANG.resolve("en_us.json"))).getAsJsonObject();
        for (int line = 0; line < 7; line++) {
            String key = "gt_shanhai.recipe_threads.hatch.tooltip." + line;
            assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), key);
            assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), key);
        }

        assertTrue(Files.exists(GUIDE), "配方线程仓 GuideME 文档缺失");
        String guide = Files.readString(GUIDE);
        assertTrue(guide.contains("gt_shanhai:recipe_thread_hatch"));
        assertTrue(guide.contains("<BlockImage id=\"gt_shanhai:recipe_thread_hatch\""));
        for (int tier = 1; tier <= 7; tier++) {
            String shardId = "dishanhai:thread_shard_" + tier;
            assertTrue(guide.contains("<ItemImage id=\"" + shardId + "\""), shardId);
            assertTrue(guide.contains("<ItemLink id=\"" + shardId + "\" />"), shardId);
        }
        assertTrue(guide.contains("<ItemImage id=\"dishanhai:universal_parallel_overdriver\""));
        assertTrue(guide.contains("<ItemLink id=\"dishanhai:universal_parallel_overdriver\" />"));
        assertTrue(guide.contains("262,145"));
        assertFalse(guide.contains("“") || guide.contains("”"), "GuideME 文档禁止使用中文引号");

        String parallelGuide = Files.readString(PARALLEL_GUIDE);
        assertTrue(parallelGuide.contains("recipe_thread_hatch.md"));
    }

    @Test
    void jadeProviderRendersHatchStatusAndIsRegisteredInPlugin() throws IOException {
        String plugin = Files.readString(JADE_PLUGIN);
        assertTrue(plugin.contains("registerBlockDataProvider(RecipeThreadHatchInfoProvider.INSTANCE"));
        assertTrue(plugin.contains("registerBlockComponent(RecipeThreadHatchInfoProvider.INSTANCE"));

        var zh = JsonParser.parseString(Files.readString(LANG.resolve("zh_cn.json"))).getAsJsonObject();
        var en = JsonParser.parseString(Files.readString(LANG.resolve("en_us.json"))).getAsJsonObject();
        for (String key : List.of("gt_shanhai.recipe_threads.jade.item", "gt_shanhai.recipe_threads.jade.item_empty")) {
            assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), key);
            assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), key);
        }

        CompoundTag serverData = new CompoundTag();
        CompoundTag hatchData = new CompoundTag();
        hatchData.putBoolean("enabled", true);
        hatchData.putLong("capacity", Long.MAX_VALUE);
        hatchData.putInt("owners", 2);
        hatchData.putLong("running", 5L);
        hatchData.putLong("waiting", 1L);
        hatchData.putLong("reserved", 128L);
        hatchData.putLong("parallel", 1024L);
        serverData.put(RecipeThreadHatchInfoProvider.getDataKey(), hatchData);

        List<String> keys = new ArrayList<>();
        IElement dummyElement = proxy(IElement.class, (method, args) -> null);
        IElementHelper helper = proxy(IElementHelper.class, (method, args) -> {
            if ("text".equals(method) && args != null && args.length == 1 && args[0] instanceof Component c
                    && c.getContents() instanceof TranslatableContents tc) {
                keys.add(tc.getKey());
            }
            return dummyElement;
        });
        ITooltip tooltip = proxy(ITooltip.class, (method, args) -> {
            if ("getElementHelper".equals(method)) return helper;
            return null;
        });
        BlockAccessor accessor = proxy(BlockAccessor.class,
                (method, args) -> "getServerData".equals(method) ? serverData : null);

        RecipeThreadHatchInfoProvider.INSTANCE.appendTooltip(tooltip, accessor, null);
        assertEquals(List.of(
                "gt_shanhai.recipe_threads.ui.title",
                "gt_shanhai.recipe_threads.ui.state",
                "gt_shanhai.recipe_threads.ui.capacity",
                "gt_shanhai.recipe_threads.jade.item_empty",
                "gt_shanhai.recipe_threads.ui.owners",
                "gt_shanhai.recipe_threads.ui.activity",
                "gt_shanhai.recipe_threads.ui.parallel"
        ), keys);
    }

    private interface Call { Object invoke(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args));
    }

}
