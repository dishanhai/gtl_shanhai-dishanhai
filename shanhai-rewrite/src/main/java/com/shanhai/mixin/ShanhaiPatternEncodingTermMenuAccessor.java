package com.shanhai.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读访问器：拿到 AE2 样板编码终端里那个「已编码样板」输出槽。
 *
 * <h2>为什么需要它（链 A 的 HEAD 注入点必须先自己取到那块样板）</h2>
 * 链 A 的注入点是 {@code MatrixUploadUtil#uploadFromEncodingMenuToMatrix} 的 <b>HEAD</b> ——
 * 那里上游还没读槽，所以"这次要上传的是哪块样板"必须我们自己取。上游取法（反编译源 L52-53）：
 * <pre>
 *   RestrictedInputSlot encodedSlot = ((PatternEncodingTermMenuAccessor) menu).eap$getEncodedPatternSlot();
 *   ItemStack stack = encodedSlot.getItem();
 * </pre>
 * 同样的做法那个转接插件（{@code extendedae_plus_gtladd}）也有一份。本类照抄其形态，但
 * <b>只暴露 {@code encodedPatternSlot} 这一个字段</b>（不碰 blank 样板槽、不碰编解码用的 ConfigInventory），
 * 因为 P7 只做"接管写目标"，不做"返还空白样板"之外的任何事。
 *
 * <h2>为什么字段名可以写死 {@code encodedPatternSlot}</h2>
 * {@code javap -p appeng.menu.me.items.PatternEncodingTermMenu} 实证：
 * <pre>
 *   private final appeng.menu.slot.RestrictedInputSlot encodedPatternSlot;
 * </pre>
 * 它是 <b>AE2 自己的字段</b>（不是 Minecraft 的），Forge 的重映射只作用于 MC 的类/字段/方法
 * ⇒ 运行时名字就是 {@code encodedPatternSlot}，故 {@code remap = false}
 * （gtlcore 的 {@code PatternEncodingTermMenuMixin} 里 {@code @Shadow(remap = false)} 同一结论）。
 *
 * <p>⚠️ 本类<b>零行为</b>：接口式 mixin、只有 accessor，不改变任何上游逻辑。
 */
@Mixin(value = PatternEncodingTermMenu.class, remap = false)
public interface ShanhaiPatternEncodingTermMenuAccessor {

    /** 「已编码样板」输出槽（链 A 从这里取这次要上传的那块样板）。 */
    @Accessor(value = "encodedPatternSlot", remap = false)
    RestrictedInputSlot shanhai$getEncodedPatternSlot();
}
