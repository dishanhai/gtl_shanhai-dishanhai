package com.dishanhai.gt_shanhai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 多方块注册开关。
 * <p>
 * 配置文件：{@code config/gt_shanhai/multiblock_registry.json}。
 * 启动期先读取，用于本轮决定哪些多方块要移除；全部模组注册完成后再自动扫描
 * {@link GTRegistries#MACHINES}，按 modid 分组写出/更新配置。
 * <p>
 * 禁用语义（安全模式）：方块/物品仍由模组正常注册，但会从 GT 机器注册表中移除，
 * 因此 JEI 结构预览、配方类型催化物、按 ID 查找机器都不会再看到它；已有存档不会因
 * 方块缺失而损坏。修改配置后需要重启游戏/服务端生效。
 */
public final class MultiblockRegistryConfig {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai_multiblock_config");

    private static final String FILE_NAME = "multiblock_registry.json";
    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 延迟到第一次使用时解析，避免类加载阶段 FMLPaths 尚未就绪。 */
    private static volatile boolean loaded;
    private static volatile boolean filePresent;
    private static volatile Set<String> disabledMachineIds = Collections.emptySet();
    private static volatile Set<String> disabledModIds = Collections.emptySet();

    /** 读取到的原始值，用于扫描时不覆盖玩家手改的开关。 */
    private static final Map<String, Boolean> knownModEnabled = new LinkedHashMap<>();
    private static final Map<String, Boolean> knownMachineEnabled = new LinkedHashMap<>();

    private static boolean applied;

    private MultiblockRegistryConfig() {}

    /**
     * 注册启动期钩子。扫描/移除必须在所有模组完成多方块注册之后、JEI 注册配方之前执行，
     * 因此挂在 FMLCommonSetupEvent 的最高优先级上。
     */
    public static void init(IEventBus modBus) {
        modBus.addListener(EventPriority.HIGHEST, (FMLCommonSetupEvent event) -> scanAndApply());
    }

    private static Path filePath() {
        return com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.multiblockRegistry());
    }

    /**
     * 读取配置。必须在所有多方块注册之前调用（模组构造期即可），
     * 这样本轮启动就能按配置移除机器。
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path file = filePath();
        filePresent = Files.isRegularFile(file);
        if (!filePresent) {
            LOG.info("[山海多方块] 未找到 {}，本次按全部启用处理；注册完成后会自动扫描生成", file);
            return;
        }

        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("根节点不是 JSON 对象");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject mods = root.has("mods") && root.get("mods").isJsonObject()
                    ? root.getAsJsonObject("mods")
                    : new JsonObject();

            Set<String> disabledIds = new HashSet<>();
            Set<String> disabledMods = new HashSet<>();
            Map<String, Boolean> modEnabled = new LinkedHashMap<>();
            Map<String, Boolean> machineEnabled = new LinkedHashMap<>();

            for (Map.Entry<String, JsonElement> modEntry : mods.entrySet()) {
                String namespace = modEntry.getKey();
                if (!modEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject group = modEntry.getValue().getAsJsonObject();
                boolean enabled = readBoolean(group.get("enabled"), true);
                modEnabled.put(namespace, enabled);
                if (!enabled) {
                    disabledMods.add(namespace);
                }

                JsonObject machines = group.has("multiblocks") && group.get("multiblocks").isJsonObject()
                        ? group.getAsJsonObject("multiblocks")
                        : null;
                if (machines == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> machineEntry : machines.entrySet()) {
                    String id = machineEntry.getKey();
                    boolean machineOn = readBoolean(machineEntry.getValue(), true);
                    machineEnabled.put(id, machineOn);
                    if (!machineOn) {
                        disabledIds.add(id);
                    }
                }
            }

            disabledMachineIds = Set.copyOf(disabledIds);
            disabledModIds = Set.copyOf(disabledMods);
            knownModEnabled.clear();
            knownModEnabled.putAll(modEnabled);
            knownMachineEnabled.clear();
            knownMachineEnabled.putAll(machineEnabled);

            LOG.info("[山海多方块] 已读取 {}：禁用模组 {} 个、禁用单项 {} 台",
                    file, disabledModIds.size(), disabledMachineIds.size());
        } catch (Exception e) {
            // 配置写坏时保留现场，交给注册完成后的扫描重新生成，避免玩家手改内容直接丢失。
            Path backup = file.resolveSibling(FILE_NAME + ".broken");
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception copyError) {
                LOG.warn("[山海多方块] 备份损坏配置失败: {}", copyError.getMessage());
            }
            filePresent = false;
            disabledMachineIds = Collections.emptySet();
            disabledModIds = Collections.emptySet();
            knownModEnabled.clear();
            knownMachineEnabled.clear();
            LOG.warn("[山海多方块] 读取 {} 失败，已备份到 {}，本次按全部启用处理并重新生成: {}",
                    file, backup, e.getMessage());
        }
    }

    /** 被配置禁用的机器 ID（含整组模组禁用）。 */
    public static boolean isDisabled(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        return disabledModIds.contains(id.getNamespace())
                || disabledMachineIds.contains(id.toString());
    }

    /**
     * 在所有模组完成注册后调用（FMLCommonSetupEvent）：
     * 先扫描当前全部多方块并按模组写盘，再把配置禁用的机器从 GT 机器注册表移除。
     */
    public static synchronized void scanAndApply() {
        if (applied) {
            return;
        }
        applied = true;
        if (!loaded) {
            load();
        }

        List<MachineDefinition> machines = new ArrayList<>(GTRegistries.MACHINES.values());
        TreeMap<String, TreeMap<String, Boolean>> discovered = new TreeMap<>();
        int total = 0;
        for (MachineDefinition definition : machines) {
            if (!(definition instanceof MultiblockMachineDefinition)) {
                continue;
            }
            ResourceLocation id = definition.getId();
            if (id == null) {
                continue;
            }
            total++;
            TreeMap<String, Boolean> group = discovered.computeIfAbsent(
                    id.getNamespace(), key -> new TreeMap<>());
            String key = id.toString();
            // 配置里已有的开关原样保留；新发现的机器默认启用。
            group.put(key, knownMachineEnabled.getOrDefault(key, Boolean.TRUE));
        }

        boolean generated = !filePresent;
        // 保护：若本轮一台多方块都没扫描到（GT 注册表异常/加载失败），不要用空列表覆盖已有配置。
        if (total == 0 && !knownMachineEnabled.isEmpty()) {
            LOG.warn("[山海多方块] 未扫描到任何多方块，保留现有配置不覆盖");
        } else if (shouldRewrite(discovered)) {
            write(discovered);
            generated = true;
        }

        List<ResourceLocation> toRemove = new ArrayList<>();
        java.util.Set<ResourceLocation> primordialDisabled = new java.util.LinkedHashSet<>();
        for (MachineDefinition definition : machines) {
            if (!(definition instanceof MultiblockMachineDefinition)) {
                continue;
            }
            ResourceLocation id = definition.getId();
            if (id != null && isDisabled(id)) {
                toRemove.add(id);
            }
        }

        // 「禁用原初系列機器」：只作用於配方類型全為外部的原初模組，主機與被動模組靠白名單排除。
        if (com.dishanhai.gt_shanhai.config.DShanhaiConfig.isPrimordialSeriesDisabled()) {
            for (MachineDefinition definition : machines) {
                if (!(definition instanceof MultiblockMachineDefinition)) {
                    continue;
                }
                ResourceLocation id = definition.getId();
                if (!com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialSeriesMachines
                        .isPrimordialSeriesId(id)) {
                    continue;
                }
                if (!com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialSeriesMachines
                        .isDisabled(id)) {
                    continue;
                }
                if (com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialSeriesMachines
                        .hasModOwnedRecipeType(definition)) {
                    continue;
                }
                if (primordialDisabled.add(id)) {
                    toRemove.add(id);
                }
            }
            if (!primordialDisabled.isEmpty()) {
                LOG.info("[山海原初] 禁用原初系列机器：{}", primordialDisabled);
            }
        }

        int removed = 0;
        for (ResourceLocation id : toRemove) {
            try {
                if (GTRegistries.MACHINES.remove(id)) {
                    removed++;
                }
            } catch (RuntimeException e) {
                LOG.warn("[山海多方块] 移除 {} 失败: {}", id, e.getMessage());
            }
        }

        if (removed > 0) {
            LOG.info("[山海多方块] 本次启动已从 GT 机器注册表移除 {} 台多方块（JEI 结构预览同步隐藏）", removed);
        } else {
            LOG.info("[山海多方块] 本次启动没有需要移除的多方块");
        }
        LOG.info("[山海多方块] 扫描完成：{} 个模组、{} 台多方块{}",
                discovered.size(), total,
                generated ? "（配置已生成/更新，修改后重启生效）" : "");
    }

    private static boolean shouldRewrite(TreeMap<String, TreeMap<String, Boolean>> discovered) {
        if (!filePresent) {
            return true;
        }
        if (!discovered.keySet().equals(knownModEnabled.keySet())) {
            return true;
        }
        Set<String> discoveredIds = new HashSet<>();
        for (TreeMap<String, Boolean> group : discovered.values()) {
            discoveredIds.addAll(group.keySet());
        }
        return !discoveredIds.equals(knownMachineEnabled.keySet());
    }

    private static void write(TreeMap<String, TreeMap<String, Boolean>> discovered) {
        Path file = filePath();
        try {
            Files.createDirectories(file.getParent());

            JsonObject root = new JsonObject();
            JsonArray comment = new JsonArray();
            comment.add("gt_shanhai 多方块注册开关。启动时读取，修改后需要重启游戏/服务端生效。");
            comment.add("mods.<modid>.enabled=false：禁用该模组下全部多方块（忽略单项设置）。");
            comment.add("mods.<modid>.multiblocks.<机器ID>=true/false：单项开关。");
            comment.add("false = 从 GT 机器注册表移除，并隐藏 JEI 多方块结构预览；方块/物品本身仍保留，避免旧存档损坏。");
            root.add("_comment", comment);
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("generatedAt",
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()));

            JsonObject modsObject = new JsonObject();
            for (Map.Entry<String, TreeMap<String, Boolean>> modEntry : discovered.entrySet()) {
                String namespace = modEntry.getKey();
                JsonObject group = new JsonObject();
                group.addProperty("name", displayName(namespace));
                group.addProperty("enabled", knownModEnabled.getOrDefault(namespace, Boolean.TRUE));

                JsonObject machines = new JsonObject();
                for (Map.Entry<String, Boolean> machineEntry : modEntry.getValue().entrySet()) {
                    machines.addProperty(machineEntry.getKey(), machineEntry.getValue());
                }
                group.add("multiblocks", machines);
                modsObject.add(namespace, group);
            }
            root.add("mods", modsObject);

            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(root));
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }

            filePresent = true;

            LOG.info("[山海多方块] 已生成/更新 {}", file);
        } catch (Exception e) {
            LOG.warn("[山海多方块] 写入 {} 失败: {}", file, e.getMessage());
        }
    }

    private static String displayName(String namespace) {
        try {
            return ModList.get().getModContainerById(namespace)
                    .map(container -> container.getModInfo().getDisplayName())
                    .orElse(namespace);
        } catch (RuntimeException e) {
            return namespace;
        }
    }

    private static boolean readBoolean(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
