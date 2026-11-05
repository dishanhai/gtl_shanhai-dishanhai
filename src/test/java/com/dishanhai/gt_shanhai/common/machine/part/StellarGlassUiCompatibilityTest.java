package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：直接核對實際 classpath 位元碼，避免來源版本落後造成 accessor 啟動失敗。 */
class StellarGlassUiCompatibilityTest {
    private static ClassNode bytecode(String name) throws IOException {
        ClassNode result = new ClassNode();
        new ClassReader(name).accept(result, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return result;
    }

    @Test
    void switchAccessorTargetsExistInTheActualLdlibDependency() throws IOException {
        ClassNode widget = bytecode("com.lowdragmc.lowdraglib.gui.widget.SwitchWidget");
        for (String field : new String[]{"baseTexture", "pressedTexture"}) {
            assertTrue(widget.fields.stream().anyMatch(f -> f.name.equals(field)
                    && f.desc.equals("Lcom/lowdragmc/lowdraglib/gui/texture/IGuiTexture;")), field);
        }
    }

    @Test
    void configuredUiLibraryExportsClearAndSmokeGlassEntryPoints() throws IOException {
        ClassNode texture = bytecode("com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture");
        for (String method : new String[]{"clearPanel", "clearButtonOverlay", "smokePanel", "smokeButtonOverlay"}) {
            assertTrue(texture.methods.stream().anyMatch(m -> m.name.equals(method)
                    && m.desc.equals("()Lcom/dishanhai/sh_better_ui/compat/ldlib/GlassPanelTexture;")), method);
        }
        ClassNode colors = bytecode("com.dishanhai.sh_better_ui.client.glass.GlassColors");
        assertTrue(colors.fields.stream().anyMatch(f -> f.name.equals("CLEAR")));
        assertTrue(colors.fields.stream().anyMatch(f -> f.name.equals("SMOKE")));
        ClassNode surface = bytecode("com.dishanhai.sh_better_ui.client.glass.GlassSurface");
        assertTrue(surface.methods.stream().anyMatch(m -> m.name.equals("smokeControl")
                && m.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V")));
        ClassNode tooltip = bytecode("com.dishanhai.sh_better_ui.client.glass.GlassTooltip");
        assertTrue(tooltip.methods.stream().anyMatch(m -> m.name.equals("render")
                && m.desc.equals("(Lnet/minecraftforge/client/event/RenderTooltipEvent$Pre;Z)V")));
    }

    @Test
    void productionDependencyRejectsTheOldUiJarBeforeAWorldCanOpen() throws Exception {
        String metadata = Files.readString(Path.of("build/resources/main/META-INF/mods.toml"));
        var blocks = Pattern.compile("(?s)\\[\\[dependencies\\.gt_shanhai]](.*?)(?=\\n\\[|\\z)")
                .matcher(metadata);
        String dependency = null;
        while (blocks.find()) {
            if (blocks.group(1).contains("modId=\"sh_better_ui\"")) dependency = blocks.group(1);
        }
        assertTrue(dependency != null, "SH-Better-UI must be a declared dependency");
        var requirement = Pattern.compile("versionRange=\"([^\"]+)\"").matcher(dependency);
        assertTrue(requirement.find());
        VersionRange range = VersionRange.createFromVersionSpec(requirement.group(1));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("1.0.0")),
                "The deployed 1.0.0 jar lacks smokePanel and must be rejected during mod loading");
        assertFalse(range.containsVersion(new DefaultArtifactVersion("1.0.2")),
                "Shop smoke themes require the GlassTheme.SMOKE API from UI library 1.0.3");

        Class<?> texture = Class.forName("com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture", false,
                getClass().getClassLoader());
        Path library = Path.of(texture.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(library.toFile())) {
            String libraryMetadata = new String(jar.getInputStream(jar.getJarEntry("META-INF/mods.toml"))
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var version = Pattern.compile("(?m)^version=\"([^\"]+)\"").matcher(libraryMetadata);
            assertTrue(version.find());
            assertTrue(range.containsVersion(new DefaultArtifactVersion(version.group(1))),
                    "The compiled UI library must satisfy the production dependency");
        }
    }
}
