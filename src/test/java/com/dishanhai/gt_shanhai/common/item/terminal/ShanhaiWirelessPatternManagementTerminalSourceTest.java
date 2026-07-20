package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShanhaiWirelessPatternManagementTerminalSourceTest {

    private static final Path ROOT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");

    @Test
    void terminalInheritsAe2wtlibWirelessRulesAndRegistersAsAWutModule() throws Exception {
        String item = read("common/item/terminal/ShanhaiWirelessPatternManagementTerminalItem.java");
        String integration = read("common/item/terminal/ShanhaiPatternTerminalIntegration.java");
        String items = read("common/item/DShanhaiItems.java");
        String mixin = read("mixin/Ae2wtlibTerminalRegistrationMixin.java");
        String mixinConfig = Files.readString(Path.of("src", "main", "resources", "gt_shanhai.mixin.json"));
        String mod = read("GTDishanhaiMod.java");

        assertTrue(item.contains("extends ItemWT implements ICurioItem"));
        assertTrue(integration.contains("MODULE_ID = \"shanhai_pattern_management\""));
        assertTrue(integration.contains("WUTHandler.addTerminal"));
        assertTrue(integration.contains("GridLinkables.register"));
        assertTrue(integration.contains("AE2wtlib.QUANTUM_BRIDGE_CARD"));
        assertTrue(integration.contains("AEItems.ENERGY_CARD"));
        assertTrue(items.contains(".onRegister(ShanhaiPatternTerminalIntegration::onItemRegistered)"));
        assertTrue(integration.contains("public static void onAe2wtlibReady()"));
        assertTrue(integration.contains("AE2wtlib.UNIVERSAL_TERMINAL == null"));
        assertTrue(mixin.contains("@Inject(method = \"onAe2Initialized\", at = @At(\"TAIL\"), remap = false)"));
        assertTrue(mixin.contains("ShanhaiPatternTerminalIntegration.onAe2wtlibReady()"));
        assertTrue(mixinConfig.contains("\"Ae2wtlibTerminalRegistrationMixin\""));
        assertFalse(mod.contains("ShanhaiPatternTerminalIntegration.init()"));
        assertFalse(mod.contains("FMLCommonSetupEvent event"));
    }

    @Test
    void shortcutUsesTheConfirmedCandidatePriority() throws Exception {
        String source = read("common/item/terminal/ShanhaiPatternTerminalOpenHelper.java");

        int hands = source.indexOf("for (InteractionHand hand");
        int inventoryWut = source.indexOf("stack.getItem() instanceof ItemWUT");
        int curios = source.indexOf("CurioHelper.findTerminal");
        int inventoryTerminal = source.lastIndexOf("stack.getItem() == terminal");

        assertTrue(hands >= 0 && inventoryWut > hands);
        assertTrue(curios > inventoryWut);
        assertTrue(inventoryTerminal > curios);
    }

    @Test
    void wutRecipesCoverUpgradeAndEveryBundledWirelessTerminal() throws Exception {
        JsonObject upgrade = readRecipe("upgrade_pattern_management.json");
        assertEquals("ae2wtlib:upgrade", upgrade.get("type").getAsString());
        assertEquals("gt_shanhai:wireless_pattern_management_terminal",
                upgrade.getAsJsonObject("terminal").get("item").getAsString());
        assertEquals("shanhai_pattern_management", upgrade.get("terminalName").getAsString());

        assertCombineRecipe("combine_crafting.json", "ae2:wireless_crafting_terminal", "crafting");
        assertCombineRecipe("combine_pattern_access.json",
                "ae2wtlib:wireless_pattern_access_terminal", "pattern_access");
        assertCombineRecipe("combine_pattern_encoding.json",
                "ae2wtlib:wireless_pattern_encoding_terminal", "pattern_encoding");
        assertCombineRecipe("combine_ex_pattern_access.json",
                "expatternprovider:wireless_ex_pat", "ex_pattern_access");
    }

    @Test
    void remoteRequestsNeverAcceptClientCoordinates() throws Exception {
        String packet = read("network/ShanhaiPatternRemoteConfigPacket.java");

        assertTrue(packet.contains("private final long serverId"));
        assertTrue(packet.contains("menu.resolveStellarContainer(packet.serverId)"));
        assertTrue(packet.contains("isValidRemotePatternSlot"));
        assertFalse(packet.contains("BlockPos"));
        assertFalse(packet.contains("ResourceKey<Level>"));
    }

    @Test
    void menuUsesCurrentAe2TrackerAndGridAsTheAuthority() throws Exception {
        String menu = read("common/item/terminal/ShanhaiPatternManagementMenu.java");

        assertTrue(menu.contains("BY_ID_FIELD"));
        assertTrue(menu.contains("TRACKER_CONTAINER_FIELD"));
        assertTrue(menu.contains("stellar.getGrid() != currentGrid"));
        assertTrue(menu.contains("ShanhaiPatternContainerMetadataPacket"));
    }

    @Test
    void onlyStellarRowsExposeRemoteControls() throws Exception {
        String screen = read("client/gui/terminal/ShanhaiPatternManagementScreen.java");

        assertTrue(screen.contains("getMenu().isStellarContainer(serverId)"));
        assertTrue(screen.contains("mouseButton == 2"));
        assertTrue(screen.contains("Operation.OPEN_SLOT_CATALYST"));
        assertTrue(screen.contains("Operation.OPEN_STOCK_INPUT"));
    }

    @Test
    void remotePagesReuseTheExistingStellarWidgets() throws Exception {
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");

        assertTrue(machine.contains("createRemoteSlotCatalystUI"));
        assertTrue(machine.contains("new MEPatternCatalystUIManager"));
        assertTrue(machine.contains("createRemoteStockInputUI"));
        assertTrue(machine.contains("new AEDualConfigWidget"));
    }

    @Test
    void remoteHolderKeepsAHandHeldTerminalValid() throws Exception {
        String holder = read("common/item/terminal/ShanhaiStellarRemoteUIHolder.java");

        assertTrue(holder.contains("for (InteractionHand hand : InteractionHand.values())"));
        assertTrue(holder.contains("player.getItemInHand(hand) == expected"));
        assertTrue(holder.contains("terminalHost.stillValid()"));
        assertFalse(holder.contains("if (!terminalHost.stillValid() ||"));
    }

    @Test
    void clientRemoteUiUsesALegalDetachedMachineHolder() throws Exception {
        String holder = read("common/item/terminal/ShanhaiStellarRemoteUIHolder.java");
        String factory = read("common/item/terminal/ShanhaiStellarRemoteUIFactory.java");
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");

        assertFalse(holder.contains("new RecipeTypePatternBufferPartMachine(null)"));
        assertTrue(factory.contains("definition.getBlockEntityType().create"));
        assertTrue(factory.contains("machineBlockEntity.getMetaMachine() instanceof RecipeTypePatternBufferPartMachine"));
        assertTrue(holder.contains("if (terminalHost == null) return false;"));
        assertTrue(machine.contains("root.addWidget(manager)"));
        assertFalse(machine.contains("root.waitToAdded(manager)"));
    }

    @Test
    void implementationInheritsExtendedAeWithoutCopyingItsPrivateSearchState() throws Exception {
        StringBuilder source = new StringBuilder();
        Path terminal = ROOT.resolve("common/item/terminal");
        try (var paths = Files.walk(terminal)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                source.append(Files.readString(path));
            }
        }
        source.append(read("client/gui/terminal/ShanhaiPatternManagementScreen.java"));

        assertTrue(source.toString().contains("GuiExPatternTerminal"));
        assertTrue(source.toString().contains("ContainerExPatternTerminal"));
        assertFalse(source.toString().contains("searchOutField"));
        assertFalse(source.toString().contains("searchInField"));
        assertFalse(source.toString().contains("infoMap"));
        assertFalse(source.toString().contains("highlightBtns"));
    }

    @Test
    void eaepOpenUiRedirectsStellarProvidersToTheFullRemotePatternUi() throws Exception {
        String mixin = read("mixin/EaepOpenProviderUiStellarMixin.java");
        String menu = read("common/item/terminal/ShanhaiPatternManagementMenu.java");
        String factory = read("common/item/terminal/ShanhaiStellarRemoteUIFactory.java");
        String holder = read("common/item/terminal/ShanhaiStellarRemoteUIHolder.java");
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String config = Files.readString(Path.of("src", "main", "resources", "gt_shanhai.mixin.json"));

        assertTrue(mixin.contains("OpenProviderUiC2SPacket.class"));
        assertTrue(mixin.contains("lambda$handle$0"));
        assertTrue(mixin.contains("menu.resolveStellarContainer(level, pos)"));
        assertTrue(mixin.contains("ShanhaiStellarRemoteUIFactory.INSTANCE.openPattern"));
        assertTrue(menu.contains("resolveStellarContainer(ServerLevel level, BlockPos pos)"));
        assertTrue(factory.contains("public boolean openPattern"));
        assertTrue(holder.contains("FULL_PATTERN"));
        assertTrue(machine.contains("createRemotePatternUI"));
        assertTrue(config.contains("\"EaepOpenProviderUiStellarMixin\""));
    }

    @Test
    void sameGroupStellarProvidersExposeDistinctServerIdRemoteUiButtons() throws Exception {
        String screen = read("client/gui/terminal/ShanhaiPatternManagementScreen.java");
        String packet = read("network/ShanhaiPatternRemoteConfigPacket.java");

        assertTrue(screen.contains("patternSlot.getMachineInv().getServerId()"));
        assertTrue(screen.contains("drawnProviders.add(serverId)"));
        assertTrue(screen.contains("new RemoteUiButtonTarget(serverId"));
        assertTrue(screen.contains("hideEaepGroupUiButtons()"));
        assertTrue(screen.contains("EAEP_OPEN_UI_BUTTONS_FIELD"));
        assertTrue(screen.contains("Operation.OPEN_FULL_PATTERN"));
        assertFalse(screen.contains("collectStellarServerIds"));
        assertFalse(screen.contains("GuiExPatternTerminalGroupHeaderRowAccessor"));
        assertFalse(screen.contains("containers.iterator().next()"));
        assertTrue(packet.contains("OPEN_FULL_PATTERN"));
        assertTrue(packet.contains("ShanhaiStellarRemoteUIFactory.INSTANCE.openPattern"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative.replace('/', java.io.File.separatorChar)));
    }

    private static JsonObject readRecipe(String name) throws Exception {
        Path path = Path.of("src", "main", "resources", "data", "gt_shanhai", "recipes",
                "wireless_universal_terminal", name);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static void assertCombineRecipe(String name, String otherItem, String otherName) throws Exception {
        JsonObject recipe = readRecipe(name);
        assertEquals("ae2wtlib:combine", recipe.get("type").getAsString());
        assertEquals("gt_shanhai:wireless_pattern_management_terminal",
                recipe.getAsJsonObject("terminalA").get("item").getAsString());
        assertEquals("shanhai_pattern_management", recipe.get("terminalAName").getAsString());
        assertEquals(otherItem, recipe.getAsJsonObject("terminalB").get("item").getAsString());
        assertEquals(otherName, recipe.get("terminalBName").getAsString());
    }
}
