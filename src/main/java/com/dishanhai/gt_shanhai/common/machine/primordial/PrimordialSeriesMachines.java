package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「原初系列機器」的禁用判定：只禁用<b>完全依賴外部配方類型</b>的原初機器。
 *
 * <p>判定規則（使用者口徑）：
 * <ul>
 *   <li>機器任一配方類型屬於 {@code gt_shanhai} 自建類型 ⇒ <b>不可禁用</b>（自寫配方要用）。</li>
 *   <li>配方類型全部來自 gtceu / gtlcore / gtladditions ⇒ 可禁用（例：原初臨界加工模塊）。</li>
 *   <li>被動模組（{@code gtceu:dummy} 或無配方類型，如質能核心、歸墟彭羅斯遏制場）⇒ 保留：
 *       它們不是「拿外部配方類型跑配方」的機器，禁用會直接破壞主機玩法。</li>
 *   <li>主機本體（原始終焉引擎、歸墟視界樞核）⇒ 永遠保留。</li>
 * </ul>
 *
 * <p>禁用語義沿用 {@code MultiblockRegistryConfig} 的安全模式：方塊/物品照常註冊，只從 GT 機器表移除，
 * 因此 JEI 結構預覽與配方類型催化物看不到它，既有存檔也不會因方塊缺失而損壞。
 */
public final class PrimordialSeriesMachines {

    /** 配方類型命名空間。 */
    private static final String NAMESPACE = "gt_shanhai";

    /** 主機本體與被動模組：永不進入禁用名單。 */
    private static final Set<String> ALWAYS_KEEP = Set.of(
            "primordial_omega_engine",
            "guixu_vision_core",
            "primordial_mass_energy_core",
            "guixu_penrose_containment_field");

    /**
     * 可被「禁用原初系列機器」關掉的機器：全部只用外部配方類型。
     * <p>新增原初機器時，若它沒有任何 {@code gt_shanhai} 自建配方類型，請加進這裡。
     */
    private static final Set<String> DISABLEABLE = Set.of(
            // 原初臨界加工模塊：全部為 gtceu / gtlcore 基礎加工類型
            "primordial_critical_processing_module",
            // 其他全外部配方類型的原初模組
            "primordial_flame_cracking_kiln",
            "primordial_abyssal_refinery",
            "primordial_cosmic_origin_core",
            "primordial_sixfold_resource_core",
            "primordial_universe_simulation_core",
            "primordial_world_fragments_collector",
            "primordial_anti_entropy_condensation_core",
            "primordial_multidimensional_implosion_core",
            "primordial_supercritical_matter_generation_core",
            "primordial_molecular_rift_core",
            "primordial_tianqiong_assembly_core",
            "primordial_worldline_traversal_matrix",
            "primordial_quantum_distortion_matrix",
            "primordial_shaoguang_aggregation_core",
            "primordial_weiyang_reconstruction_module",
            "primordial_molecular_assembler_module");

    /** 全部原初系列機器 id 前綴判定用（含主機與外部模組）。 */
    public static boolean isPrimordialSeriesId(ResourceLocation id) {
        if (id == null || !NAMESPACE.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.startsWith("primordial_") || path.startsWith("guixu_");
    }

    /** 是否被「禁用原初系列機器」開關命中。 */
    public static boolean isDisabled(ResourceLocation id) {
        if (id == null || !DShanhaiConfig.isPrimordialSeriesDisabled()) {
            return false;
        }
        String path = id.getPath();
        return DISABLEABLE.contains(path) && !ALWAYS_KEEP.contains(path);
    }

    public static boolean isDisabled(MachineDefinition definition) {
        return definition != null && isDisabled(definition.getId());
    }

    /**
     * 執行期判定：只用定義表的配方類型，避免依賴註冊順序。
     *
     * @return true = 這台機器有自建配方類型，或純被動（DUMMY／無類型），禁用開關不得作用於它
     */
    public static boolean hasModOwnedRecipeType(MachineDefinition definition) {
        if (definition == null) {
            return true;
        }
        GTRecipeType[] types = definition.getRecipeTypes();
        if (types == null || types.length == 0) {
            return true;
        }
        for (GTRecipeType type : types) {
            if (type == null || type.registryName == null) {
                continue;
            }
            if (NAMESPACE.equals(type.registryName.getNamespace())) {
                return true;
            }
            if (GTRecipeTypes.DUMMY_RECIPES != null && type == GTRecipeTypes.DUMMY_RECIPES) {
                return true;
            }
        }
        return false;
    }

    /** 相容入口：部分舊呼叫端慣用集合語義。 */
    public static Set<String> disableableIds() {
        return new LinkedHashSet<>(DISABLEABLE);
    }

    /** 永遠保留的機器 id（主機本體與被動模組），供契約測試與診斷使用。 */
    public static Set<String> alwaysKeptIds() {
        return new LinkedHashSet<>(ALWAYS_KEEP);
    }

    private PrimordialSeriesMachines() {}
}
