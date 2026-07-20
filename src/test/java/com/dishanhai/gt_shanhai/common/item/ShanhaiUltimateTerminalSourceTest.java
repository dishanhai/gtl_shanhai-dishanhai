package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ShanhaiUltimateTerminalSourceTest {

    private static final Path ITEMS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "DShanhaiItems.java");
    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalConfig.java");
    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");
    private static final Path INTERACTION = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiUltimateTerminalInteractionHandler.java");
    private static final Path HIGHLIGHT_PACKET = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "network", "ShanhaiStructureHighlightPacket.java");
    private static final Path HIGHLIGHT_CLIENT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "ShanhaiStructureHighlightClient.java");
    private static final Path NETWORK = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "network", "ShanhaiNetwork.java");
    private static final Path RECIPE = Path.of("src", "main", "resources", "data", "gt_shanhai",
            "recipes", "ultimate_terminal.json");
    private static final Path MODEL = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "models", "item", "ultimate_terminal.json");
    private static final Path TEXTURE = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "textures", "item", "ultimate_terminal.png");
    private static final Path ANIMATION = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "textures", "item", "ultimate_terminal.png.mcmeta");

    @Test
    void terminalIsRegisteredAsAnIndependentComponentItem() throws Exception {
        String items = Files.readString(ITEMS);

        assertTrue(items.contains("REGISTRATE.item(\"ultimate_terminal\", ComponentItem::create)"));
        assertTrue(items.contains("properties -> properties.stacksTo(1)"));
        assertTrue(items.contains("GTItems.attach(ShanhaiUltimateTerminalBehavior.INSTANCE)"));
    }

    @Test
    void terminalOwnsVersionedNamespacedState() throws Exception {
        String config = Files.readString(CONFIG);

        assertTrue(config.contains("ROOT_KEY = \"gt_shanhai_terminal\""));
        assertTrue(config.contains("CONFIG_VERSION"));
        assertTrue(config.contains("TerminalUuid"));
        assertTrue(config.contains("BoundAE"));
        assertTrue(config.contains("ReplacementFamily"));
        assertTrue(config.contains("ReplacementTier"));
    }

    @Test
    void terminalDoesNotExtendOrDecodeTheGtlcoreTerminal() throws Exception {
        String behavior = Files.readString(BEHAVIOR);

        assertFalse(behavior.contains("extends UltimateTerminalBehavior"));
        assertFalse(behavior.contains("getAutoBuildSetting("));
        assertFalse(behavior.contains("item.gtlcore.ultimate_terminal"));
    }

    @Test
    void terminalUsesItsOwnEightFrameAnimatedTexture() throws Exception {
        String model = Files.readString(MODEL);
        String animation = Files.readString(ANIMATION);
        BufferedImage texture = ImageIO.read(TEXTURE.toFile());

        assertTrue(model.contains("gt_shanhai:item/ultimate_terminal"));
        assertFalse(model.contains("gtlcore:item/ultimate_terminal"));
        assertTrue(animation.contains("\"frametime\": 4"));
        assertEquals(16, texture.getWidth());
        assertEquals(128, texture.getHeight());
        assertTrue(texture.getColorModel().hasAlpha());
    }

    @Test
    void interactionBindsScansHighlightsAndRequiresSecondBuildConfirmation() throws Exception {
        String behavior = Files.readString(BEHAVIOR);

        assertTrue(behavior.contains(
                "public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context)"));
        assertTrue(behavior.contains("return handleUseOn(context);"));
        assertTrue(behavior.contains("public InteractionResult useOn(UseOnContext context) {\n"
                + "        return InteractionResult.PASS;\n"
                + "    }"));
        assertTrue(behavior.contains("ShanhaiTerminalAeBinding.bind"));
        assertTrue(behavior.contains("MetaMachine.getMachine"));
        assertTrue(behavior.contains("ShanhaiStructurePlanner.scan"));
        assertTrue(behavior.contains("ShanhaiStructureHighlightPacket"));
        assertTrue(behavior.contains("confirmSubmit"));
        assertTrue(behavior.contains("refreshBuildReadiness"));
        assertTrue(behavior.contains("consumeBuildConfirmation"));
        assertTrue(behavior.contains("player.isShiftKeyDown()"));
    }

    @Test
    void dismantleModeFailsExplicitlyWhenNoControllerCanDefineTheFootprint() throws Exception {
        String behavior = Files.readString(BEHAVIOR);

        assertTrue(behavior.contains("if (plan == null)"));
        assertTrue(behavior.contains("ShanhaiUltimateTerminalConfig.isDismantleMode(terminal)"));
        assertTrue(behavior.contains("拆解模式请右击对应多方块控制器"));
        assertTrue(behavior.contains("控制器不存在时无法识别结构范围"));
    }

    @Test
    void chamberCandidatesAreOnlyReportedAndNeverPlacedByTheBehavior() throws Exception {
        String behavior = Files.readString(BEHAVIOR);

        assertTrue(behavior.contains("CHAMBER_HINT"));
        assertTrue(behavior.contains("可放仓室"));
        assertFalse(behavior.contains("MultiblockPartMachine"));
    }

    @Test
    void replacementSelectorMatchesTheOriginalFieldWithoutSharingItsPersistentNbt() throws Exception {
        String behavior = Files.readString(BEHAVIOR);

        assertTrue(behavior.contains("gui.gt_shanhai.ultimate_terminal.tier_blocks"));
        assertTrue(behavior.contains("BlockMapSelectorWidget.getBlock(family)"));
        assertTrue(behavior.contains("blocks[tier].getName()"));
        assertTrue(behavior.contains("ItemStack selectorState = terminal.copy()"));
        assertTrue(behavior.contains("selector.setInit(selectorState)"));
        assertTrue(behavior.contains("ShanhaiUltimateTerminalConfig.setReplacement(terminal, family, tier)"));
        assertFalse(behavior.contains("Component.literal(family + \" : \""));
    }

    @Test
    void leftClickOnlyScansWhileRightClickKeepsTheBuildWorkflow() throws Exception {
        String behavior = Files.readString(BEHAVIOR);
        String interaction = Files.readString(INTERACTION);

        assertTrue(interaction.contains("PlayerInteractEvent.LeftClickBlock"));
        assertTrue(interaction.contains("priority = EventPriority.HIGHEST"));
        assertTrue(interaction.contains("event.setCanceled(true)"));
        assertTrue(interaction.contains("ShanhaiUltimateTerminalBehavior.INSTANCE.scanOnly"));
        assertTrue(behavior.contains("public boolean scanOnly"));

        int rightClick = behavior.indexOf("private InteractionResult handleUseOn");
        int scanOnly = behavior.indexOf("public boolean scanOnly");
        int report = behavior.indexOf("private void reportPlan");
        assertTrue(rightClick >= 0 && scanOnly > rightClick && report > scanOnly);
        assertFalse(behavior.substring(rightClick, scanOnly).contains("reportPlan("));
        assertTrue(behavior.substring(scanOnly, report).contains("reportPlan("));
    }

    @Test
    void scanUsesTheFirstChamberCandidateAndSendsOneColoredHighlightBatch() throws Exception {
        String behavior = Files.readString(BEHAVIOR);
        String packet = Files.readString(HIGHLIGHT_PACKET);
        String client = Files.readString(HIGHLIGHT_CLIENT);
        String network = Files.readString(NETWORK);

        assertTrue(behavior.contains("ShanhaiChamberClassifier.firstCandidate(entry.candidates())"));
        assertTrue(behavior.contains("selection.candidate().getHoverName()"));
        assertTrue(behavior.contains("selection.type().color()"));
        assertTrue(behavior.contains("ShanhaiStructureHighlightPacket.sendTo"));
        assertFalse(behavior.contains("SStructureDetectHighlight"));
        assertTrue(packet.contains("record Marker(BlockPos pos, int color)"));
        assertTrue(packet.contains("buf.writeVarInt(markers.size())"));
        assertTrue(client.contains("com.glodblock.github.glodium.client.render.highlight.HighlightHandler"));
        assertTrue(client.contains("marker.color()"));
        assertTrue(client.contains("Supplier<Boolean> BLINK"));
        assertTrue(client.contains("colorType, Supplier.class"));
        assertFalse(client.contains("BooleanSupplier"));
        assertTrue(network.contains("ShanhaiStructureHighlightPacket.class"));
        assertTrue(network.contains("Optional.of(NetworkDirection.PLAY_TO_CLIENT)"));
    }

    @Test
    void survivalRecipeCombinesTheOriginalToolsWithoutMigratingTheirNbt() throws Exception {
        String recipe = Files.readString(RECIPE);

        assertTrue(recipe.contains("gtlcore:ultimate_terminal"));
        assertTrue(recipe.contains("gtlcore:structure_detect"));
        assertTrue(recipe.contains("gt_shanhai:ultimate_terminal"));
        assertFalse(recipe.contains("copy_nbt"));
    }
}
