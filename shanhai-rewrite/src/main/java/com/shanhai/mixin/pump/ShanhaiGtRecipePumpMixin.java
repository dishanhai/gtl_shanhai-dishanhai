package com.shanhai.mixin.pump;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.shanhai.common.win.ShanhaiPump;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>消息泵插点 2/2</b>：GT 配方索引每收进一条配方，就给窗口线程一次抽消息的机会。
 *
 * <h2>1. 为什么是这个方法（{@code javap} 取证）</h2>
 * <pre>
 * javap -classpath libs/gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup
 *   public class com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup {
 *     public boolean addRecipe(com.gregtechceu.gtceu.api.recipe.GTRecipe);
 * </pre>
 * 它是"配方装进服务端索引"的入口，而配方表是逐条灌进去的；
 * 灌进来的那条链已有本工程自己的取证（{@code ShanhaiJeiBridge} 类注释第 2 节）：
 * gtceu 唯一往 {@code GTRecipeLookup} 灌配方的地方挂在 datapack 的 apply 上
 * -> 进世界/加载这一段必然经过本方法（不是靠"应该会经过"推出来的）。
 * <p>它与插点 1 互补：插点 1 管"主线程在等别的东西"的那段空转，本插点管"主线程正在灌配方"的那段。
 *
 * <h2>2. 三条纪律</h2>
 * <ul>
 *   <li>{@code remap = false}：{@code GTRecipeLookup} 是 GT 自己的类，名字本来就不是 SRG，不需要映射；</li>
 *   <li>{@code require = 0}：上游改名 / 改签名时只记一行 WARN，<b>不打崩启动</b>；失败现象是
 *       "少这一路 {@code [SHANHAI-PUMP]} 读数"，不是起不来；</li>
 *   <li>注入体只有一行（{@link ShanhaiPump#tick()}），它自己吞掉一切 Throwable
 *       -> 绝不许把异常抛进 GT 的配方索引构建。</li>
 * </ul>
 * <p>形参严格照目标方法：一个 {@code GTRecipe} + 一个 {@code CallbackInfoReturnable<Boolean>}
 * （返回 {@code boolean}）。写错参数个数/类型会在应用期报 {@code Invalid descriptor}，
 * 那是 {@code require = 0} 管不到的。
 *
 * <h2>3. 专服上跑不跑</h2>
 * 跑：目标类两端都有。但 {@link ShanhaiPump#tick()} 在专服上永远是
 * "读一次 volatile 得到 null 然后返回"（泵只由客户端 setup 装），所以代价可忽略。
 */
@Mixin(value = GTRecipeLookup.class, remap = false)
public class ShanhaiGtRecipePumpMixin {

    /** HEAD：在目标方法<b>真正改索引之前</b>跑一次泵。 */
    @Inject(method = "addRecipe", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$pump(final GTRecipe recipe, final CallbackInfoReturnable<Boolean> cir) {
        ShanhaiPump.tick();
    }
}
