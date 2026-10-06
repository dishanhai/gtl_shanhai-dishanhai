package com.dishanhai.gt_shanhai.client.holo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * 山海重构 · 全息命令输入框的<b>键盘接管层</b>（一个<b>完全透明</b>的 Screen）。
 *
 * <h2>1. 用户原话（逐字）</h2>
 * <blockquote>
 *   还可以新增一个 cmd 的功能，全息输入框，可以执行cmd的命令，拥有管理员权限
 * </blockquote>
 *
 * <h2>2. 🔴 为什么是"开一个轻量 Screen"，而不是"在渲染层自己接键"</h2>
 * 这是任务书点名要挑一条并写进报告的分叉。挑 Screen 的三条理由，每条都对应一件手搓一定会错的事：
 * <table border="1">
 *   <tr><th>事</th><th>原版怎么做的</th><th>手搓会发生什么</th></tr>
 *   <tr><td><b>输入法（中文/日文）</b></td>
 *       <td>{@code Screen.charTyped} 收的是<b>输入法提交之后</b>的字符（GLFW 的 char 回调）</td>
 *       <td>自己在 {@code InputEvent.Key} 上攒键码 ⇒ 只能敲出 ASCII，中文命令一个字都打不进去</td></tr>
 *   <tr><td><b>键盘焦点</b></td>
 *       <td>开了 Screen 之后原版<b>停止</b>把按键喂给玩家（不会边打字边走路/挖方块）</td>
 *       <td>渲染层接键 ⇒ 每敲一个字母，玩家还在动、方块还在被挖（按键被吃两遍）</td></tr>
 *   <tr><td><b>ESC 优先级</b></td>
 *       <td>{@code Screen.keyPressed(ESCAPE)} ⇒ {@code onClose()}，且它<b>不</b>碰世界状态</td>
 *       <td>自己判 ESC ⇒ 很容易顺手把"投影也关掉"，而那正好违反已验收的
 *           「ESC 走路打方块都不关」</td></tr>
 * </table>
 *
 * <h2>3. 🔴 它是【不可见】的 —— 用户看到的仍然是那块世界空间的全息板</h2>
 * <pre>
 *   render()          : 什么都不画（世界照旧渲染，全息板由 RenderLevelStageEvent 那条路画）
 *   renderBackground(): 覆写成空（原版会糊一层渐变/模糊，那会把全息板盖住 —— 直接违反"观感是全息面板"）
 *   isPauseScreen()   : false（单人游戏里也不许暂停世界：用户要的是"边跑边敲"）
 * </pre>
 * 文字本身画在<b>板</b>上（{@code ShanhaiHoloMenuRenderer#drawPanelRowText} 读
 * {@link ShanhaiHoloMenuPanel#cellText}({@code MODE_COMMAND}, {@code COMMAND_INPUT_ROW}, 0)），所以这个 Screen 上<b>一个字都不画</b>，
 * 它只是一副"键盘接收器"。这也是为什么它不违反"面板必须是全息风格"那一条。
 *
 * <h2>4. 键位</h2>
 * <pre>
 *   回车 / 小键盘回车 : 执行这条命令（发给服务端，以管理员权限跑）
 *   ESC              : 关掉键盘（<b>投影与面板都不关</b> —— 回来接着点）
 *   退格             : 删一个字符
 *   Ctrl+A / Delete  : 清空
 *   其余可打印字符    : 追加（含中文，由输入法给）
 * </pre>
 * ⚠️ 鼠标滚轮：本界面没有任何可滚动的东西（面板是 5 行固定板），所以不接管滚动 ——
 * 滚轮仍然走原版（切快捷栏），这与"面板固定 5 行"是一致的、也是用户默认期望。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloCommandScreen extends Screen {

    /** 日志前缀（与全息那几个类同一个，便于 grep）。 */
    private static final String PREFIX = ShanhaiHoloMenuInput.PREFIX;

    public ShanhaiHoloCommandScreen() {
        super(Component.literal("山海全息命令输入"));
    }

    /** 什么都不画 —— 见类注释 §3。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 刻意留空：用户看到的 UI 是那块世界空间的全息板（RenderLevelStageEvent 画的）。
        // ⚠️ 这里【不要】调 super.render(...)：那会画控件；也【不要】调 renderBackground(...)：
        //    那会糊一层渐变把全息板盖掉。
    }

    /** 不糊背景（原版 {@code Screen#renderBackground} 会画渐变 + 模糊）。 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 同上：面板本身要透出来，所以背景一律不画。
    }

    /** 不暂停世界（单人游戏里也照旧跑）—— 用户要的是"边跑边敲命令"。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 优先：只关键盘，【不】碰投影与面板（已验收的「ESC 不关投影」照旧成立）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            ShanhaiHoloMenuPanel.backspace();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE
                || (keyCode == GLFW.GLFW_KEY_A && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0)) {
            ShanhaiHoloMenuPanel.clear();
            return true;
        }
        // 其余按键不回给 super：super 会在 ESC 之外做别的默认动作（例如 E 关界面），
        // 而在这里"任何非编辑键"都应当什么都不做，免得把面板顶掉。
        return true;
    }

    /**
     * 输入法/键盘提交下来的字符（<b>中文也走这一条</b>）。
     * <p>{@code §} 与控制字符一律不收：{@code §} 会被原版当成颜色码，收进去会让板上的字变色。
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint >= ' ' && codePoint != 0x00A7) {
            ShanhaiHoloMenuPanel.append(codePoint);
        }
        return true;
    }

    /** 回车：把整条命令发出去（执行由服务端以管理员权限做）。 */
    private void submit() {
        // 复用输入层那条唯一的出口（判空 / 日志 / 面板上那行读数都在那一处，
        // 免得"点执行"与"按回车"两条路各写一套、迟早漂）
        ShanhaiHoloMenuInput.submitCommand(ShanhaiHoloMenuPanel.command(), "screen");
    }

    /** 关掉键盘（回世界，面板还开着）。 */
    @Override
    public void onClose() {
        com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info("{} holo_cmd_screen_closed text_len={}",
                PREFIX, ShanhaiHoloMenuPanel.command().length());
        final Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(null);
        }
    }
}
