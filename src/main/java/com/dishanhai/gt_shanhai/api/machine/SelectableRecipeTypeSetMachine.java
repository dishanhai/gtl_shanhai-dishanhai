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
    private GTRecipeType forcedSearchRecipeType;
    private GTRecipeType[] forcedSearchRecipeTypesCache;

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

    public boolean isRecipeOutputAlwaysMatch(GTRecipe recipe) {
        return false;
    }

    public long getRecipeLogicMaxParallel() {
        return Math.max(1L, (long) getMaxParallel());
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        if (forcedSearchRecipeType != null) {
            return forcedSearchRecipeTypesCache;
        }
        ensureRecipeTypeSelectionInitialized();
        return getSelectedRecipeTypes();
    }

    @Override
    public GTRecipeType getRecipeType() {
        if (forcedSearchRecipeType != null) {
            return forcedSearchRecipeType;
        }
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

    void setForcedSearchRecipeType(GTRecipeType forcedSearchRecipeType) {
        this.forcedSearchRecipeType = forcedSearchRecipeType;
        this.forcedSearchRecipeTypesCache = forcedSearchRecipeType == null
                ? null
                : new GTRecipeType[] { forcedSearchRecipeType };
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
        selectedRecipeTypeNames.removeIf(name -> !valid.contains(name));
        if (selectedRecipeTypeNames.isEmpty() && !valid.isEmpty()) {
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
        RecipeLogic logic = getRecipeLogic();
        if (logic != null) {
            onRecipeTypeSelectionChanged(logic);
            if (logic instanceof ILockRecipe lockRecipe) {
                lockRecipe.setLockRecipe(null);
            }
            logic.resetRecipeLogic();
        }
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
        scheduleRenderUpdate();
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

    private void applySyncedRecipeTypesIfNeeded() {
        if (!selectedRecipeTypeNames.isEmpty() || selectedRecipeTypesSync.isEmpty()) {
            return;
        }
        String[] names = selectedRecipeTypesSync.split("\\n");
        for (String name : names) {
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
