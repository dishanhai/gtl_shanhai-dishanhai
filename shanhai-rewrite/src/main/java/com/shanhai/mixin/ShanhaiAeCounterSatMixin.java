package com.shanhai.mixin;

import appeng.api.stacks.AEKey;
import com.shanhai.ShanhaiMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>给 AE2 的存量累加加一道「饱和」闸</b>：加法越界时夹到 {@code Long.MAX_VALUE} / {@code Long.MIN_VALUE}，
 * 绝不绕回负数。除此之外<b>不改任何语义</b>（未越界 ⇒ 原方法原样执行，一行代码都不碰）。
 *
 * <h2>为什么要它（前一份 mixin 的代价）</h2>
 * {@code ShanhaiInfiniteCellCapMixin} 把超级 AE 无限元件的单键上限抬成了 {@code Long.MAX_VALUE}；
 * 而 AE2 的累加是<b>裸 long 加</b>（逐层 javap 实证，见下）。于是<b>两个来源</b>（两块同种无限元件、
 * 或一块元件 + 网络里同种物品）就 {@code 9.22e18 + 9.22e18 = -2}。
 *
 * <h3>翻负之后会发生什么（都是实证，不是推测）</h3>
 * <ol>
 *   <li><b>终端那一格直接崩渲染</b>：{@code MEStorageScreen#m_280092_} 里
 *       {@code 220: AEKey.formatAmount(J,AmountFormat)} → {@code AEKeyType#formatShortAmount(J,I)}
 *       → {@code ReadableNumberConverter.format(J,I)}，后者的第 0..13 字节就是
 *       {@code Preconditions.checkArgument(amount >= 0, "Non-negative numbers cannot be formatted by this method")}
 *       ⇒ 负数抛 {@code IllegalArgumentException}。而 {@code m_280092_} 的异常表只有一条
 *       {@code 65..86 → 89 (Exception)}，护的是<b>图标</b>那句 {@code AEKeyRendering.drawInGui}，
 *       {@code formatAmount} 在偏移 220，<b>不在任何 try 里</b> ⇒ 异常直接冲出渲染循环。</li>
 *   <li><b>物品从终端里消失</b>：{@code MEInventoryUpdatePacket$Builder#addChanges} 在
 *       {@code 113..124} 判 {@code storedAmount <= 0 && craftableAmount <= 0 && !craftable}
 *       ⇒ 改发一条 0 存量、再 {@code IncrementalUpdateHelper.removeSerial}（{@code 153..156}）。</li>
 *   <li><b>「够不够料」被读反</b>：{@code getAvailableStacks()} 那张表就是本类的记录表。翻负后
 *       · GTCEu {@code MEStockingBusPartMachine#refreshList} 偏移 104..111 是 {@code if (amount <= 0) continue}
 *         ⇒ 该物品<b>整行不参与维持</b>（不会被补货）；
 *       · ME Requester {@code StorageManager$Storage#refresh} 偏移 39..48 是
 *         {@code knownAmount = getAvailableStacks().get(key)} ⇒ 变成 -2；
 *       · 本工程 {@code StellarSampleAssemblerPartMachine} 的 {@code shanhaiAvailable*} 判据是
 *         {@code available > 0} ⇒ 读成「没料」。</li>
 * </ol>
 * <p>⚠️ 反过来说：<b>抽取</b>（{@code MEStorage.extract}，含 {@code SIMULATE}）是<b>逐 provider</b>问的，
 * 不经过这张合计表 ⇒ 「明明抽得出来、界面/维持却当没有」这种分裂，正是翻负最讨厌的地方。
 *
 * <h2>为什么打在 {@code VariantCounter#add}，而不是 {@code AEKey2LongMap$OpenHashMap#addTo}</h2>
 * <pre>
 * KeyCounter#add(AEKey,J)          9: invokevirtual getSubIndex(AEKey)VariantCounter
 *                                 14: invokevirtual VariantCounter.add(AEKey,J)V
 * VariantCounter#add(AEKey,J)      0..6: getRecords().addTo(AEKey,J)J
 *                                 11: pop2                     ← 🔴 返回值被丢掉
 * AEKey2LongMap$OpenHashMap#addTo  0..3: invokespecial it/unimi/dsi/fastutil/objects/Object2LongOpenHashMap.addTo
 * AEKey2LongMap$AVLTreeMap#addTo   0..3: 同上（fuzzy 子表 Object2LongAVLTreeMap）
 * </pre>
 * <ul>
 *   <li>① @{code VariantCounter.add} 把 {@code addTo} 的返回值 <b>{@code pop2} 丢掉了</b>
 *       ⇒ 在 {@code addTo} 上做 {@code @ModifyReturnValue} <b>毫无用处</b>（表里已经写进绕回值了），
 *       只能做 HEAD 拦截 + 自己写回；</li>
 *   <li>② 记录表有 <b>两种实现</b>（{@code OpenHashMap} 与 {@code AVLTreeMap}），打在
 *       {@code AEKey2LongMap} 上要写 <b>两份</b>；打 {@code VariantCounter#add} <b>一份就全覆盖</b>；</li>
 *   <li>③ <b>全 AE 只此一条累加咽喉</b>：{@code KeyCounter#add} 覆写了，但它只是
 *       {@code getSubIndex(key).add(...)} 转调；两个子表 {@code VariantCounter$UnorderedVariantMap} /
 *       {@code VariantCounter$FuzzyVariantMap} 的成员表里<b>都没有覆写 {@code add}</b>（javap 实证）
 *       ⇒ 所有累加最终都落到本方法。</li>
 * </ul>
 *
 * <h2>🔴 安全边界（为什么说它「最小」）</h2>
 * <ol>
 *   <li><b>未越界 ⇒ 完全不动</b>：先算一遍 {@code cur + amount}（就是原方法自己要算的那一下），
 *       和夹取后的值相等就 {@code return}，让原方法照跑 —— 语义、性能、时序全不变；</li>
 *   <li><b>夹取判据只用两操作数同号</b>：{@code b > 0 && a > MAX - b} ⇒ MAX；
 *       {@code b < 0 && a < MIN - b} ⇒ MIN。它<b>只可能在真正越界时成立</b>，
 *       所以对「扣减 / 负增量 / {@code removeAll} 的 {@code add(key,-v)}」这些正常路径<b>零影响</b>；</li>
 *   <li>写回用 {@code set}（本来就是 {@code put}），不碰 {@code dropZeros} 之外的任何逻辑（夹取结果永远不是 0）；</li>
 *   <li><b>fail-soft</b>：目标只在 AE2 在时加载；注入点 {@code require = 0}；配置 {@code required: false}。</li>
 * </ol>
 *
 * <h2>⚠️ remap = false 必须逐处写全（本工程踩过的静默失效坑）</h2>
 * 目标是 AE2 自己的类、不是 Minecraft 类 ⇒ {@code @Mixin(remap = false)} 与 {@code @Inject(remap = false)}
 * <b>两处都写</b>。漏写不报错，只是运行期找不到目标、静默不生效。
 *
 * <h2>⚠️ 为什么用 {@code targets = "…"} 而不是 {@code @Mixin(VariantCounter.class)}</h2>
 * {@code appeng.api.stacks.VariantCounter} 是 <b>包私有</b>（javap: {@code abstract class appeng.api.stacks.VariantCounter}）
 * ⇒ 从 {@code com.shanhai.mixin} 里<b>没法写它的类名</b>。而 {@code @Shadow} 的
 * {@code get(AEKey)J} / {@code set(AEKey;J)V} 两个成员都是 {@code public}（javap 实证），
 * 参数类型 {@code AEKey} 也是 public ⇒ 只有「类名」需要写字符串，其余都走正常编译期检查。
 * <p>{@code -AdisableTargetValidator=true} 已在 {@code build.gradle} 的 annotationProcessor 里，
 * 注解处理器不会因为按名字找不到包私有目标而报错。
 *
 * <h2>实机判据</h2>
 * <pre>
 * grep -F '[SHANHAI-AESAT]' logs/latest.log      ← 整个游戏生命周期最多一行
 * 期望：两块同种无限元件放进同一网络后，终端显示仍是 9.2E（而不是那一格消失 / 客户端报 IAE）
 * </pre>
 */
@Mixin(targets = "appeng.api.stacks.VariantCounter", remap = false)
public abstract class ShanhaiAeCounterSatMixin {

    /** 整个进程只打一行，绝不刷屏（这是 {@code add} 的热路径，日志本身就可能是瓶颈）。 */
    @Unique
    private static boolean shanhai$aeSatLogged = false;

    /**
     * 目标类自己的读法（{@code public long get(AEKey)}，javap 实证）。
     * <p>包私有类 + public 成员：{@code @Shadow} 按「名字 + 描述符」吻合即可，Mixin 会把对
     * 本 mixin 的调用改写成对 {@code VariantCounter} 的调用。
     */
    @Shadow
    public abstract long get(AEKey key);

    /**
     * 目标类自己的写法（{@code public void set(AEKey, long)}，javap 实证）。
     * <p>它内部就是 {@code getRecords().put(key, amount)}（{@code dropZeros} 且 {@code amount == 0}
     * 时才走 {@code removeLong}）—— 夹取结果恒非 0，所以等价于 {@code put}。
     */
    @Shadow
    public abstract void set(AEKey key, long amount);

    /**
     * 饱和加。判据 =「与两操作数同号判据不符 ⇒ 夹」。
     *
     * <p>⚠️ <b>刻意不写成 {@code Math.addExact} + catch</b>：热路径上抛/接异常比一次分支贵得多。
     *
     * @param a 现值
     * @param b 增量（可负：{@code removeAll} / {@code invert} 都会送负数进来）
     * @return {@code a + b}，越界时夹到 {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}
     */
    @Unique
    private static long shanhai$satAddLong(final long a, final long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        if (b < 0L && a < Long.MIN_VALUE - b) {
            return Long.MIN_VALUE;
        }
        return a + b;
    }

    /**
     * {@code VariantCounter#add(AEKey, long)} 的入口闸。
     *
     * <p>{@code require = 0}：AE2 换版本后描述符变了就跳过（fail-soft），不把启动打崩。
     * <p>{@code cancellable = true}：越界时我们要自己写回并跳过原方法。
     * <p>{@code remap = false}：非 MC 目标，见类注释。
     */
    @Inject(
            method = "add(Lappeng/api/stacks/AEKey;J)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void shanhai$saturatingAdd(final AEKey key, final long amount, final CallbackInfo ci) {
        final long cur = this.get(key);
        final long planted = shanhai$satAddLong(cur, amount);
        // 未越界 ⇒ 交给原方法（原方法算的就是 cur + amount），语义零变化
        if (planted == cur + amount) {
            return;
        }
        this.set(key, planted);
        ci.cancel();
        if (!shanhai$aeSatLogged) {
            shanhai$aeSatLogged = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-AESAT] saturated add key={} cur={} amount={} wrapped={} -> {}",
                    key, cur, amount, cur + amount, planted);
        }
    }
}
