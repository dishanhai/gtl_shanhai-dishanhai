package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星律共享搜索集：玩家可配置哪些配方类型互相视为"可共同搜索/执行"，打破严格的类型隔离。
 *
 * <p>动机：如化学反应釜（gtceu:chemical_reactor）与大型化学反应釜（gtceu:large_chemical_reactor）
 * 共享绝大部分配方，但星律按样板编码类型严格隔离——化反样板在大型化反宿主上会被
 * {@code hostAllowsVirtualRecipeType} / {@code slotAllowsRecipe} 直接拒绝，明明等价的配方无法执行。
 *
 * <p>配置来源 {@code recipe_type_pattern_buffer.sharedSearchSets}：每个条目是一组以逗号/分号/空白
 * 分隔的配方类型 ID（需带命名空间），同一条目内的类型互认；不同条目若有共同成员则传递合并为一组。
 *
 * <p>生效层级是"放行判定"而非"重解析"：样板自身的配方解析仍严格在其编码类型域内进行（权威标记
 * 语义不变），共享集只在宿主类型校验、选择集选中校验、槽位扣料匹配这三类门槛上放行同组类型。
 * 与 {@code allowUnsupportedHostRecipeTypes}（全放开）相比，这是按组精确授权的中间档。
 *
 * <p>解析结果按配置列表实例做快照缓存：ForgeConfigSpec 在配置重载前 {@code get()} 返回同一实例，
 * 命中时零解析开销；重载后实例变化自动重建。配置尚未加载（数据生成/极早期调用）时视为无共享集。
 */
public final class RecipeTypeSharedSearchSets {

    private RecipeTypeSharedSearchSets() {
    }

    private static final class Snapshot {
        final List<? extends String> source;
        final Map<String, Set<String>> groupsByTypeId;

        Snapshot(List<? extends String> source, Map<String, Set<String>> groupsByTypeId) {
            this.source = source;
            this.groupsByTypeId = groupsByTypeId;
        }
    }

    private static volatile Snapshot snapshot;

    /** 两个配方类型 ID 是否同组（同 ID 恒为共享）。入参为 registryName 全称，如 "gtceu:chemical_reactor"。 */
    public static boolean isShared(String typeIdA, String typeIdB) {
        if (PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(typeIdA, typeIdB)) return true;
        String canonicalA = PatternRecipeTypeHelper.canonicalRecipeTypeId(typeIdA);
        String canonicalB = PatternRecipeTypeHelper.canonicalRecipeTypeId(typeIdB);
        if (canonicalA.isEmpty() || canonicalB.isEmpty()) return false;
        Set<String> group = currentGroups().get(canonicalA);
        return group != null && group.contains(canonicalB);
    }

    /** {@code type} 是否与候选数组中任一类型同组。候选为空或无组配置时返回 false（保持严格隔离）。 */
    public static boolean isSharedWithAny(GTRecipeType type, GTRecipeType[] candidates) {
        if (type == null || type.registryName == null || candidates == null || candidates.length == 0) {
            return false;
        }
        String typeId = PatternRecipeTypeHelper.canonicalRecipeTypeId(type.registryName.toString());
        Set<String> group = currentGroups().get(typeId);
        if (group == null) return false;
        for (GTRecipeType candidate : candidates) {
            if (candidate == null || candidate.registryName == null) continue;
            String candidateId = PatternRecipeTypeHelper.canonicalRecipeTypeId(candidate.registryName.toString());
            if (group.contains(candidateId)) return true;
        }
        return false;
    }

    private static Map<String, Set<String>> currentGroups() {
        List<? extends String> source;
        try {
            source = DShanhaiConfig.COMMON.recipeTypeSharedSearchSets.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return Collections.emptyMap();
        }
        Snapshot cached = snapshot;
        if (cached != null && cached.source == source) {
            return cached.groupsByTypeId;
        }
        Map<String, Set<String>> groups = parseGroups(source);
        snapshot = new Snapshot(source, groups);
        return groups;
    }

    /**
     * 纯解析核心（无 MC 运行时依赖，供单元测试直接调用）：条目内按逗号/分号/空白切分并统一小写，
     * 单成员/空条目忽略；跨条目共享成员时传递合并成同一组，组内每个成员都指向同一个不可变集合。
     */
    static Map<String, Set<String>> parseGroups(List<? extends String> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyMap();
        Map<String, Set<String>> groupsByTypeId = new HashMap<>();
        for (String entry : entries) {
            if (entry == null) continue;
            LinkedHashSet<String> members = new LinkedHashSet<>();
            for (String raw : entry.split("[,;\\s]+")) {
                String id = PatternRecipeTypeHelper.canonicalRecipeTypeId(raw);
                if (!id.isEmpty()) {
                    members.add(id);
                }
            }
            if (members.size() < 2) continue;
            LinkedHashSet<String> merged = new LinkedHashSet<>(members);
            for (String member : members) {
                Set<String> existing = groupsByTypeId.get(member);
                if (existing != null) {
                    merged.addAll(existing);
                }
            }
            Set<String> immutable = Collections.unmodifiableSet(merged);
            for (String member : merged) {
                groupsByTypeId.put(member, immutable);
            }
        }
        return groupsByTypeId;
    }
}
