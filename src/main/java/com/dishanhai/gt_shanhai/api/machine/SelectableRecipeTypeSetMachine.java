package com.dishanhai.gt_shanhai.api.machine;

import com.dishanhai.gt_shanhai.api.gui.configurators.SelectableRecipeTypeSetConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多配方类型选择集基类。
 * <p>
 * 注册时仍暴露完整 recipeTypes；运行时 getRecipeTypes() 只返回玩家选中的集合。
 */
public class SelectableRecipeTypeSetMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            SelectableRecipeTypeSetMachine.class,
            GTLAddWirelessWorkableElectricMultipleRecipesMachine.getMANAGED_FIELD_HOLDER());

    private static final String KEY_ROOT = "sh_selectable_recipe_type_set";
    private static final String KEY_SELECTED_TYPES = "selectedTypes";
    private static final String KEY_SELECTION_INITIALIZED = "selectionInitialized";
    private static final ResourceLocation WRAPPER_RECIPE_TYPE_ID =
            new ResourceLocation("gt_shanhai", "selectable_recipe_type_set");

    private final GTRecipeType recipeTypeSetWrapper;
    private final Set<String> selectedRecipeTypeNames = new LinkedHashSet<>();

    // 解析后的选中配方类型数组缓存：getRecipeTypes()/getSelectedRecipeTypes() 每 tick 被多次调用，
    // 原实现每次都新建 ArrayList + 对每个选中名做 O(全部类型) 的字符串匹配 + 装箱成数组，纯浪费。
    // 仅在选中集合真正变化时失效重建。
    private GTRecipeType[] resolvedSelectedTypesCache;
    // 是否已对当前选中集合做过"剔除失效类型"。定义的可选类型列表运行时不变，
    // 故只需在加载/选择变更后剪枝一次，避免热路径每次调用都新建 valid 名称集合。
    private boolean recipeTypeSelectionPruned;

    private void invalidateResolvedTypesCache() {
        resolvedSelectedTypesCache = null;
        recipeTypeSelectionPruned = false;
    }

    @Persisted @DescSynced private String selectedRecipeTypesSync = "";
    @Persisted @DescSynced private boolean recipeTypeSelectionInitialized = false;

    public SelectableRecipeTypeSetMachine(IMachineBlockEntity holder, GTRecipeType dummyRecipeType, Object... args) {
        super(holder, args);
        this.recipeTypeSetWrapper = new GTRecipeType(WRAPPER_RECIPE_TYPE_ID, "gt_shanhai");
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public GTLAddMultipleWirelessRecipesLogic createRecipeLogic(Object... args) {
        return new SelectableRecipeTypeSetRecipeLogic(this);
    }

    // 代理执行者等机器完全不耗能，但继承的无线基类要求“无线EU网络在线”才开工：
    // checkBeforeWorking() 与 buildFinalWirelessRecipe() 内部各有一道 isOnline() 门槛，
    // 未接无线网络时永远为 false → 配方逻辑永不启动（发了料主机也不跑，只能重进偶发恢复）。
    // 覆写为恒在线、免费能量的假 handler，让两道门槛全过且不真正扣能。
    private IWirelessNetworkEnergyHandler freeWirelessEnergyHandler;

    @Override
    public IWirelessNetworkEnergyHandler getWirelessNetworkEnergyHandler() {
        if (freeWirelessEnergyHandler == null) {
            freeWirelessEnergyHandler = new FreeWirelessNetworkEnergyHandler();
        }
        return freeWirelessEnergyHandler;
    }

    /** 恒在线、无限可用、不真实扣能的无线能源 handler，用于旁路无线EU门槛。 */
    private static final class FreeWirelessNetworkEnergyHandler implements IWirelessNetworkEnergyHandler {
        private static final java.math.BigInteger UNLIMITED =
                java.math.BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(64);

        @Override
        public boolean consumeEnergy(int energy) {
            return true;
        }

        @Override
        public boolean consumeEnergy(long energy) {
            return true;
        }

        @Override
        public boolean consumeEnergy(java.math.BigInteger energy) {
            return true;
        }

        @Override
        public java.math.BigInteger getMaxAvailableEnergy() {
            return UNLIMITED;
        }

        @Override
        public boolean isOnline() {
            return true;
        }
    }

    public Component getRecipeTypeSetTabTitle() {
        return Component.literal("§b配方集合");
    }

    public IGuiTexture getRecipeTypeSetTabIcon() {
        return new ItemStackTexture(Items.COMPARATOR);
    }

    public IFancyUIProvider.PageGroupingData getRecipeTypeSetPageGroupingData() {
        return new IFancyUIProvider.PageGroupingData(null, 1000);
    }

    public String getRecipeTypeSetHeaderText() {
        return "§6§l配方类型选择集";
    }

    public String getRecipeTypeSetDescriptionText() {
        return "§7选中多个时按集合共同搜索";
    }

    public boolean shouldShowRecipeTypeSetTab() {
        return getAllSelectableRecipeTypes().length > 1;
    }

    /**
     * 保持 tick 订阅常驻。
     * 本体系机器（AE 网络供料的原初引擎主机/模块、目标机器动态切换的代理执行者等）的输入来源
     * 往往不走"物品栏变化 -> onContentsChanged"这条唤醒信号：AE 发料不触发机器自身 inventory
     * 变化；代理执行者的实际产出能力依赖插槽里的目标机器定义，也不是标准物品栏事件。
     * 默认 keepSubscribing()=false 时，配方类型选择变更触发的那一次重新订阅若恰好没查到配方，
     * RecipeLogic 会在同一 tick 内立刻再次取消订阅、永久待机——UI 仍显示选中正常，但机器再也
     * 不会主动尝试生产。此处统一覆写为 true，让 RecipeLogic 每 5 tick 主动轮询，从根上避免
     * 子类各自遗漏（如 PrimordialOmegaEngineMachine 曾经漏过、ProxyExecutorMachine 一直没加）。
     */
    @Override
    public boolean keepSubscribing() {
        return true;
    }

    public boolean isRecipeOutputAlwaysMatch(GTRecipe recipe) {
        return false;
    }

    public long getRecipeLogicMaxParallel() {
        return Math.max(1L, (long) getMaxParallel());
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        ensureRecipeTypeSelectionInitialized();
        return getSelectedRecipeTypes();
    }

    @Override
    public GTRecipeType getRecipeType() {
        GTRecipeType selected = getPrimarySelectedRecipeType();
        return selected == null ? recipeTypeSetWrapper : selected;
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        CompoundTag root = new CompoundTag();
        ListTag selected = new ListTag();
        for (String name : selectedRecipeTypeNames) {
            selected.add(StringTag.valueOf(name));
        }
        root.putBoolean(KEY_SELECTION_INITIALIZED, recipeTypeSelectionInitialized);
        root.put(KEY_SELECTED_TYPES, selected);
        tag.put(KEY_ROOT, root);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        selectedRecipeTypeNames.clear();
        invalidateResolvedTypesCache();
        if (!tag.contains(KEY_ROOT)) {
            selectedRecipeTypesSync = "";
            recipeTypeSelectionInitialized = false;
            return;
        }
        CompoundTag root = tag.getCompound(KEY_ROOT);
        recipeTypeSelectionInitialized = !root.contains(KEY_SELECTION_INITIALIZED)
                || root.getBoolean(KEY_SELECTION_INITIALIZED);
        ListTag selected = root.getList(KEY_SELECTED_TYPES, 8);
        for (int i = 0; i < selected.size(); i++) {
            String name = selected.getString(i);
            if (isValidRecipeTypeName(name)) {
                selectedRecipeTypeNames.add(name);
            }
        }
        pruneMissingRecipeTypes();
        updateSelectedRecipeTypesSync();
    }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
        if (shouldShowRecipeTypeSetTab()) {
            tabsWidget.attachSubTab(new SelectableRecipeTypeSetConfigurator(this));
        }
    }

    public GTRecipeType[] getAllSelectableRecipeTypes() {
        if (getDefinition() != null && getDefinition().getRecipeTypes() != null) {
            return getDefinition().getRecipeTypes();
        }
        return new GTRecipeType[0];
    }

    public GTRecipeType[] getSelectedRecipeTypes() {
        ensureRecipeTypeSelectionInitialized();
        GTRecipeType[] cached = resolvedSelectedTypesCache;
        if (cached != null) {
            return cached;
        }
        List<GTRecipeType> result = new ArrayList<>();
        for (String name : selectedRecipeTypeNames) {
            GTRecipeType type = findSelectableRecipeType(name);
            if (type != null) {
                result.add(type);
            }
        }
        GTRecipeType[] array = result.toArray(new GTRecipeType[0]);
        resolvedSelectedTypesCache = array;
        return array;
    }

    public GTRecipeType getPrimarySelectedRecipeType() {
        ensureRecipeTypeSelectionInitialized();
        for (String name : selectedRecipeTypeNames) {
            GTRecipeType type = findSelectableRecipeType(name);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    public boolean hasSelectedRecipeTypes() {
        ensureRecipeTypeSelectionInitialized();
        return !selectedRecipeTypeNames.isEmpty();
    }

    public boolean isRecipeTypeSelected(GTRecipeType type) {
        applySyncedRecipeTypesIfNeeded();
        ensureRecipeTypeSelectionInitialized();
        String name = recipeTypeName(type);
        if (name == null) {
            return false;
        }
        return selectedRecipeTypeNames.contains(name);
    }

    public int getSelectedRecipeTypeCount() {
        return getSelectedRecipeTypes().length;
    }

    /** 当前选中集合的同步串（换行分隔）。供 UI 轮询检测服务端选择变化。 */
    public String getSelectedRecipeTypesSyncValue() {
        return selectedRecipeTypesSync;
    }

    public boolean isRecipeTypeNameSelected(String name) {
        applySyncedRecipeTypesIfNeeded();
        ensureRecipeTypeSelectionInitialized();
        return name != null && selectedRecipeTypeNames.contains(name);
    }

    public void setRecipeTypeSelected(GTRecipeType type, boolean selected) {
        String name = recipeTypeName(type);
        if (name == null) {
            return;
        }
        boolean changed = selected ? selectedRecipeTypeNames.add(name) : selectedRecipeTypeNames.remove(name);
        if (changed) {
            recipeTypeSelectionInitialized = true;
            refreshRecipeTypeSelection();
        }
    }

    public void selectOnlyRecipeType(GTRecipeType type) {
        String name = recipeTypeName(type);
        Set<String> next = new LinkedHashSet<>();
        if (name != null) {
            next.add(name);
        }
        replaceSelectedRecipeTypeNames(next);
    }

    public void selectAllRecipeTypes() {
        Set<String> next = new LinkedHashSet<>();
        for (GTRecipeType type : getAllSelectableRecipeTypes()) {
            String name = recipeTypeName(type);
            if (name != null) {
                next.add(name);
            }
        }
        replaceSelectedRecipeTypeNames(next);
    }

    public void selectFirstRecipeType() {
        Set<String> next = new LinkedHashSet<>();
        GTRecipeType[] types = getAllSelectableRecipeTypes();
        if (types.length > 0) {
            String name = recipeTypeName(types[0]);
            if (name != null) {
                next.add(name);
            }
        }
        replaceSelectedRecipeTypeNames(next);
    }

    public void selectNoRecipeTypes() {
        replaceSelectedRecipeTypeNames(new LinkedHashSet<>());
    }

    public void retainAvailableRecipeTypeSelection(boolean selectFirstWhenEmpty) {
        applySyncedRecipeTypesIfNeeded();
        Set<String> valid = getSelectableRecipeTypeNames();
        // 可选列表为空 = 目标机器暂时取出/槽位尚未加载（代理执行者动态列表），
        // 此时不能把选中集清空——否则玩家的选择集在换手目标机器时被永久抹掉，只保留现状等列表恢复
        if (valid.isEmpty()) {
            refreshRecipeTypeSelection();
            return;
        }
        selectedRecipeTypeNames.removeIf(name -> !valid.contains(name));
        if (selectedRecipeTypeNames.isEmpty()) {
            if (selectFirstWhenEmpty) {
                selectedRecipeTypeNames.add(valid.iterator().next());
            } else {
                selectedRecipeTypeNames.addAll(valid);
            }
        }
        refreshRecipeTypeSelection();
    }

    private void replaceSelectedRecipeTypeNames(Set<String> next) {
        if (selectedRecipeTypeNames.equals(next)) {
            return;
        }
        recipeTypeSelectionInitialized = true;
        selectedRecipeTypeNames.clear();
        selectedRecipeTypeNames.addAll(next);
        refreshRecipeTypeSelection();
    }

    public void refreshRecipeTypeSelection() {
        invalidateResolvedTypesCache();
        pruneMissingRecipeTypes();
        updateSelectedRecipeTypesSync();
        if (getLevel() != null && getLevel().isClientSide) {
            // 客户端只做本地乐观显示，权威状态由服务端 @DescSynced 回同步，不触碰配方逻辑
            return;
        }
        RecipeLogic logic = getRecipeLogic();
        if (logic != null) {
            wakeRecipeLogic(logic);
        }
        markDirty();
        notifyBlockUpdate();
        scheduleRenderUpdate();
    }

    /**
     * 读档后唤醒 RecipeLogic：selectedRecipeTypeNames 本身在 loadCustomPersistedData() 里已经
     * 从 NBT 正确还原（UI 看到的选中状态是对的），但唤醒 RecipeLogic 的这部分（解锁陈旧锁定配方、
     * 退出闲置、重新订阅 tick）此前只写在 refreshRecipeTypeSelection() 里，只有玩家手动切一次
     * 选择集才会触发。读档路径完全没走到这里，导致机器停留在读档时的旧订阅/锁定状态，必须手动
     * 开关一次配方类型才能唤醒。onLoad() 时机在 NBT 反序列化之后，这里补上同一套唤醒逻辑。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() != null && getLevel().isClientSide) {
            return;
        }
        RecipeLogic logic = getRecipeLogic();
        if (logic != null) {
            wakeRecipeLogic(logic);
        }
    }

    private void wakeRecipeLogic(RecipeLogic logic) {
        onRecipeTypeSelectionChanged(logic);
        // 多配方合成逻辑在 setupRecipe 前就已真实扣除输入、产出在 onRecipeFinish 才给出，
        // 运行中打断（interruptRecipe/resetRecipeLogic）= 已扣输入直接蒸发。
        // 因此选择变更不打断正在运行/等待输出的配方，新选择在下一轮 lookup 自然生效；
        // 锁定配方仅在其类型被取消选择时清锁，避免无关点选丢锁。
        if (logic instanceof ILockRecipe lockRecipe) {
            GTRecipe locked = lockRecipe.getLockRecipe();
            if (locked != null && (locked.recipeType == null
                    || !selectedRecipeTypeNames.contains(recipeTypeName(locked.recipeType)))) {
                lockRecipe.setLockRecipe(null);
            }
        }
        if (logic.isIdle()) {
            logic.resetRecipeLogic();
        }
        logic.updateTickSubscription();
    }

    protected void onRecipeTypeSelectionChanged(RecipeLogic logic) {
        if (logic instanceof SelectableRecipeTypeSetRecipeLogic selectableLogic) {
            selectableLogic.onRecipeTypeSelectionChanged();
        }
    }

    private void pruneMissingRecipeTypes() {
        if (recipeTypeSelectionPruned) {
            return;
        }
        if (selectedRecipeTypeNames.isEmpty()) {
            recipeTypeSelectionPruned = true;
            return;
        }
        Set<String> valid = getSelectableRecipeTypeNames();
        if (valid.isEmpty()) {
            return;
        }
        if (selectedRecipeTypeNames.removeIf(name -> !valid.contains(name))) {
            invalidateResolvedTypesCache();
        }
        recipeTypeSelectionPruned = true;
    }

    private Set<String> getSelectableRecipeTypeNames() {
        Set<String> valid = new LinkedHashSet<>();
        GTRecipeType[] types = getAllSelectableRecipeTypes();
        for (GTRecipeType type : types) {
            String name = recipeTypeName(type);
            if (name != null) {
                valid.add(name);
            }
        }
        return valid;
    }

    private void ensureRecipeTypeSelectionInitialized() {
        applySyncedRecipeTypesIfNeeded();
        pruneMissingRecipeTypes();
        if (recipeTypeSelectionInitialized || !selectedRecipeTypeNames.isEmpty()) {
            return;
        }
        GTRecipeType[] types = getAllSelectableRecipeTypes();
        if (types.length == 0) {
            return;
        }
        for (GTRecipeType type : types) {
            String name = recipeTypeName(type);
            if (name != null) {
                selectedRecipeTypeNames.add(name);
            }
        }
        recipeTypeSelectionInitialized = true;
        updateSelectedRecipeTypesSync();
    }

    // 客户端已应用过的同步串快照：用于检测 @DescSynced 字符串变化（服务端剪枝/他人操作/全空），
    // 旧实现只在本地集合为空时应用一次，集合一旦非空就永远不再跟随服务端，UI 显示陈旧选择。
    private String appliedSyncedSelection = null;

    private void applySyncedRecipeTypesIfNeeded() {
        String sync = selectedRecipeTypesSync;
        if (getLevel() != null && getLevel().isClientSide) {
            // 客户端：同步串一变就整体重建本地集合（含变空），服务端是唯一权威
            if (sync.equals(appliedSyncedSelection)) {
                return;
            }
            appliedSyncedSelection = sync;
            selectedRecipeTypeNames.clear();
            if (!sync.isEmpty()) {
                for (String name : sync.split("\\n")) {
                    if (isValidRecipeTypeName(name)) {
                        selectedRecipeTypeNames.add(name);
                    }
                }
            }
            invalidateResolvedTypesCache();
            pruneMissingRecipeTypes();
            return;
        }
        // 服务端：仅保留读档顺序兜底——集合为空而持久化同步串非空时重建一次
        if (!selectedRecipeTypeNames.isEmpty() || sync.isEmpty()) {
            return;
        }
        for (String name : sync.split("\\n")) {
            if (isValidRecipeTypeName(name)) {
                selectedRecipeTypeNames.add(name);
            }
        }
        invalidateResolvedTypesCache();
        pruneMissingRecipeTypes();
    }

    private void updateSelectedRecipeTypesSync() {
        invalidateResolvedTypesCache();
        StringBuilder builder = new StringBuilder();
        for (String name : selectedRecipeTypeNames) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(name);
        }
        selectedRecipeTypesSync = builder.toString();
    }

    private GTRecipeType findSelectableRecipeType(String name) {
        if (name == null) {
            return null;
        }
        for (GTRecipeType type : getAllSelectableRecipeTypes()) {
            if (name.equals(recipeTypeName(type))) {
                return type;
            }
        }
        return null;
    }

    private static boolean isValidRecipeTypeName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new ResourceLocation(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String recipeTypeName(GTRecipeType type) {
        if (type == null || type.registryName == null) {
            return null;
        }
        return type.registryName.toString();
    }
}
