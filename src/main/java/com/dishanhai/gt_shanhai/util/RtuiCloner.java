package com.dishanhai.gt_shanhai.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/**
 * 从 jar 内模板 .rtui 克隆自定义配方 UI 文件 (NBT 格式)
 * 用法: RtuiCloner <templateJar> <newRecipeType> <output.rtui>
 */
public class RtuiCloner {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: RtuiCloner <templateJar> <newRecipeType> <output.rtui>");
            return;
        }
        String templateJar = args[0];
        String newType = args[1];
        String outputPath = args[2];

        // Extract aggregation_device.rtui from jar as template
        CompoundTag root = null;
        try (var jar = new JarFile(templateJar)) {
            var entry = jar.getJarEntry("assets/gtceu/ui/recipe_type/cosmos_simulation.rtui");
            if (entry == null) {
                System.err.println("Template not found in jar: aggregation_device.rtui");
                return;
            }
            try (var in = jar.getInputStream(entry)) {
                root = NbtIo.read(new DataInputStream(in));
            }
        }
        if (root == null) {
            System.err.println("Failed to read template");
            return;
        }

        root.putString("recipe_type", newType);
        System.out.println("Set recipe_type -> " + newType);

        Path out = Path.of(outputPath);
        Files.createDirectories(out.getParent());
        try (var dos = new DataOutputStream(new FileOutputStream(outputPath))) {
            NbtIo.write(root, dos);
        }
        System.out.println("Written: " + outputPath);
    }
}
