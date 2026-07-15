package com.dishanhai.gt_shanhai.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiGTRecipeQuery;
import com.dishanhai.gt_shanhai.api.DShanhaiMaterialCounter;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public class DShanhaiCommands {

    private static final String CONFIG_PATH = "kubejs/data/shanhai_recipe_load_config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配方类型补全提供者 — 从 GTRegistries.RECIPE_TYPES 读取，列出完整命名空间:路径 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RECIPE_TYPES = (ctx, builder) -> {
        GTRegistries.RECIPE_TYPES.forEach(type -> {
            if (type != null && type.registryName != null) {
                builder.suggest(type.registryName.toString());
            }
        });
        return builder.buildFuture();
    };

    /**
     * 智能命名空间解析。
     * 依次尝试: 已含冒号(直接返回) → gtceu → gtlcore → gtladditions → gt_shanhai → minecraft
     * 均失败则回退到 gtceu 前缀。
     */
    private static String resolveRecipeType(String type) {
        if (type == null || type.isEmpty()) return type;
        if (type.indexOf(':') >= 0) return type;
        String[] namespaces = {"gtceu", "gtlcore", "gtladditions", "gt_shanhai", "minecraft"};
        for (String ns : namespaces) {
            String candidate = ns + ":" + type;
            if (GTRegistries.RECIPE_TYPES.get(new ResourceLocation(candidate)) != null) {
                return candidate;
            }
        }
        return "gtceu:" + type;
    }

    /** 预设名补全提供者 — 从 presets/ 目录读取 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PRESETS = (ctx, builder) -> {
        for (String name : DShanhaiRecipeModifierAPI.listPresets()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MULTIBLOCKS = (ctx, builder) -> {
        GTRegistries.MACHINES.forEach(def -> {
            if (def instanceof MultiblockMachineDefinition) {
                var block = def.getBlock();
                if (block != null) {
                    var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
                    if (id != null) builder.suggest(id.toString());
                }
            }
        });
        return builder.buildFuture();
    };

    /** 剥离名称两侧的单引号/双引号，避免中文名被当作字符串字面量 */
    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char first = s.charAt(0), last = s.charAt(s.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 检查预设文件是否已存在 */
    private static boolean fileExistsInPresets(String name) {
        return new java.io.File("config/gt_shanhai/presets", name + ".json").exists();
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> recipeTypeArg(String name) {
        return Commands.argument(name, StringArgumentType.string())
                .suggests(SUGGEST_RECIPE_TYPES);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> greedyRecipeTypeArg(String name) {
        return Commands.argument(name, StringArgumentType.greedyString())
                .suggests(SUGGEST_RECIPE_TYPES);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var cmd = Commands.literal("shanhai")
                .then(recipeCommand())
                .then(gtQueryCommand())
                .then(materialsCommand("materials"))
                .then(sdaCommand("sda"))
                .then(shopCommand("shop"))
                .then(cacheCommand());

        event.getDispatcher().register(cmd);
        // 中文别名
        var cn剥离 = Commands.literal("剥离")
                .then(recipeTypeArg("type")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> execStrip(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"),
                                        StringArgumentType.getString(ctx, "item"), true, false, ""))
                                .then(Commands.literal("输入")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"), true, false, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), true, false,
                                                        StringArgumentType.getString(ctx, "recipeId"))))
                                        .then(Commands.literal("流体")
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), true, true, ""))
                                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "item"), true, true,
                                                                StringArgumentType.getString(ctx, "recipeId"))))))
                                .then(Commands.literal("输出")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"), false, false, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), false, false,
                                                        StringArgumentType.getString(ctx, "recipeId"))))
                                        .then(Commands.literal("流体")
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), false, true, ""))
                                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "item"), false, true,
                                                                StringArgumentType.getString(ctx, "recipeId"))))))
                                .then(Commands.literal("流体")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"), true, true, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), true, true,
                                                        StringArgumentType.getString(ctx, "recipeId")))))));
        var cn恢复 = Commands.literal("恢复")
            .executes(ctx -> {
                DShanhaiRecipeModifierAPI.clearAll();
                ctx.getSource().sendSuccess(msg("§b[山海] 已恢复所有剥离和替换规则"), false);
                return 1;
            })
            .then(recipeTypeArg("type")
                    .executes(ctx -> {
                        String type = StringArgumentType.getString(ctx, "type");
                        String fullType = resolveRecipeType(type);
                        boolean removed = DShanhaiRecipeModifierAPI.removeStripRules(fullType);
                        boolean repRemoved = DShanhaiRecipeModifierAPI.removeReplaceRules(fullType);
                        ctx.getSource().sendSuccess(msg("§b[山海] " + ((removed || repRemoved) ? "已恢复" : "未找到") + " §f" + fullType + " §b的规则"), false);
                        return 1;
                    }));
        var cn重载 = Commands.literal("重载")
                .executes(ctx -> {
                    DShanhaiRecipeModifierAPI.reloadStripRules();
                    ctx.getSource().sendSuccess(msg("§b[山海] 已从文件重新加载剥离规则"), false);
                    return 1;
                });
        var cn替换 = Commands.literal("替换")
                // 不消耗模式
                .then(Commands.literal("不消耗")
                        .then(recipeTypeArg("type")
                                .then(Commands.argument("oldItem", StringArgumentType.string())
                                        .then(Commands.argument("newItem", StringArgumentType.string())
                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "oldItem"), false,
                                                        StringArgumentType.getString(ctx, "newItem"), false, true))
                                                .then(Commands.literal("流体")
                                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "oldItem"), false,
                                                                StringArgumentType.getString(ctx, "newItem"), true, true))))
                                        .then(Commands.literal("流体")
                                                .then(Commands.argument("newItem", StringArgumentType.string())
                                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "oldItem"), true,
                                                                StringArgumentType.getString(ctx, "newItem"), true, true))
                                                        .then(Commands.literal("物品")
                                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "type"),
                                                                        StringArgumentType.getString(ctx, "oldItem"), true,
                                                                        StringArgumentType.getString(ctx, "newItem"), false, true))))))))
                // 消耗模式
                .then(Commands.literal("消耗")
                        .then(recipeTypeArg("type")
                                .then(Commands.argument("oldItem", StringArgumentType.string())
                                        .then(Commands.argument("newItem", StringArgumentType.string())
                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "oldItem"), false,
                                                        StringArgumentType.getString(ctx, "newItem"), false, false))
                                                .then(Commands.literal("流体")
                                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "oldItem"), false,
                                                                StringArgumentType.getString(ctx, "newItem"), true, false))))
                                        .then(Commands.literal("流体")
                                                .then(Commands.argument("newItem", StringArgumentType.string())
                                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "oldItem"), true,
                                                                StringArgumentType.getString(ctx, "newItem"), true, false))
                                                        .then(Commands.literal("物品")
                                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "type"),
                                                                        StringArgumentType.getString(ctx, "oldItem"), true,
                                                                        StringArgumentType.getString(ctx, "newItem"), false, false))))))))
                // 电路模式：替换为 programmed_circuit
                .then(Commands.literal("电路")
                        .then(recipeTypeArg("type")
                                .then(Commands.argument("oldItem", StringArgumentType.string())
                                        .then(Commands.argument("circuitNumber", IntegerArgumentType.integer(0, 32))
                                                .executes(ctx -> execReplaceCircuit(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "oldItem"),
                                                        IntegerArgumentType.getInteger(ctx, "circuitNumber")))))))
                // 默认：自动检测旧物品位置
                .then(recipeTypeArg("type")
                        .then(Commands.argument("oldItem", StringArgumentType.string())
                                .then(Commands.argument("newItem", StringArgumentType.string())
                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "oldItem"), false,
                                                StringArgumentType.getString(ctx, "newItem"), false, null))
                                        .then(Commands.literal("流体")
                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "oldItem"), false,
                                                        StringArgumentType.getString(ctx, "newItem"), true, null))))
                                .then(Commands.literal("流体")
                                        .then(Commands.argument("newItem", StringArgumentType.string())
                                                .executes(ctx -> execReplace(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "oldItem"), true,
                                                        StringArgumentType.getString(ctx, "newItem"), true, null))
                                                .then(Commands.literal("物品")
                                                        .executes(ctx -> execReplace(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "oldItem"), true,
                                                                StringArgumentType.getString(ctx, "newItem"), false, null)))))));
        var cn配方 = Commands.literal("配方")
                .then(Commands.literal("列表").executes(ctx -> listRecipes(ctx.getSource())))
                .then(Commands.literal("删除")
                        .then(recipeTypeArg("type")
                                .then(Commands.argument("item", StringArgumentType.string())
                                        .executes(ctx -> execRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"), false))
                                        .then(Commands.literal("保留输出")
                                                .executes(ctx -> execRemove(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"), true))))));
        // 构造保存分支：/山海 配方 预设 保存 <name> [type]
        var saveArg = Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_PRESETS);
        saveArg.executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            boolean ok = DShanhaiRecipeModifierAPI.savePreset(name);
            String reason = fileExistsInPresets(name) ? "文件已存在，用 -f 覆盖" : "无规则";
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已保存: " + name : "§c失败: " + reason)), false);
            return ok ? 1 : 0;
        });
        var typeSaveArg = recipeTypeArg("type");
        typeSaveArg.executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            String rawType = StringArgumentType.getString(ctx, "type");
            String type = rawType.contains(":") ? rawType : "gtceu:" + rawType;
            boolean ok = DShanhaiRecipeModifierAPI.savePreset(name, type, "");
            String reason = fileExistsInPresets(name) ? "文件已存在，用 -f 覆盖" : "无规则";
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已保存[" + type + "]: " + name : "§c失败[" + type + "]: " + reason)), false);
            return ok ? 1 : 0;
        });
        saveArg.then(typeSaveArg);
        var typeForceNode = Commands.literal("-f").executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            String type = StringArgumentType.getString(ctx, "type");
            type = type.contains(":") ? type : "gtceu:" + type;
            boolean ok = DShanhaiRecipeModifierAPI.savePreset(name, type, "", true);
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已覆盖[" + type + "]: " + name : "失败")), false);
            return ok ? 1 : 0;
        });
        typeSaveArg.then(typeForceNode);
        var forceNode = Commands.literal("-f").executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            boolean ok = DShanhaiRecipeModifierAPI.savePreset(name, "", "", true);
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已覆盖保存: " + name : "失败")), false);
            return ok ? 1 : 0;
        }).then(recipeTypeArg("type").executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            String type = StringArgumentType.getString(ctx, "type");
            type = type.contains(":") ? type : "gtceu:" + type;
            boolean ok = DShanhaiRecipeModifierAPI.savePreset(name, type, "", true);
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已覆盖[" + type + "]: " + name : "失败")), false);
            return ok ? 1 : 0;
        }));
        saveArg.then(forceNode);
        var cnSave = Commands.literal("保存").then(saveArg);

        // 构造加载分支：/山海 配方 预设 加载 <name> [覆盖] [type]
        var loadArg = Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_PRESETS);
        loadArg.executes(ctx -> {
            String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
            boolean ok = DShanhaiRecipeModifierAPI.loadPreset(name);
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已追加预设: " + name : "不存在: " + name)), false);
            return ok ? 1 : 0;
        });
        var overwriteNode = Commands.literal("覆盖");
        overwriteNode.executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            boolean ok = DShanhaiRecipeModifierAPI.loadPreset(name, true, "");
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已覆盖加载: " + name : "不存在: " + name)), false);
            return ok ? 1 : 0;
        });
        var typeLoadArg = recipeTypeArg("type");
        typeLoadArg.executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            String rawType = StringArgumentType.getString(ctx, "type");
            String type = rawType.contains(":") ? rawType : "gtceu:" + rawType;
            boolean ok = DShanhaiRecipeModifierAPI.loadPreset(name, false, type);
            ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已加载[" + type + "]: " + name : "不存在")), false);
            return ok ? 1 : 0;
        });
        loadArg.then(overwriteNode);
        loadArg.then(typeLoadArg);
        var cnLoad = Commands.literal("加载").then(loadArg);

        var cn预设 = Commands.literal("预设")
                .then(cnSave)
                .then(cnLoad)
                .then(Commands.literal("删除")
                        .then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_PRESETS)
                                .executes(ctx -> {
                                    String name = stripQuotes(StringArgumentType.getString(ctx, "name"));
                                    boolean ok = DShanhaiRecipeModifierAPI.deletePreset(name);
                                    ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已删除预设: " + name : "预设不存在: " + name)), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("列表")
                        .executes(ctx -> {
                            String[] presets = DShanhaiRecipeModifierAPI.listPresets();
                            if (presets.length == 0) {
                                ctx.getSource().sendSuccess(msg("§7暂无预设文件"), false);
                            } else {
                                ctx.getSource().sendSuccess(msg("§b[山海] 预设列表 (" + presets.length + "个): §f" + String.join(", ", presets)), false);
                            }
                            return 1;
                        }));
        // 删除配置：清理指定类型的 strip/replace 字段
        var cn删除配置 = Commands.literal("删除配置")
                .then(recipeTypeArg("type")
                        .executes(ctx -> { // 删除该类型的全部规则
                            String type = StringArgumentType.getString(ctx, "type");
                            type = type.contains(":") ? type : "gtceu:" + type;
                            boolean ok1 = DShanhaiRecipeModifierAPI.removeStripRules(type);
                            boolean ok2 = DShanhaiRecipeModifierAPI.removeReplaceRules(type);
                            ctx.getSource().sendSuccess(msg("§b[山海] 已删除[" + type + "] " +
                                    (ok1 ? "剥离" : "") + (ok1 && ok2 ? "+" : "") + (ok2 ? "替换" : "") +
                                    ((!ok1 && !ok2) ? "无规则" : "规则")), false);
                            return 1;
                        })
                        .then(Commands.literal("strip")
                                .executes(ctx -> {
                                    String type = StringArgumentType.getString(ctx, "type");
                                    type = type.contains(":") ? type : "gtceu:" + type;
                                    boolean ok = DShanhaiRecipeModifierAPI.removeStripRules(type);
                                    ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已删除[" + type + "]剥离规则" : "[" + type + "]无剥离规则")), false);
                                    return ok ? 1 : 0;
                                }))
                        .then(Commands.literal("replace")
                                .executes(ctx -> {
                                    String type = StringArgumentType.getString(ctx, "type");
                                    type = type.contains(":") ? type : "gtceu:" + type;
                                    boolean ok = DShanhaiRecipeModifierAPI.removeReplaceRules(type);
                                    ctx.getSource().sendSuccess(msg("§b[山海] " + (ok ? "已删除[" + type + "]替换规则" : "[" + type + "]无替换规则")), false);
                                    return ok ? 1 : 0;
                                })));
        var cn删除ID = removeIdCNCommand();
        var cn模式 = replacePatternCNCommand();
        cn配方 = cn配方
                .then(cn删除配置)
                .then(cn删除ID)
                .then(cn模式)
                .then(cn剥离)
                .then(cn恢复)
                .then(cn重载)
                .then(cn替换)
                .then(cn预设);

        var cnGT = Commands.literal("GT")
                .then(Commands.literal("列表").executes(ctx -> gtListTypes(ctx.getSource())))
                .then(Commands.literal("查询")
                        .then(greedyRecipeTypeArg("type")
                                .executes(ctx -> gtQueryRecipes(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type")))))
                .then(Commands.literal("搜索")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> gtSearchItem(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item")))));

        event.getDispatcher().register(
                Commands.literal("山海")
                        .requires(s -> s.hasPermission(2))
                        .then(cn配方)
                        .then(cnGT)
                        .then(materialsCommand("材料"))
                        .then(sdaCommand("SDA"))
                        .then(shopEditPermCommand()));
        // 商店对所有玩家开放（不加权限门槛）
        event.getDispatcher().register(shopCommand("商店"));
    }

    // ===== 商店编辑权限（/山海 商店 授权|取消授权|授权列表）=====

    /** 商店编辑白名单管理指令（OP 专用）。授予后非 OP 玩家亦可在界面内编辑商店。 */
    private static LiteralArgumentBuilder<CommandSourceStack> shopEditPermCommand() {
        return Commands.literal("商店")
                .then(Commands.literal("授权")
                        .then(Commands.argument("玩家", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> {
                                    net.minecraft.server.level.ServerPlayer target =
                                            net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "玩家");
                                    var perm = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.get(ctx.getSource().getServer());
                                    boolean ok = perm.grant(target.getUUID());
                                    ctx.getSource().sendSuccess(msg(ok
                                            ? "§b[山海商店] §a已授予 §f" + target.getName().getString() + " §a编辑权限"
                                            : "§7[山海商店] " + target.getName().getString() + " 已有编辑权限"), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("取消授权")
                        .then(Commands.argument("玩家", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> {
                                    net.minecraft.server.level.ServerPlayer target =
                                            net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "玩家");
                                    var perm = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.get(ctx.getSource().getServer());
                                    boolean ok = perm.revoke(target.getUUID());
                                    ctx.getSource().sendSuccess(msg(ok
                                            ? "§b[山海商店] §e已撤销 §f" + target.getName().getString() + " §e的编辑权限"
                                            : "§7[山海商店] " + target.getName().getString() + " 本就无编辑权限"), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("授权列表")
                        .executes(ctx -> {
                            var perm = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.get(ctx.getSource().getServer());
                            var ids = perm.all();
                            if (ids.isEmpty()) {
                                ctx.getSource().sendSuccess(msg("§7[山海商店] 白名单为空（仅 OP 可编辑）"), false);
                                return 1;
                            }
                            var server = ctx.getSource().getServer();
                            StringBuilder sb = new StringBuilder("§b[山海商店] 编辑白名单 (§f" + ids.size() + "§b):\n");
                            for (java.util.UUID id : ids) {
                                var prof = server.getProfileCache() != null ? server.getProfileCache().get(id).orElse(null) : null;
                                sb.append("§7- §f").append(prof != null ? prof.getName() : id.toString()).append("\n");
                            }
                            ctx.getSource().sendSuccess(msg(sb.toString()), false);
                            return 1;
                        }))
                .then(Commands.literal("作弊")
                        .executes(ctx -> {
                            net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayer();
                            if (p == null) {
                                ctx.getSource().sendSuccess(msg("§c此命令须由玩家执行"), false);
                                return 0;
                            }
                            boolean on = com.dishanhai.gt_shanhai.common.shop.ShopCheatMode.toggle(p.getUUID());
                            ctx.getSource().sendSuccess(msg(on
                                    ? "§d[山海商店] §a已开启直接获取模式 §7（购买不扣成本、直接到手；重启后自动关闭）"
                                    : "§d[山海商店] §e已关闭直接获取模式 §7（恢复正常扣费购买）"), false);
                            return 1;
                        }))
                .then(Commands.literal("编辑")
                        .executes(ctx -> {
                            net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayer();
                            if (p == null) {
                                ctx.getSource().sendSuccess(msg("§c此命令须由玩家执行"), false);
                                return 0;
                            }
                            boolean on = com.dishanhai.gt_shanhai.common.shop.ShopEditMode.toggle(p.getUUID());
                            ctx.getSource().sendSuccess(msg(on
                                    ? "§b[山海商店] §a已开启编辑模式 §7（可新增/删除商品；重启后自动关闭）"
                                    : "§b[山海商店] §e已关闭编辑模式 §7（新增/删除商品按钮将隐藏）"), false);
                            return 1;
                        }));
    }

    // ===== 商店 =====

    /** 构造商店命令节点。玩家执行时若手持/背包内有钱包则打开商店界面。
     *  管理子命令（add/remove/reload）需 OP 权限。 */
    private static LiteralArgumentBuilder<CommandSourceStack> shopCommand(String name) {
        return Commands.literal(name)
                .executes(ctx -> execOpenShop(ctx.getSource()))
                // 新增商品：/商店 add <goods> <count> <currency> <price> [category]
                .then(Commands.literal("add")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("goods", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("currency", StringArgumentType.string())
                                                .then(Commands.argument("price", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> execShopAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "goods"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "currency"),
                                                                IntegerArgumentType.getInteger(ctx, "price"),
                                                                com.dishanhai.gt_shanhai.common.shop.ShopEntry.DEFAULT_CATEGORY))
                                                        .then(Commands.argument("category", StringArgumentType.string())
                                                                .executes(ctx -> execShopAdd(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "goods"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "currency"),
                                                                        IntegerArgumentType.getInteger(ctx, "price"),
                                                                        StringArgumentType.getString(ctx, "category")))))))))
                // 删除商品：/商店 remove <goods>（删除第一个匹配该 goods 的条目）
                .then(Commands.literal("remove")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("goods", StringArgumentType.string())
                                .executes(ctx -> execShopRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "goods")))))
                // 重载：/商店 reload（从 shop.json 重新加载）
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> execShopReload(ctx.getSource())));
    }

    private static int execShopAdd(CommandSourceStack source, String goods, int count,
                                   String currency, int price, String category) {
        try {
            ResourceLocation goodsId = new ResourceLocation(goods);
            ResourceLocation currencyId = new ResourceLocation(currency);
            if (!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(goodsId)) {
                source.sendSuccess(msg("§c商品物品不存在: " + goods), false);
                return 0;
            }
            if (!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(currencyId)) {
                source.sendSuccess(msg("§c货币物品不存在: " + currency), false);
                return 0;
            }
            com.dishanhai.gt_shanhai.common.shop.ShopConfig.addEntry(
                    new com.dishanhai.gt_shanhai.common.shop.ShopEntry(goodsId, count, currencyId, price, category));
            source.sendSuccess(msg("§b[山海商店] §a已新增商品 §f" + count + "x " + goods
                    + " §7@ §e" + price + " " + currency + " §7[" + category + "]"), false);
            return 1;
        } catch (Exception e) {
            source.sendSuccess(msg("§c新增失败: " + e.getMessage()), false);
            return 0;
        }
    }

    private static int execShopRemove(CommandSourceStack source, String goods) {
        try {
            ResourceLocation goodsId = new ResourceLocation(goods);
            var target = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getEntries().stream()
                    .filter(e -> e.getGoodsId().equals(goodsId))
                    .findFirst().orElse(null);
            if (target == null) {
                source.sendSuccess(msg("§c未找到商品: " + goods), false);
                return 0;
            }
            boolean ok = com.dishanhai.gt_shanhai.common.shop.ShopConfig.removeEntry(target);
            source.sendSuccess(msg(ok ? "§b[山海商店] §a已删除商品 §f" + goods
                    : "§c删除失败: " + goods), false);
            return ok ? 1 : 0;
        } catch (Exception e) {
            source.sendSuccess(msg("§c删除失败: " + e.getMessage()), false);
            return 0;
        }
    }

    private static int execShopReload(CommandSourceStack source) {
        com.dishanhai.gt_shanhai.common.shop.ShopConfig.reload();
        com.dishanhai.gt_shanhai.common.shop.ShopConfig.syncLimitsFromSave(source.getServer());
        int n = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getEntries().size();
        source.sendSuccess(msg("§b[山海商店] §a已重载商品清单，共 §f" + n + " §a个"), false);
        return 1;
    }

    private static int execOpenShop(CommandSourceStack source) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(msg("§c此命令须由玩家执行"), false);
            return 0;
        }
        // 优先主手，其次副手；命中钱包则发包让客户端打开 ShopScreen
        boolean canEdit = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player);
        boolean catalogEditUnlocked = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEditCatalog(player);
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem()
                    instanceof com.dishanhai.gt_shanhai.common.item.WalletItem) {
                com.dishanhai.gt_shanhai.network.ShanhaiNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                        new com.dishanhai.gt_shanhai.network.ShopOpenPacket(canEdit, catalogEditUnlocked,
                                com.dishanhai.gt_shanhai.common.shop.ShopConfig.manifest()));
                return 1;
            }
        }
        source.sendSuccess(msg("§c请先手持 §6山海钱包 §c再执行（/give @s gt_shanhai:wallet）"), false);
        return 0;
    }

    // ===== 材料统计 =====

    private static LiteralArgumentBuilder<CommandSourceStack> materialsCommand(String name) {
        return Commands.literal(name)
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("machineId", StringArgumentType.string())
                        .suggests(SUGGEST_MULTIBLOCKS)
                        .executes(ctx -> execCountMaterials(ctx.getSource(),
                                StringArgumentType.getString(ctx, "machineId"))))
                .then(Commands.literal("all")
                        .executes(ctx -> execCountAllMaterials(ctx.getSource())));
    }

    private static int execCountMaterials(CommandSourceStack source, String machineId) {
        var def = GTRegistries.MACHINES.get(new ResourceLocation(machineId));
        if (def == null) def = GTRegistries.MACHINES.get(new ResourceLocation("gtceu", machineId));
        if (def == null) { source.sendSuccess(msg("§c未找到: " + machineId), false); return 0; }
        if (!(def instanceof MultiblockMachineDefinition mdef)) { source.sendSuccess(msg("§c不是多方块: " + machineId), false); return 0; }
        var materials = DShanhaiMaterialCounter.countMaterials(mdef);
        source.sendSuccess(msg(DShanhaiMaterialCounter.formatMaterialList(machineId, materials)), false);
        return 1;
    }

    private static int execCountAllMaterials(CommandSourceStack source) {
        java.io.File outDir = new java.io.File("kubejs/data"); outDir.mkdirs();
        java.io.File outFile = new java.io.File(outDir, "shanhai_multiblock_materials.md");
        var sb = new StringBuilder("# 全部多方块机器材料清单\n\n> ").append(new java.util.Date()).append("\n\n---\n\n");
        AtomicInteger machineCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        GTRegistries.MACHINES.forEach(def -> {
            if (def instanceof MultiblockMachineDefinition mdef) {
                String id = "unknown";
                var block = mdef.getBlock();
                if (block != null) { var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block); if (key != null) id = key.toString(); }
                try { sb.append(DShanhaiMaterialCounter.toMarkdown(id, DShanhaiMaterialCounter.countMaterials(mdef))).append("\n"); machineCount.incrementAndGet(); }
                catch (Exception e) { errors.add(id + ": " + e.getMessage()); }
            }
        });
        if (!errors.isEmpty()) { sb.append("\n---\n\n## 错误\n\n"); for (String err : errors) sb.append("- ").append(err).append("\n"); }
        int total = machineCount.get();
        sb.append("\n---\n\n**共 ").append(total).append(" 台**\n");
        try (var w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) { w.write(sb.toString()); }
        catch (Exception e) { source.sendSuccess(msg("§c写入失败: " + e.getMessage()), false); return 0; }
        source.sendSuccess(msg("§b已导出 §f" + total + " §b台到 §7kubejs/data/shanhai_multiblock_materials.md"), false);
        return 1;
    }

    // ===== 按ID删除 =====

    private static int execRemoveById(CommandSourceStack source, String type, String recipeRegex) {
        String fullType = resolveRecipeType(type);
        int count = DShanhaiRecipeModifierAPI.deleteRecipesById(fullType, recipeRegex);
        source.sendSuccess(msg("§b删除 §f" + fullType + " §7regex=§f" + recipeRegex + " §7→ §f" + count + " §b个配方"), false);
        return count > 0 ? 1 : 0;
    }

    // ===== 模式替换 =====

    private static int execReplacePattern(CommandSourceStack source, String type, String oldRegex, String newPattern) {
        String fullType = resolveRecipeType(type);
        int count = DShanhaiRecipeModifierAPI.replacePatternRecipes(fullType, oldRegex, newPattern);
        source.sendSuccess(msg("§b模式替换 §f" + fullType + " §7regex=§f" + oldRegex + " §7→ §f" + newPattern + " §7(§f" + count + " §7个配方)"), false);
        return count > 0 ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> gtQueryCommand() {
        return Commands.literal("gt")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ctx -> gtListTypes(ctx.getSource())))
                .then(Commands.literal("query")
                        .then(greedyRecipeTypeArg("type")
                                .executes(ctx -> {
                                    String type = StringArgumentType.getString(ctx, "type");
                                    return gtQueryRecipes(ctx.getSource(), type);
                                })))
                .then(Commands.literal("search")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String item = StringArgumentType.getString(ctx, "item");
                                    return gtSearchItem(ctx.getSource(), item);
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cacheCommand() {
        return Commands.literal("cache")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats")
                        .executes(ctx -> cacheStats(ctx.getSource())))
                .then(Commands.literal("reset")
                        .executes(ctx -> cacheResetStats(ctx.getSource())));
    }

    private static int cacheStats(CommandSourceStack source) {
        boolean enabled = DShanhaiConfig.COMMON.runtimeRecipeCacheDiagnostics.get();
        var stats = com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCache.getDiagnosticsStats();
        var sb = new StringBuilder("§6===== 运行期配方查找缓存诊断 =====\n");
        sb.append("§e诊断开关: ").append(enabled ? "§a开启" : "§c关闭（计数恒为 0，改配置 runtime_recipe_cache.diagnosticsEnabled 后重进世界生效）").append("\n");
        sb.append("§7- §fhit(命中): §a").append(stats.get("hit")).append("\n");
        sb.append("§7- §fnegativeHit(命中空结果): §e").append(stats.get("negativeHit")).append("\n");
        sb.append("§7- §fmiss(未命中): §c").append(stats.get("miss")).append("\n");
        sb.append("§7- §fclear(缓存清空次数): §7").append(stats.get("clear")).append("\n");
        sb.append("§7- §f当前缓存条目数: §b").append(stats.get("cacheSize")).append("\n");
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static int cacheResetStats(CommandSourceStack source) {
        com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCache.resetDiagnosticsStats();
        source.sendSuccess(msg("§a已清零运行期配方查找缓存诊断计数（缓存内容本身未清空）"), false);
        return 1;
    }

    private static int gtListTypes(CommandSourceStack source) {
        DShanhaiGTRecipeQuery.buildCache();
        var stats = DShanhaiGTRecipeQuery.getStats();
        var sb = new StringBuilder("§6===== GT 配方类型列表 =====\n");
        sb.append("§e共 ").append(stats.get("totalTypes")).append(" 个类型, ")
                .append(stats.get("totalRecipes")).append(" 个配方\n");

        @SuppressWarnings("unchecked")
        Map<String, Integer> breakdown = (Map<String, Integer>) stats.get("typeBreakdown");
        int n = 0;
        for (var entry : breakdown.entrySet()) {
            sb.append("§7- §f").append(entry.getKey()).append(" §7(").append(entry.getValue()).append(")\n");
            n++;
            if (n >= 100) { sb.append("§7... 还有 ").append(breakdown.size() - 100).append(" 个类型未显示\n"); break; }
        }
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static int gtQueryRecipes(CommandSourceStack source, String type) {
        DShanhaiGTRecipeQuery.buildCache();
        var recipes = DShanhaiGTRecipeQuery.getRecipesByType(type);
        var sb = new StringBuilder("§6类型: §f").append(type).append(" §7(").append(recipes.size()).append(" 个配方)\n");
        int dc = Math.min(recipes.size(), 30);
        for (int i = 0; i < dc; i++) {
            var r = recipes.get(i);
            var output = r.itemOutputs.isEmpty() ? "" : " §7→ §b" + r.itemOutputs.get(0);
            sb.append("§7").append(i + 1).append(". §e").append(r.eut).append("EU/t §a").append(r.duration).append("t").append(output).append("\n");
        }
        if (recipes.size() > dc) sb.append("§e... 还有 ").append(recipes.size() - dc).append(" 个配方未显示\n");
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static int gtSearchItem(CommandSourceStack source, String item) {
        DShanhaiGTRecipeQuery.buildCache();
        var byInput = DShanhaiGTRecipeQuery.findRecipesByInput(item);
        var byOutput = DShanhaiGTRecipeQuery.findRecipesByOutput(item);
        var sb = new StringBuilder("§6===== 搜索: §f").append(item).append(" §6=====\n");
        sb.append("§e作为输入: §f").append(byInput.size()).append(" 个配方\n");
        sb.append("§e作为输出: §f").append(byOutput.size()).append(" 个配方\n");

        int dc = Math.min(byOutput.size(), 10);
        for (int i = 0; i < dc; i++) {
            var r = byOutput.get(i);
            sb.append("§7").append(i + 1).append(". §7[").append(r.typeId).append("] §e").append(r.eut).append("EU/t §a").append(r.duration).append("t\n");
        }
        if (byOutput.size() > 10) sb.append("§e... 还有 ").append(byOutput.size() - 10).append(" 个配方未显示\n");
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> recipeCommand() {
        return Commands.literal("recipe")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ctx -> listRecipes(ctx.getSource())))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .executes(ctx -> {
                                    ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                    return toggleRecipe(ctx.getSource(), id.getPath());
                                })))
                .then(Commands.literal("status")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .executes(ctx -> {
                                    ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                    return statusRecipe(ctx.getSource(), id.getPath());
                                })))
                .then(stripCommand())
                .then(removeCommand())
                .then(Commands.literal("recover")
                        .executes(ctx -> {
                            DShanhaiRecipeModifierAPI.clearAll();
                            ctx.getSource().sendSuccess(msg("§b[山海] 已恢复所有剥离规则（清除）"), false);
                            return 1;
                        })
                        .then(recipeTypeArg("type")
                                .executes(ctx -> {
                                    String type = StringArgumentType.getString(ctx, "type");
                                    String fullType = resolveRecipeType(type);
                                    boolean removed = DShanhaiRecipeModifierAPI.removeStripRules(fullType);
                                    DShanhaiRecipeModifierAPI.saveStripRules();
                                    ctx.getSource().sendSuccess(msg("§b[山海] " + (removed ? "已恢复" : "未找到") + " §f" + fullType + " §b的规则"), false);
                                    return 1;
                                })))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            DShanhaiRecipeModifierAPI.reloadStripRules();
                            ctx.getSource().sendSuccess(msg("§b[山海] 已从文件重新加载剥离规则"), false);
                            return 1;
                        }))
                .then(removeIdCommand())
                .then(replacePatternCommand());
    }

    private static int execStripClear(CommandSourceStack source, String type) {
        if (type == null || type.isEmpty()) {
            DShanhaiRecipeModifierAPI.clearAll();
            source.sendSuccess(msg("§b[山海] 已清除所有剥离规则"), false);
        } else {
            boolean removed = DShanhaiRecipeModifierAPI.removeStripRule(type, "");
            source.sendSuccess(msg("§b[山海] " + (removed ? "已清除" : "未找到") + " §f" + type + " §b的剥离规则"), false);
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stripCommand() {
        return Commands.literal("strip")
                .then(recipeTypeArg("type")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> {
                                    String type = StringArgumentType.getString(ctx, "type");
                                    String item = StringArgumentType.getString(ctx, "item");
                                    return execStrip(ctx.getSource(), type, item, true, false, "");
                                })
                                .then(Commands.literal("input")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"),
                                                true, false, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        true, false,
                                                        StringArgumentType.getString(ctx, "recipeId"))))
                                        .then(Commands.literal("fluid")
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        true, true, ""))
                                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                true, true,
                                                                StringArgumentType.getString(ctx, "recipeId"))))))
                                .then(Commands.literal("output")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"),
                                                false, false, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        false, false,
                                                        StringArgumentType.getString(ctx, "recipeId"))))
                                        .then(Commands.literal("fluid")
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        false, true, ""))
                                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                false, true,
                                                                StringArgumentType.getString(ctx, "recipeId"))))))
                                .then(Commands.literal("fluid")
                                        .executes(ctx -> execStrip(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"),
                                                true, true, ""))
                                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                                .executes(ctx -> execStrip(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        true, true,
                                                        StringArgumentType.getString(ctx, "recipeId")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> removeCommand() {
        return Commands.literal("remove")
                .then(recipeTypeArg("type")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> execRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"),
                                        StringArgumentType.getString(ctx, "item"), false))
                                .then(Commands.literal("reAdd")
                                        .executes(ctx -> execRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "item"), true)))));
    }

    private static int execRemove(CommandSourceStack source, String type, String item, boolean reAdd) {
        String fullType = resolveRecipeType(type);
        int count = DShanhaiRecipeModifierAPI.deleteRecipes(fullType, item, reAdd);
        var sb = new StringBuilder("§b[山海] 已从 §f").append(fullType).append(" §b删除 §f").append(count).append(" §b个配方\n");
        if (reAdd) sb.append("§7已重加去掉 §f").append(item).append(" §7的修改版");
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> removeIdCommand() {
        return Commands.literal("removeId")
                .requires(s -> s.hasPermission(2))
                .then(recipeTypeArg("type")
                        .then(Commands.argument("recipeRegex", StringArgumentType.greedyString())
                                .executes(ctx -> execRemoveById(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"),
                                        StringArgumentType.getString(ctx, "recipeRegex")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> replacePatternCommand() {
        return Commands.literal("replacePattern")
                .requires(s -> s.hasPermission(2))
                .then(recipeTypeArg("type")
                        .then(Commands.argument("oldRegex", StringArgumentType.string())
                                .then(Commands.argument("newPattern", StringArgumentType.greedyString())
                                        .executes(ctx -> execReplacePattern(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "oldRegex"),
                                                StringArgumentType.getString(ctx, "newPattern"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> removeIdCNCommand() {
        return Commands.literal("删除ID")
                .then(recipeTypeArg("type")
                        .then(Commands.argument("recipeRegex", StringArgumentType.greedyString())
                                .executes(ctx -> execRemoveById(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"),
                                        StringArgumentType.getString(ctx, "recipeRegex")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> replacePatternCNCommand() {
        return Commands.literal("模式")
                .then(recipeTypeArg("type")
                        .then(Commands.argument("oldRegex", StringArgumentType.string())
                                .then(Commands.argument("newPattern", StringArgumentType.greedyString())
                                        .executes(ctx -> execReplacePattern(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "oldRegex"),
                                                StringArgumentType.getString(ctx, "newPattern"))))));
    }

    private static int execStrip(CommandSourceStack source, String type, String item,
                                  boolean isInput, boolean isFluid, String recipeId) {
        String fullType = resolveRecipeType(type);

        DShanhaiRecipeModifierAPI.addStripRule(fullType,
                new DShanhaiRecipeModifierAPI.StripEntry(item, isInput, isFluid, recipeId));

        var sb = new StringBuilder("§b[山海] 已注册剥离规则\n");
        sb.append("§7  配方类型: §f").append(fullType).append("\n");
        sb.append("§7  目标: §f").append(item).append("\n");
        sb.append("§7  位置: §f").append(isInput ? "输入" : "输出").append("\n");
        if (!recipeId.isEmpty()) sb.append("§7  配方: §f").append(recipeId);
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static int listRecipes(CommandSourceStack source) {
        JsonObject config = loadConfig();
        if (config == null) {
            source.sendSuccess(msg("§c无法读取配方配置文件"), false);
            return 0;
        }
        int total = config.size();
        int enabled = 0;
        StringBuilder sb = new StringBuilder("§b[山海] 配方列表 (§f" + total + "§b 个):\n");
        int n = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> e : config.entrySet()) {
            String id = e.getKey();
            boolean val = e.getValue().getAsBoolean();
            if (val) enabled++;
            sb.append(val ? " §a✔ " : " §c✘ ").append(id).append("\n");
            n++;
            if (n >= 20) { sb.append("§7... 还有 ").append(total - 20).append(" 个配方"); break; }
        }
        sb.append("§7已启用: §a").append(enabled).append("§7/").append(total);
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static int toggleRecipe(CommandSourceStack source, String id) {
        JsonObject config = loadConfig();
        if (config == null) { source.sendSuccess(msg("§c无法读取配方配置文件"), false); return 0; }

        boolean current = config.has(id) && config.get(id).getAsBoolean();
        config.addProperty(id, !current);
        saveConfig(config);

        source.sendSuccess(msg("§b[山海] " + (!current ? "§a✔ 已启用" : "§c✘ 已禁用") + " §f" + id), false);
        return 1;
    }

    private static int statusRecipe(CommandSourceStack source, String id) {
        JsonObject config = loadConfig();
        if (config == null) { source.sendSuccess(msg("§c无法读取配方配置文件"), false); return 0; }

        boolean exists = config.has(id);
        if (!exists) { source.sendSuccess(msg("§c配方 " + id + " 不存在"), false); return 0; }

        boolean val = config.get(id).getAsBoolean();
        source.sendSuccess(msg("§b[山海] §f" + id + " §7状态: " + (val ? "§a已启用" : "§c已禁用")), false);
        return 1;
    }

    private static JsonObject loadConfig() {
        try {
            Path path = Path.of(".").toRealPath();
            java.io.File f = new java.io.File(path.toFile(), CONFIG_PATH);
            if (!f.exists()) return null;
            try (FileReader r = new FileReader(f)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveConfig(JsonObject config) {
        try {
            Path path = Path.of(".").toRealPath();
            java.io.File f = new java.io.File(path.toFile(), CONFIG_PATH);
            try (FileWriter w = new FileWriter(f)) {
                GSON.toJson(config, w);
            }
        } catch (Exception ignored) {}
    }

    // ===== SDA 命令 =====

    /** 构造 SDA 命令节点，name 可为 "sda" 或 "SDA" */
    private static LiteralArgumentBuilder<CommandSourceStack> sdaCommand(String name) {
        return Commands.literal(name)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("导出")
                        .executes(ctx -> execSdaExport(ctx.getSource())))
                .then(Commands.literal("任务导出")
                        .executes(ctx -> execSdaExportFromQuests(ctx.getSource())))
                .then(Commands.literal("商店导出")
                        .executes(ctx -> execSdaExportFromShop(ctx.getSource())))
                .then(Commands.literal("全部导出")
                        .executes(ctx -> execSdaExportAll(ctx.getSource())))
                .then(Commands.literal("移除")
                        .executes(ctx -> execSdaRemove(ctx.getSource())))
                .then(Commands.literal("列表")
                        .executes(ctx -> execSdaList(ctx.getSource())));
    }

    /**
     * 扫描 config/ftbquests/quests/chapters/*.snbt，提取所有 shanhai_sda_uuid，
     * 仅将这些 UUID 对应的 world/data 内容写入 kubejs/data/sda_uuid_store/。
     * 解决"集合存储器有500个SDA但只想导出任务奖励的那几个"问题。
     */
    private static int execSdaExportFromQuests(CommandSourceStack source) {
        java.util.Set<java.util.UUID> questUuids =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.scanQuestSnbtUuids();
        if (questUuids.isEmpty()) {
            source.sendSuccess(msg("§7[山海] 未在 config/ftbquests/quests/chapters/ 中找到 SDA UUID"), false);
            return 0;
        }
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(source.getServer());
        int exported = 0;
        int empty = 0;
        for (java.util.UUID uuid : questUuids) {
            java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts =
                    data.readCellBigAmounts(uuid);
            if (amounts.isEmpty()) {
                // world/data 里该 UUID 无内容，跳过，不能删已有文件
                empty++;
                continue;
            }
            com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid, amounts);
            exported++;
        }
        source.sendSuccess(msg("§b[山海] 任务导出完成 §7共扫描 §f" + questUuids.size()
                + " §7个 UUID：§a" + exported + " §b个有内容已导出"
                + (empty > 0 ? "§7，" + empty + " 个为空（已跳过）" : "")), false);
        return exported > 0 ? 1 : 0;
    }

    /**
     * 扫描 config/gt_shanhai/shop.json，提取商品 goodsNbt 模板里写死的 shanhai_sda_uuid
     * （商品本体就是提前捕获的固定 SDA 时会有此字段），仅导出这些 UUID 对应的内容——
     * 精确对应「任务导出」在商店场景下的等价物。
     */
    private static int execSdaExportFromShop(CommandSourceStack source) {
        java.util.Set<java.util.UUID> shopUuids =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.scanShopJsonUuids();
        if (shopUuids.isEmpty()) {
            source.sendSuccess(msg("§7[山海] 未在 config/gt_shanhai/shop.json 中找到 SDA UUID"), false);
            return 0;
        }
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(source.getServer());
        int exported = 0;
        int empty = 0;
        for (java.util.UUID uuid : shopUuids) {
            java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts =
                    data.readCellBigAmounts(uuid);
            if (amounts.isEmpty()) {
                empty++;
                continue;
            }
            com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid, amounts);
            exported++;
        }
        source.sendSuccess(msg("§b[山海] 商店导出完成 §7共扫描 §f" + shopUuids.size()
                + " §7个 UUID：§a" + exported + " §b个有内容已导出"
                + (empty > 0 ? "§7，" + empty + " 个为空（已跳过）" : "")), false);
        return exported > 0 ? 1 : 0;
    }

    /**
     * 批量导出 world/data 里<b>当前存在的全部</b> SDA 单元到 kubejs/data/sda_uuid_store/。
     *
     * <p>商店卖出的 SDA 用随机 UUID（{@code ShopPurchase#packAsSda}），不像任务奖励那样
     * 写死在可扫描的配置文件里——shop.json 只存商品模板，不记录任何已成交的 UUID。
     * 因此商店场景没有"正则匹配商店存储"这条路：{@link DShanhaiVirtualCellSavedData}
     * （world/data）本身就是所有 SDA 单元的权威登记表，不分来源，直接全量导出即可覆盖商店、
     * 任务、手搓等一切来源，天然比按来源过滤更完整。</p>
     */
    private static int execSdaExportAll(CommandSourceStack source) {
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(source.getServer());
        java.util.List<String[]> cells = data.listSdaCells();
        if (cells.isEmpty()) {
            source.sendSuccess(msg("§7[山海] world/data 中暂无 SDA 单元"), false);
            return 0;
        }
        int exported = 0;
        for (String[] cell : cells) {
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(cell[0]);
            } catch (Exception ex) {
                continue;
            }
            java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts = data.readCellBigAmounts(uuid);
            if (amounts.isEmpty()) continue;
            com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid, amounts);
            exported++;
        }
        source.sendSuccess(msg("§b[山海] 全部导出完成 §7共 §f" + cells.size()
                + " §7个 SDA 单元，§a" + exported + " §b个已导出/刷新 → §7kubejs/data/sda_uuid_store/"), false);
        return exported > 0 ? 1 : 0;
    }

    /**
     * 手动导出手持 SDA：将当前手持 SDA 的 UUID + 内容写入 kubejs/data/sda_uuid_store/。
     * 只有通过此命令显式导出的 UUID 才会被保存，不会自动大量写入。
     */
    private static int execSdaExport(CommandSourceStack source) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(msg("§c此命令须由玩家执行"), false);
            return 0;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof
                com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem)) {
            source.sendSuccess(msg("§c请手持超级磁盘阵列（SDA）执行此命令"), false);
            return 0;
        }
        net.minecraft.nbt.CompoundTag tag = held.getTag();
        if (tag == null || !tag.hasUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID)) {
            source.sendSuccess(msg("§c该 SDA 尚未初始化，请先放入驱动器触发一次读写"), false);
            return 0;
        }
        java.util.UUID uuid = tag.getUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID);
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(source.getServer());
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts =
                data.readCellBigAmounts(uuid);
        com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid, amounts);
        source.sendSuccess(msg("§b[山海] SDA 已导出 §7" + uuid.toString().substring(0, 8)
                + "… §b共 §f" + amounts.size() + " §b种物品 → §7kubejs/data/sda_uuid_store/"), false);
        return 1;
    }

    /**
     * 从 ContentStore 移除手持 SDA 的导出文件（删除对应 uuid.json）。
     */
    private static int execSdaRemove(CommandSourceStack source) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(msg("§c此命令须由玩家执行"), false);
            return 0;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof
                com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem)) {
            source.sendSuccess(msg("§c请手持超级磁盘阵列（SDA）执行此命令"), false);
            return 0;
        }
        net.minecraft.nbt.CompoundTag tag = held.getTag();
        if (tag == null || !tag.hasUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID)) {
            source.sendSuccess(msg("§c该 SDA 无 UUID"), false);
            return 0;
        }
        java.util.UUID uuid = tag.getUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID);
        boolean had = com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(uuid);
        if (had) {
            com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid,
                    java.util.Collections.emptyMap()); // 写空→触发删除文件
            source.sendSuccess(msg("§b[山海] 已移除 SDA 导出 §7" + uuid.toString().substring(0, 8) + "…"), false);
        } else {
            source.sendSuccess(msg("§7[山海] 该 SDA 未导出，无需移除"), false);
        }
        return had ? 1 : 0;
    }

    /** 列出 world/data 中所有 SDA 单元的 UUID 和物品种类数 */
    private static int execSdaList(CommandSourceStack source) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(server);
        java.util.List<String[]> cells = data.listSdaCells();
        if (cells.isEmpty()) {
            source.sendSuccess(msg("§7[山海] 暂无 SDA 单元"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§b[山海] SDA 单元列表 (§f")
                .append(cells.size()).append("§b 个):\n");
        for (String[] c : cells) {
            // c[0]=uuid, c[1]=totalItems, c[2]=typeCount
            String shortUuid = c[0].substring(0, 8) + "…";
            sb.append("§7  ").append(shortUuid)
              .append(" §7物品总数:§f").append(c[1])
              .append(" §7种类:§f").append(c[2]).append("\n");
        }
        source.sendSuccess(msg(sb.toString()), false);
        return 1;
    }

    private static Supplier<Component> msg(String text) {
        return () -> Component.literal(text);
    }

    private static int execReplace(CommandSourceStack source, String type, String oldItem, boolean oldIsFluid,
                                    String newItem, boolean newIsFluid, Boolean notConsumable) {
        // 解析 "Nx 物品名" 前缀
        int count = 0;
        String actualNewItem = newItem;
        if (newItem.matches("^\\d+x\\s+.+")) {
            int xIdx = newItem.indexOf('x');
            try {
                count = Integer.parseInt(newItem.substring(0, xIdx));
                actualNewItem = newItem.substring(xIdx + 1).trim();
            } catch (NumberFormatException ignored) {}
        }
        String fullType = resolveRecipeType(type);
        DShanhaiRecipeModifierAPI.addReplaceRule(fullType,
                new DShanhaiRecipeModifierAPI.ReplaceEntry(oldItem, actualNewItem, oldIsFluid, newIsFluid, "", count));
        int recipeCount = DShanhaiRecipeModifierAPI.replaceInRecipes(fullType, oldItem, actualNewItem, oldIsFluid, newIsFluid, notConsumable, count, -1, "");
        String countStr = count > 0 ? (" " + count + "x") : "";
        source.sendSuccess(msg("§b[山海] 替换完成 §f" + fullType
                + " §7" + (oldIsFluid ? "流体" : "物品") + " §f" + oldItem + " §7→ §a" + countStr + " " + actualNewItem
                + (newIsFluid ? " §7(流体)" : "") + (notConsumable != null ? (notConsumable ? " §7[不消耗]" : " §7[消耗]") : "")
                + " §7(" + recipeCount + " 个配方)"), false);
        return 1;
    }

    private static int execReplaceCircuit(CommandSourceStack source, String type, String oldItem, int circuitNumber) {
        String fullType = resolveRecipeType(type);
        DShanhaiRecipeModifierAPI.addReplaceRule(fullType,
                new DShanhaiRecipeModifierAPI.ReplaceEntry(oldItem, circuitNumber, ""));
        int recipeCount = DShanhaiRecipeModifierAPI.replaceInRecipes(fullType, oldItem, "gtceu:programmed_circuit", false, false, true, 0, circuitNumber, "");
        source.sendSuccess(msg("§b[山海] 替换完成 §f" + fullType
                + " §7物品 §f" + oldItem + " §7→ §a电路#" + circuitNumber
                + " §7[不消耗] §7(" + recipeCount + " 个配方)"), false);
        return 1;
    }
}
