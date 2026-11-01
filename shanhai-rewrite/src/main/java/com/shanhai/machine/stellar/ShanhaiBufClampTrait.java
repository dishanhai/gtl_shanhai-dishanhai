package com.shanhai.machine.stellar;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTrait;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>星律「产物 buffer」的两件事</b>：写进去之前<b>夹</b>（不溢出），以及每一轮<b>免电搬回网络</b>
 * （不丢量、不付 AE 电）。本类只做"接缝 + 怎么退 + 怎么报"，判据全在纯核
 * {@link ShanhaiBufferClamp} / {@link ShanhaiBufferFlush}（零 MC 依赖，可离线单测）。
 *
 * <h2>一、为什么是这一个接缝（无 mixin）</h2>
 * buffer 的唯一写入点是
 * {@code MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase#meHandleRecipeOutputInner}（物品）
 * 与同文件 {@code MEFluidHandlerBase#meHandleRecipeOutputInner}（流体）—— 里面就一句
 * {@code this.getMachine().buffer.addTo(key, amount)}（{@code MEPatternBufferRecipeHandlerTraitBase.java:281 / :152}，
 * fastutil <b>裸加不饱和</b>）。这个类在 gtlcore 里，我们改不到；但它的<b>实例是由可覆写的工厂造出来的</b>：
 * <pre>
 *   MEPatternBufferPartMachine#createRecipeHandler(IO)      ← protected，构造器 :188 调
 *       → MEPatternBufferRecipeHandlerTrait#createMEItemHandler(IO)   ← protected
 *       → MEPatternBufferRecipeHandlerTrait#createMEFluidHandler(IO)  ← protected
 * </pre>
 * 三个都是 {@code protected}、都<b>不是 final</b>（{@code javap -p} 对
 * {@code libs\gtlcore-1.2.3.2-fix3.jar} 逐条核过：sha256_16 {@code 32D3B630D1D1EA28}）。
 * 本类只是第 1 步的替换品；第 2/3 步返回本类的两个内层 handler。
 * ⇒ <b>不需要 mixin，也不需要反射</b>（上游 {@code MEStockingPatternBufferPartMachine} 自己就覆写了
 * {@code createRecipeHandler}，是官方留的扩展点）。
 *
 * <h2>二、buffer 怎么取到</h2>
 * {@code MEPatternBufferPartMachineBase#getBuffer()} 是 <b>public</b>
 * （{@code javap -p}：{@code public Object2LongOpenHashMap<AEKey> getBuffer();}）
 * ⇒ 每次使用前现取一次，<b>不缓存引用</b>（换机/读档不会拿到旧 map）。
 * 取不到（返回 null / 抛异常）⇒ <b>退回上游原行为</b>（{@code super.meHandleRecipeOutputInner}）并打一行
 * {@code bufclamp unreadable} —— 夹取永远不许阻塞产出。
 *
 * <h2>三、夹的算法与回归底线（{@link ShanhaiBufferClamp}）</h2>
 * {@code existing + want <= MAX} 时<b>逐位不变</b>；装不下只补到不溢出；夹到 0 就<b>不写</b>。
 *
 * <h2>四、免电搬运（{@link ShanhaiBufferFlush} + 本类的 {@link #sweepFree}）</h2>
 * 取证结论（{@code temp\infinity2\BigInteger磁盘接口.md}）：磁盘内部是 BigInteger、单次
 * {@code insert(≤ Long.MAX)} 全额收下、永不报满；但 AE2 入库<b>要付电</b>
 * （{@code StorageHelper#poweredInsert} 把额度砍成 {@code min(磁盘收多少, 可用电×factor)}）——
 * 9.22e18 件就需要 9.22e18 AE，<b>结构上永远退不完</b>。⇒ 「先退再写」救不了，
 * 只能让<b>我们这条退货路径不付电</b>（用户拍板：星律产物入库免电；<b>不改 AE2 的电经济通用规则</b>）。
 * <pre>
 *   我们自己的 IEnergySource（{@link FreeEnergySource}：任意额度都"够"）
 *     → StorageHelper.poweredInsert(它, 网格库存, key, amount ≤ Long.MAX, actionSource, MODULATE)
 *     → amount 原额入库（实测口径见离线自证），buffer 里该条目减掉 moved，减到 0 才移除
 * </pre>
 * 触发在 {@code update()} 里（{@link ShanhaiBufferFlush#cadenceTicks()} = <b>每 tick 一轮</b>、
 * 一轮最多 64 条），实现见 {@code StellarSampleAssemblerPartMachine#shanhaiTickFreeFlush()}。
 *
 * <h2>四-1、🔴 「追着写」（2026-11-01 实机日志驱动）</h2>
 * 实机读数：{@code bufclamp key=gtceu:lazurite_dust existing=9223372036854775807 want=1104
 * write=0 dropped=1104}，每 5 秒一条、{@code dropped} 恒 1104。两条病因都在"节奏"上：
 * <ol>
 *   <li>产出<b>每 tick</b>都在写，而旧节奏是<b>每 5 tick</b> 才退一轮 ⇒ 退完立刻又满；</li>
 *   <li>一轮只有 64 条预算，键集却是<b>稳定</b>的（同一批产物反复重写）⇒ fastutil 的遍历顺序稳定
 *       ⇒ 排在 64 名之后的键<b>永远轮不到</b>（实机旁证：{@code 条数=64 成功=64 失败=0} 每轮都成功，
 *       可那个键的 {@code existing} 一直停在 MAX）。</li>
 * </ol>
 * ⇒ 本类的 {@link #writeClamped} 改成 <b>"写不进去 ⇒ 就地追一轮（把这个键排最前）⇒ 重算再写一次"</b>，
 * 核心逻辑在纯核 {@link ShanhaiBufferFlush#writeWithChase}（可离线断言）。追写失败/离线时
 * 行为与老版本<b>逐位相同</b>（write 照负的夹取结果提交，绝不抛进产出链）。
 *
 * <h2>五、日志（前缀沿用 {@code [SHANHAI-STELLAR]}；两档各 5 秒 1 行）</h2>
 *
 * <b>🔴 口径（用户 2026-11-01 拍板）：WARN 就是真的丢了。</b>级别判据只有一个 ——
 * {@link ShanhaiBufferFlush#writeEvents}（纯核，离线可断言），它看的是<b>追写之后</b>的
 * {@link ShanhaiBufferFlush.ChaseWrite#dropped}。
 * <pre>
 *   [WARN] bufclamp key=… existing=… want=… write=… dropped=&lt;真丢&gt; rescue=failed pos=…
 *   [INFO] bufflush CHASE pos=… key=… existing=… moved=… want=… write=… dropped=0 rescued=1
 *
 *   [WARN] bufclamp BUFNEG pos=… dim=… key=… value=… neg=…
 *   [WARN] bufclamp unreadable
 *   [INFO] bufclamp key=… existing=… want=… write=… dropped=…        ← 回灌槽被夹（余量写回源表，不丢）
 *   [WARN] bufflush pos=… dim=… 条数=… 成功=… 失败=… 本次入网量=… 剩余=… free=on   ← 心跳（机器那侧）
 *   [WARN] bufflush cadence=everytick pos=… rounds=… entries=… moved=… remaining=… upstreamMin=…
 * </pre>
 * 三条纪律：
 * <ol>
 *   <li><b>救回来的那一单一条 WARN 都不打</b>：它的 {@code bufclamp} 行<b>干脆不打</b>（不是降级）——
 *       键与三个读数在 {@code CHASE} 行里<b>全都有</b>，打出来纯属重复，而它正是"看起来像丢、
 *       其实没丢"的误导源；</li>
 *   <li><b>追写也失败</b>才 WARN，且那行一定带 {@code rescue=failed}（一眼就知道是真丢）；</li>
 *   <li>节流：<b>WARN 一族</b> 5 秒 1 行、<b>CHASE(INFO) 族</b> 5 秒 1 行、<b>回灌(INFO) 族</b> 5 秒 1 行
 *       —— 三个窗口各自独立（见 {@link ShanhaiBufferFlush#windowDue}），
 *       <b>绝不让一条 INFO 把 WARN 挤掉</b>。</li>
 * </ol>
 * 全部走 {@link ShanhaiStellarLog}（带统一前缀）；{@code bufflush} 心跳/节奏/{@code BUFNEG} 那三行
 * 由机器侧按游戏 tick 限流（100 tick），不经过本类。
 *
 * <h2>六、⚠️ 构造期约束（改动前必读）</h2>
 * 本类是在 {@code MEPatternBufferPartMachine} 的<b>构造器</b>里（:188）被 new 出来的，
 * 那时 {@code StellarSampleAssemblerPartMachine} 自己的字段<b>还全是 null</b>。
 * ⇒ 本类与两个内层 handler 在<b>构造期</b>都<b>不许读机器上的任何自定义字段/方法</b>；
 * 真正写入/搬运时（tick 里）只允许用这三样（全是父类公有 API 或本机显式透出的 accessor）：
 * {@code getBuffer()}、{@code getGrid()}、{@code shanhaiActionSource()}。
 */
public class ShanhaiBufClampTrait extends MEPatternBufferRecipeHandlerTrait {

    /** WARN 档上一次打点的墙钟（{@code 0} = 从没打过）—— 真丢 / 读不到 buffer 共用这一个闸。 */
    private static volatile long lastWarnMs = 0L;

    /** 回灌夹取（INFO）上一次打点的墙钟 —— 与 WARN 档<b>分开</b>，绝不互相挤占。 */
    private static volatile long lastClampInfoMs = 0L;

    /** {@code bufflush CHASE}（INFO）上一次打点的墙钟 —— 同样独立。 */
    private static volatile long lastChaseInfoMs = 0L;

    /**
     * 追写的<b>重入闸</b>（{@code true} = 这一线程正在跑一次"就地追一轮"）。
     *
     * <p>追写里的动作是 {@code poweredInsert}（网络入库）。它<b>理论上</b>可能触发网格侧的
     * {@code onChange} 监听，万一哪条链又走回产出写入，就会变成
     * 写入 → 追写 → 入库 → 写入 → 追写 … 的递归（服务端 tick 里出
     * {@code StackOverflowError} 是要崩档的）。代价只有一个 boolean，所以挡住。
     * <p>跨机器互斥的副作用：A 机正在追写时 B 机的追写会跳过（B 机这一单按老行为提交，
     * 下一次写入/下一 tick 的常规路径照常）。
     */
    private static volatile boolean IN_CHASE = false;

    /** 免电搬运的间隔兜底值（tick）；生产侧优先读上游配置 {@code ConfigHolder.MEPatternOutputMin}。 */
    public static final int INTERVAL_FALLBACK_TICKS = ShanhaiBufferFlush.FALLBACK_INTERVAL_TICKS;

    public ShanhaiBufClampTrait(MEPatternBufferPartMachine machine, IO io) {
        super(machine, io);
    }

    // ================================================================ 内层 handler（物品 / 流体各一）

    @Override
    protected MEPatternBufferRecipeHandlerTraitBase.MEItemInputHandlerBase createMEItemHandler(IO io) {
        return new ClampItemHandler(this.getMachine(), io);
    }

    @Override
    protected MEPatternBufferRecipeHandlerTraitBase.MEFluidHandlerBase createMEFluidHandler(IO io) {
        return new ClampFluidHandler(this.getMachine(), io);
    }

    /**
     * 物品侧：逐条产出，写 buffer 之前先夹。
     *
     * <p>除"写"这一步，其余<b>逐行镜像</b>上游 {@code MEItemInputHandlerBase#meHandleRecipeOutputInner}
     * （含 {@code IntProviderIngredient} 的清采样、{@code LongIngredient} 取 {@code getActualAmount()}）。
     */
    private static final class ClampItemHandler extends MEPatternBufferRecipeHandlerTraitBase.MEItemInputHandlerBase {

        ClampItemHandler(MEPatternBufferPartMachine machine, IO io) {
            super(machine, io);
        }

        @Override
        public @NotNull List<Ingredient> meHandleRecipeOutputInner(@NotNull List<Ingredient> left, boolean simulate) {
            if (simulate) {
                return List.of();
            }
            final Object2LongOpenHashMap<AEKey> buffer = bufOrNull(this.getMachine());
            if (buffer == null) {
                // 取不到现值 ⇒ 退回原行为（绝不阻塞产出），并留一行读数
                reportUnreadable();
                return super.meHandleRecipeOutputInner(left, simulate);
            }
            for (Ingredient ingredient : left) {
                if (ingredient instanceof IntProviderIngredient intProvider) {
                    intProvider.setItemStacks(null);
                    intProvider.setSampledCount(null);
                }
                final ItemStack[] items = ingredient.getItems();
                if (items.length == 0) {
                    continue;
                }
                final ItemStack output = items[0];
                if (output.isEmpty()) {
                    continue;
                }
                final long want = ingredient instanceof LongIngredient longIngredient
                        ? longIngredient.getActualAmount()
                        : (long) output.getCount();
                final AEKey key = AEItemKey.of(output);
                if (key == null) {
                    continue;
                }
                writeClamped(this.getMachine(), buffer, key, want);
            }
            return List.of();
        }
    }

    /**
     * 流体侧：与物品侧同款，只换了 key/量的取法（逐行镜像上游 {@code MEFluidHandlerBase#meHandleRecipeOutputInner}）。
     */
    private static final class ClampFluidHandler extends MEPatternBufferRecipeHandlerTraitBase.MEFluidHandlerBase {

        ClampFluidHandler(MEPatternBufferPartMachine machine, IO io) {
            super(machine, io);
        }

        @Override
        public @NotNull List<FluidIngredient> meHandleRecipeOutputInner(@NotNull List<FluidIngredient> left,
                                                                        boolean simulate) {
            if (simulate) {
                return List.of();
            }
            final Object2LongOpenHashMap<AEKey> buffer = bufOrNull(this.getMachine());
            if (buffer == null) {
                reportUnreadable();
                return super.meHandleRecipeOutputInner(left, simulate);
            }
            for (FluidIngredient fluidIngredient : left) {
                if (fluidIngredient.isEmpty()) {
                    continue;
                }
                final FluidStack[] fluids = fluidIngredient.getStacks();
                if (fluids.length == 0) {
                    continue;
                }
                final FluidStack output = fluids[0];
                if (output == null) {
                    continue;
                }
                final AEKey key = AEFluidKey.of(output.getFluid());
                if (key == null) {
                    continue;
                }
                writeClamped(this.getMachine(), buffer, key, output.getAmount());
            }
            return List.of();
        }
    }

    // ================================================================ 夹 + 写 + 日志（唯一写入口）

    /**
     * <b>唯一的写 buffer 动作</b>：取现值 → {@link ShanhaiBufferClamp#allow} →
     * <b>写不进去就地追一轮</b> → 重算再写一次 → {@link ShanhaiBufferClamp#commit} → 该报就报。
     *
     * <pre>
     *   existing = buffer.getLong(key)          // 缺键返回 0（fastutil 默认值）
     *   write    = allow(existing, want)
     *   write &lt; want ⇒ ① 就地追一轮（免电 sweep，这个 key 排最前、预算 8 条）
     *                  ② 追到东西了 ⇒ 重取 existing、重算 write
     *   commit(buffer, key, want, write)        // 夹到 0 ⇒ 不写（不造 0 值条目）
     *   日志 = writeEvents(追写之后的 dropped)   // 🔴 WARN 只在"真的丢了"时出现（见类注释 §五）
     * </pre>
     *
     * <p>"夹 / 不夹 / 写 / 不写"的判据本身在纯核（{@link ShanhaiBufferClamp#allow} /
     * {@link ShanhaiBufferClamp#skipWrite}）；"打哪条、什么级别"也在纯核
     * （{@link ShanhaiBufferFlush#writeEvents}）—— 本方法只剩两件事：<b>什么时候追一轮</b>、
     * <b>把纯核给的事件派发到对应级别</b>。
     *
     * <p>🔴 <b>追写不改变老行为</b>：追不到东西（离线 / 没网格 / 网络不收这个键 / 负值）⇒
     * {@code write} 仍是夹取的结果，与上一版<b>逐位相同</b>；追写自身的异常在纯核里被兜住
     * （{@code catch Throwable}），一个都不许漏进产出链。
     *
     * <p>🔴 <b>日志口径（2026-11-01 实机驱动）</b>：旧版在<b>追写之前</b>打 {@code bufclamp … dropped=1104}，
     * 于是"追写救回来了"也照样 WARN（实机 11 条里 10 条是救回来的）。本版把那一行<b>去掉</b>
     * （它的每个字段在 {@code CHASE} 行里都有），只在真丢时打带 {@code rescue=failed} 的 WARN。
     *
     * <p>⚠️ 这一版<b>仍然不</b>调 {@code AEUtils#reFunds}：那条路要付 AE 电
     * （{@code poweredInsert} 按可用电砍额度），9.22e18 件在结构上退不完 ⇒ 换成
     * {@link #sweepFree} 的免电搬运。
     *
     * @param machine 本机（追写要网格库存 + actionSource）；{@code null} ⇒ 只夹不追
     */
    static void writeClamped(@Nullable MEPatternBufferPartMachineBase machine,
                             @NotNull Object2LongOpenHashMap<AEKey> buffer,
                             @NotNull AEKey key, long want) {
        final ShanhaiBufferFlush.ChaseWrite done = ShanhaiBufferFlush.writeWithChase(
                buffer, key, want,
                // 🔴 reporter 传 null：它拿到的是**追写之前**的读数，正是本版要消掉的误导源
                null,
                () -> chaseFor(machine, buffer, key));
        emitWriteEvents(machine, key, done);
    }

    /**
     * <b>把一次写入的事件派发到日志</b>（级别由纯核 {@link ShanhaiBufferFlush#writeEvents} 定，
     * 本方法只负责"谁用哪个 5 秒窗口"）。
     *
     * <p>为什么派发要留在本类：{@code ShanhaiBufferFlush} 是零 MC 依赖的纯核，它不能持有
     * {@code System.currentTimeMillis()} 的静态状态（离线自证要能断言"该不该打"）。
     * 所以纯核只说"这条是 WARN / 这条是 INFO"，节流与落笔在这里。
     *
     * <p>两个供给器（{@code keyIdOf} / {@code posOf}）传给纯核是<b>惰性</b>的：没被夹的 99.99% 产出
     * 一次 {@code AEKey#getId()}、一次位置拼接都不会发生。
     */
    private static void emitWriteEvents(@Nullable MEPatternBufferPartMachineBase machine,
                                        @Nullable AEKey key,
                                        @Nullable ShanhaiBufferFlush.ChaseWrite done) {
        if (done == null) {
            return;
        }
        final List<ShanhaiBufferFlush.Event> events = ShanhaiBufferFlush.writeEvents(
                done,
                () -> keyId(key),
                () -> posOf(machine));
        for (int i = 0; i < events.size(); i++) {
            final ShanhaiBufferFlush.Event event = events.get(i);
            if (event == null) {
                continue;
            }
            if (event.warn) {
                reportWarn(event.text);
            } else {
                reportChaseInfo(event.text);
            }
        }
    }

    /**
     * <b>就地追一轮</b>（{@link ShanhaiBufferFlush#CHASE_ENTRIES} 条预算、这个 key 排最前）。
     *
     * <p>为什么优先键是必须的：上一版实机日志里 {@code 条数=64 成功=64 失败=0} 每轮全成功，
     * 可 {@code lazurite_dust} 的 {@code existing} 一直停在 MAX ⇒ 它<b>根本没进过那 64 条窗口</b>。
     * 只把节奏调快（每 5 tick → 每 tick）治不了这个 —— 顺序不变，它还是第 65 名之后。
     *
     * @return 这一轮真正入网的总量（{@code 0} = 什么都没搬动 ⇒ 纯核不重算 write）
     */
    private static long chaseFor(@Nullable MEPatternBufferPartMachineBase machine,
                                 @NotNull Object2LongOpenHashMap<AEKey> buffer,
                                 @NotNull AEKey key) {
        if (IN_CHASE) {
            // 重入：一次追写里绝不再嵌套一次（见 IN_CHASE 的注释）
            return 0L;
        }
        IN_CHASE = true;
        try {
            final ShanhaiBufferFlush.Sweep sweep =
                    sweepFreeFor(machine, buffer, key, ShanhaiBufferFlush.CHASE_ENTRIES);
            return sweep == null ? 0L : sweep.moved;
        } catch (Throwable t) {
            return 0L;
        } finally {
            IN_CHASE = false;
        }
    }

    // ================================================================ 免电搬运（星律产物入库不付 AE 电）

    /**
     * <b>「这一次入库不付电」的全部实现</b>：一个"要多少有多少"的 {@link IEnergySource}。
     *
     * <h2>为什么这样就能免电（javap 实证，{@code appeng.api.storage.StorageHelper}）</h2>
     * {@code poweredInsert(source, inv, key, amount, src, MODULATE)} 的字节码顺序：
     * <pre>
     *   amount = inv.insert(key, amount, SIMULATE, src)                  // 磁盘报价（本盘 = 原额）
     *   amount &lt;= 0 ⇒ return 0
     *   factor = max(1.0, key.getAmountPerOperation())
     *   d      = source.extractAEPower(amount / factor, SIMULATE, CONFIG) // ★ 只在这里看"有多少电"
     *   amount = min(amount, (long)(d * factor + 0.9))                    // ★ 电不足就砍额度
     *   amount = source.extractAEPower(amount / factor, MODULATE, CONFIG) → 结果被 pop 掉（返回值不参与）
     *   return inv.insert(key, amount, MODULATE, src)
     * </pre>
     * ⇒ <b>这条路径上唯一的"电"就是本类返回的那个 double</b>。返回一个足够大的常量，
     * 额度就不会被砍，实际扣电的那次调用也会落在我们自己身上（不从网格扣一分）。
     *
     * <h2>🔴 为什么返回"无限"而不是"刚好等于请求值"</h2>
     * 上游那一步是 {@code (long)(d * factor + 0.9)} —— <b>double 截断成 long</b>。
     * 9.2e18 这个量级的 double <b>ULP ≈ 1024</b>：返回"刚好够"时截断会把几个到几百件砍掉
     * （<b>实测</b>：{@code 9223372036854775000} 少 <b>216</b> 件、{@code 2^53+1} 少 1 件），
     * 于是 {@code min} 选中那个偏小的值 ⇒ 一轮搬不完、要多搬一轮（<b>不是丢量</b>：余量留在 buffer 下轮再搬）。
     * 返回 {@code Double.MAX_VALUE} ⇒ {@code d * factor} 溢出成 {@code Infinity} ⇒ 截断饱和成
     * {@code Long.MAX_VALUE} ⇒ {@code min} 永远等于磁盘报价（= 原额，<b>实测 0 损失</b>）。
     * 这条差异在离线自证里是<b>可断言</b>的（{@code FreeFlushSelfTest} 第 1 组）。
     *
     * <h2>范围限定</h2>
     * 这个实例<b>只</b>被 {@link #sweepFree} 用；不注册到网格、不改 {@code IEnergyService}、
     * 不碰 AE2 的通用电量规则 —— 星律以外的一切仍然照常付电。
     */
    private static final class FreeEnergySource implements IEnergySource {

        @Override
        public double extractAEPower(double amount, @NotNull Actionable mode, @NotNull PowerMultiplier multiplier) {
            // 无限电：SIMULATE 不会被砍额度；MODULATE 的返回值上游本来就 pop 掉（偏移 127 pop2）
            return ShanhaiBufferFlush.freeEnergy();
        }
    }

    /** 唯一的无限电实例（无状态，可复用）。 */
    private static final IEnergySource FREE_ENERGY = new FreeEnergySource();

    /**
     * <b>免电搬一轮 buffer → 网络</b>（生产侧常规调用点：
     * {@code StellarSampleAssemblerPartMachine#shanhaiTickFreeFlush()}）。
     *
     * @return 本轮读数；{@code null} = 这一轮没搬（前置不满足）
     */
    @Nullable
    static ShanhaiBufferFlush.Sweep sweepFree(@Nullable MEPatternBufferPartMachineBase machine,
                                              @Nullable Object2LongOpenHashMap<AEKey> buffer) {
        return sweepFreeFor(machine, buffer, null, ShanhaiBufferFlush.MAX_ENTRIES);
    }

    /**
     * <b>免电搬一轮（可指定优先键与预算）</b>—— 生产侧两个调用点共用这一份实现：
     * <ul>
     *   <li>常规路径（每 tick）：{@code priority = null}、{@code maxEntries = 64}；</li>
     *   <li>追写路径（写不进去时）：{@code priority = 卡住的那个键}、{@code maxEntries = 8}。</li>
     * </ul>
     *
     * <p>三件事：拿网格库存 → 用 {@link FreeEnergySource} 走
     * {@code StorageHelper.poweredInsert(…, MODULATE)} → 把"搬了多少"交给
     * {@link ShanhaiBufferFlush#sweep} 记账（搬多少减多少，减到 0 才移除）。
     *
     * <p>🔴 <b>{@code ≤0} 的量一次都到不了 {@code poweredInsert}</b>：入库动作被
     * {@link ShanhaiBufferFlush#guarded} 包了一层闸门（{@link ShanhaiBufferFlush#moveIfPositive}）。
     * 纯核那一层的 {@code amount <= 0 ⇒ continue} 是第一道，这里是<b>贴着入库动作</b>的第二道 ——
     * 两道各有各的离线断言（见 {@code FreeFlushSelfTest2} 第 1/1b 组）。
     *
     * <p>⚠️ 前置条件任一不满足（没机器 / 没 buffer / 离线没网格 / 取不到 actionSource）
     * ⇒ <b>返回 null 且一个字节都不动</b>（调用方据此不打日志），绝不抛异常进 tick。
     *
     * @param priority   优先搬的键（{@code null} = 不指定；追写路径传"卡住的那个键"）
     * @param maxEntries 本轮条目预算（{@code <= 0} ⇒ 纯核用 64）
     */
    @Nullable
    static ShanhaiBufferFlush.Sweep sweepFreeFor(@Nullable MEPatternBufferPartMachineBase machine,
                                                 @Nullable Object2LongOpenHashMap<AEKey> buffer,
                                                 @Nullable AEKey priority,
                                                 int maxEntries) {
        if (machine == null || buffer == null || buffer.isEmpty()) {
            return null;
        }
        final IGrid grid;
        try {
            grid = machine.getGrid();
        } catch (Throwable t) {
            return null;
        }
        if (grid == null) {
            return null;
        }
        final MEStorage storage;
        try {
            storage = grid.getStorageService().getInventory();
        } catch (Throwable t) {
            return null;
        }
        if (storage == null) {
            return null;
        }
        final IActionSource source;
        try {
            source = actionSourceOf(machine);
        } catch (Throwable t) {
            return null;
        }
        if (source == null) {
            return null;
        }
        // 🔴 闸门：真正触达 poweredInsert 的那个 mover 只会收到 > 0 的量
        final ShanhaiBufferFlush.Mover<AEKey> insert = ShanhaiBufferFlush.guarded(
                (key, amount) -> StorageHelper.poweredInsert(
                        FREE_ENERGY, storage, key, amount, source, Actionable.MODULATE));
        return ShanhaiBufferFlush.sweep(buffer, insert, maxEntries, priority);
    }

    /**
     * 取本机自己的 {@code actionSource}（= 上游退款时用的同一个）。
     * <p>{@code MEIOPartMachine#actionSource} 是 {@code protected final} 字段
     * （{@code javap -p} 核过），跨包非子类拿不到，整条继承链上也没有公有 getter
     * ⇒ 只有本机这个子类能合法透出来（{@link StellarSampleAssemblerPartMachine#shanhaiActionSource()}，
     * 与同文件 {@code refundAll} 里那句 {@code this.actionSource} 逐字同源）。
     */
    @Nullable
    private static IActionSource actionSourceOf(@Nullable MEPatternBufferPartMachineBase machine) {
        if (machine instanceof StellarSampleAssemblerPartMachine ours) {
            return ours.shanhaiActionSource();
        }
        return null;
    }

    /**
     * <b>退款槽 → buffer 那一步的夹</b> —— {@code MEPatternBufferPartMachineBase#refundSlot} 的替身。
     *
     * <h2>为什么这条也要夹</h2>
     * 上游 {@code refundSlot(Object2LongOpenHashMap<AEItemKey>, Object2LongOpenHashMap<AEFluidKey>)}
     * 是 <b>protected</b>（{@code javap -p} 核过），它把每个内部槽里待退的料<b>裸加</b>进 buffer
     * （{@code Base.java:240 / :251}）。同一条溢出链（裸加 → 负数 → {@code reFunds} 偏移 99 静默删）在这里也成立
     * ⇒ 只夹产物写入等于留了个后门。本类同一份核把它一起兜住。
     *
     * <h2>语义与上游的唯一差别</h2>
     * 夹掉的那部分<b>写回源表</b>（{@code entry.setValue(left)}）而不是删条目 —— 料还留在槽里，下次还能退；
     * 详见 {@link ShanhaiBufferClamp#commitAll}。装得下时与上游<b>逐位一致</b>（整条搬走 + {@code it.remove()}）。
     *
     * @param itemInventory  内部槽的物品待退表（可为 null）
     * @param fluidInventory 内部槽的流体待退表（可为 null）
     */
    static void refundInto(@NotNull Object2LongOpenHashMap<AEKey> buffer,
                           @Nullable Object2LongOpenHashMap<AEItemKey> itemInventory,
                           @Nullable Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        ShanhaiBufferClamp.commitAll(buffer, itemInventory, k -> k, ShanhaiBufClampTrait::reportClamped);
        ShanhaiBufferClamp.commitAll(buffer, fluidInventory, k -> k, ShanhaiBufClampTrait::reportClamped);
    }

    /** 现取 buffer（<b>不缓存</b>）；取不到返回 null（调用方退回上游原行为）。 */
    @Nullable
    private static Object2LongOpenHashMap<AEKey> bufOrNull(@Nullable MEPatternBufferPartMachineBase machine) {
        if (machine == null) {
            return null;
        }
        try {
            return machine.getBuffer();
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 限流日志（两档各 5 秒 1 行）

    /**
     * <b>WARN 档</b>的 5 秒闸（真丢 / 读不到 buffer 共用）。
     *
     * <p>不追求严格，只要求"不刷屏"；用 {@code System.currentTimeMillis()} 而不是机器 tick，
     * 因为本类在构造期就被创建，拿不到 tick 上下文，也不该把日志时机绑在某台机器的 tick 上。
     */
    private static boolean windowWarn() {
        final long now = System.currentTimeMillis();
        if (!ShanhaiBufferFlush.windowDue(now, lastWarnMs)) {
            return false;
        }
        lastWarnMs = now;
        return true;
    }

    /**
     * <b>回灌夹取（INFO）档</b>的 5 秒闸。
     *
     * <p>与 {@link #windowWarn()} <b>分开</b>：那一档是告警，这一档只是读数 ——
     * 绝不许一条 INFO 把真丢的 WARN 挤掉（用户口径：WARN 就是真的丢了）。
     */
    private static boolean windowClampInfo() {
        final long now = System.currentTimeMillis();
        if (!ShanhaiBufferFlush.windowDue(now, lastClampInfoMs)) {
            return false;
        }
        lastClampInfoMs = now;
        return true;
    }

    /**
     * <b>{@code bufflush CHASE}（INFO）档</b>的 5 秒闸（与上面两个窗口各自独立）。
     *
     * <p>它证明的是"追写真的发生在写不进去的那一瞬"，属于证据而非告警 ⇒ 走 INFO、且不占 WARN 的窗口。
     */
    private static boolean windowChaseInfo() {
        final long now = System.currentTimeMillis();
        if (!ShanhaiBufferFlush.windowDue(now, lastChaseInfoMs)) {
            return false;
        }
        lastChaseInfoMs = now;
        return true;
    }

    /** <b>WARN 落笔</b>（真丢 / 读不到 buffer）。WARN 级别只从这里出，判据在纯核 {@code writeEvents}。 */
    private static void reportWarn(@Nullable String line) {
        if (line == null || !windowWarn()) {
            return;
        }
        try {
            ShanhaiStellarLog.warn("{}", line);
        } catch (Throwable ignored) {
            // 日志永远不许把产出链带崩
        }
    }

    /**
     * <b>{@code bufflush CHASE} 那行（INFO）</b>：追写跑过之后的证据。
     *
     * <p>{@code rescued=1} = 整笔救回来（这一单一条 WARN 都没有）；{@code rescued=0} = 还丢了
     * （同一时刻会有一行带 {@code rescue=failed} 的 WARN，两行配对看）。
     */
    private static void reportChaseInfo(@Nullable String line) {
        if (line == null || !windowChaseInfo()) {
            return;
        }
        try {
            ShanhaiStellarLog.info("{}", line);
        } catch (Throwable ignored) {
            // 日志永远不许把产出链带崩
        }
    }

    /**
     * 本机位置串（{@code <维度>@x,y,z}）—— 与机器那几条日志<b>同一份实现</b>
     * （{@code StellarSampleAssemblerPartMachine#shanhaiPos()} 借一个包内转发方法，
     * 不在这里重复拼字符串，免得两处口径飘）。
     *
     * @return {@code null} = 不是我们的机器 / 取不到（日志里写 {@code ?}）
     */
    @Nullable
    private static String posOf(@Nullable MEPatternBufferPartMachineBase machine) {
        if (machine instanceof StellarSampleAssemblerPartMachine ours) {
            try {
                return ours.shanhaiPosForTrait();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * <b>回灌槽被夹那行（INFO）</b>—— 上游 {@code refundSlot} 那条路的夹取读数。
     *
     * <p>为什么是 <b>INFO 不是 WARN</b>：{@link ShanhaiBufferClamp#commitAll} 把被夹掉的余量
     * <b>写回源表</b>（{@code entry.setValue(left)}），料还留在槽里、下次还能退 ⇒ <b>这不是丢</b>。
     * 只有产物写入那条路（没有重试路径）才可能真丢，那一条由 {@link ShanhaiBufferFlush#writeEvents}
     * 判定并走 WARN。
     */
    private static void reportClamped(@NotNull AEKey key, long existing, long want, long write) {
        if (!windowClampInfo()) {
            return;
        }
        try {
            ShanhaiStellarLog.info("bufclamp key={} existing={} want={} write={} dropped={}",
                    keyId(key),
                    Long.valueOf(existing),
                    Long.valueOf(want),
                    Long.valueOf(write),
                    Long.valueOf(want - write));
        } catch (Throwable ignored) {
            // 日志永远不许把产出链带崩
        }
    }

    /** 日志里的 {@code key=<id>}：优先取 AE 的规范化 id（{@code namespace:path}），取不到退回 {@code toString()}。 */
    @NotNull
    static String keyId(@NotNull AEKey key) {
        try {
            final ResourceLocation id = key.getId();
            if (id != null) {
                return id.toString();
            }
        } catch (Throwable ignored) {
            // 退化到 toString
        }
        try {
            return String.valueOf(key);
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * <b>{@code bufflush BUFNEG} 那行</b>：把 {@link ShanhaiBufferFlush.Sweep#negKey} 解析成可读 id，
     * 正文交给纯核 {@link ShanhaiBufferFlush#negLine}（可离线断言）。
     *
     * <p>解析留在本类（要碰 {@code AEKey#getId()}）；{@code value} 才是关键信息，
     * 所以 id 取不到也照打那一行（写 {@code ?}）。
     *
     * @return 没有负值 ⇒ {@code null}（一次都不许误报）
     */
    @Nullable
    static String negLineOf(@Nullable String pos, @Nullable ShanhaiBufferFlush.Sweep sweep) {
        if (sweep == null || sweep.negatives <= 0) {
            return null;
        }
        String id = null;
        try {
            if (sweep.negKey instanceof AEKey key) {
                id = keyId(key);
            }
        } catch (Throwable ignored) {
            // 取不到 id 也要把这行打出来
        }
        return ShanhaiBufferFlush.negLine(pos, sweep, id);
    }

    /** {@code bufclamp unreadable}：取不到 buffer 就没法夹 ⇒ 这是真告警，走 WARN 档那一个窗口。 */
    private static void reportUnreadable() {
        reportWarn("bufclamp unreadable");
    }
}
