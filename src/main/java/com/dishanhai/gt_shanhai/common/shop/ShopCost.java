package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店商品的<b>多元成本</b>（山海署名）。五条支付通道并存、互不冲突：
 * <ul>
 *   <li>{@link #spark}：星火 → 钱包<b>数字余额</b>（BigInteger 真无限）</li>
 *   <li>{@link #coins}：币种 → 钱包<b>币种余额</b>（可多种，维持旧商店钱包结算语义）</li>
 *   <li>{@link #physical} 中 {@code isFluid=false}：物品 → <b>背包实物</b>消耗（同兑换）</li>
 *   <li>{@link #physical} 中 {@code isFluid=true}：流体 → 绑定 <b>AE 抽取</b>（同兑换）</li>
 *   <li>{@link #eu}：EU → 玩家绑定的<b>无线电网</b>余额（gtladditions/gtmthings，见 {@link ShopWirelessEu}）</li>
 * </ul>
 * 复用 {@link ExchangeEntry.Ingredient} 承载物品/流体成分。旧 shop.json 的单币条目
 * 由 {@link #singleCoin} 迁移成「1 个币种成分」。
 */
public final class ShopCost {

    public final BigInteger spark;
    public final LinkedHashMap<ResourceLocation, BigInteger> coins;   // 钱包币种 → 数量
    public final List<ExchangeEntry.Ingredient> physical;            // 物品(isFluid=false)/流体(isFluid=true)
    public final BigInteger eu;                                       // 无线电网 EU（第5通道，见 ShopWirelessEu）

    public ShopCost(BigInteger spark, Map<ResourceLocation, BigInteger> coins, List<ExchangeEntry.Ingredient> physical) {
        this(spark, coins, physical, BigInteger.ZERO);
    }

    public ShopCost(BigInteger spark, Map<ResourceLocation, BigInteger> coins, List<ExchangeEntry.Ingredient> physical, BigInteger eu) {
        this.spark = spark == null || spark.signum() < 0 ? BigInteger.ZERO : spark;
        this.coins = new LinkedHashMap<>();
        if (coins != null) {
            for (Map.Entry<ResourceLocation, BigInteger> e : coins.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue().signum() > 0) {
                    this.coins.put(e.getKey(), e.getValue());
                }
            }
        }
        this.physical = new ArrayList<>();
        if (physical != null) {
            for (ExchangeEntry.Ingredient in : physical) {
                if (in != null && in.id != null && in.count > 0) this.physical.add(in);
            }
        }
        this.eu = eu == null || eu.signum() < 0 ? BigInteger.ZERO : eu;
    }

    /** 旧格式迁移：单一 currency + price。SPARK → 星火通道，否则 → 币种通道。 */
    public static ShopCost singleCoin(ResourceLocation currency, long price) {
        if (currency == null || price <= 0L) return new ShopCost(BigInteger.ZERO, null, null);
        if (WalletAccountAPI.isSpark(currency)) {
            return new ShopCost(BigInteger.valueOf(price), null, null);
        }
        LinkedHashMap<ResourceLocation, BigInteger> c = new LinkedHashMap<>();
        c.put(currency, BigInteger.valueOf(price));
        return new ShopCost(BigInteger.ZERO, c, null);
    }

    public boolean isEmpty() {
        return spark.signum() <= 0 && coins.isEmpty() && physical.isEmpty() && eu.signum() <= 0;
    }

    /** 有效性：非空 + 所有币种/物品/流体 ID 在注册表中存在（星火除外，无对应物品）。 */
    public boolean isValid() {
        if (isEmpty()) return false;
        for (ResourceLocation id : coins.keySet()) {
            if (!ForgeRegistries.ITEMS.containsKey(id)) return false;
        }
        for (ExchangeEntry.Ingredient in : physical) {
            boolean ok = in.isFluid ? ForgeRegistries.FLUIDS.containsKey(in.id)
                    : ForgeRegistries.ITEMS.containsKey(in.id);
            if (!ok) return false;
        }
        return true;
    }

    /** 币种/物品/流体成分里是否有任意一项已经不在当前注册表（模组缺失）。仅用于展示判断，购买拦截见 {@link #isValid}。 */
    public boolean hasMissingIngredient() {
        for (ResourceLocation id : coins.keySet()) {
            if (!ForgeRegistries.ITEMS.containsKey(id)) return true;
        }
        for (ExchangeEntry.Ingredient in : physical) {
            boolean ok = in.isFluid ? ForgeRegistries.FLUIDS.containsKey(in.id)
                    : ForgeRegistries.ITEMS.containsKey(in.id);
            if (!ok) return true;
        }
        return false;
    }

    /** 是否含实物成本（物品/流体）。出售仅对纯钱包成本开放。 */
    public boolean hasPhysical() {
        return !physical.isEmpty();
    }

    /** 纯钱包成本（星火/币种，无实物）→ 支持出售。 */
    public boolean isPureWallet() {
        return physical.isEmpty();
    }

    /** 只含单一币种、无星火无实物（等价旧单币条目）→ 兼容旧显示/出售。 */
    public boolean isSingleCoin() {
        return spark.signum() <= 0 && physical.isEmpty() && coins.size() == 1;
    }

    /** 纯星火成本（无币种无实物）。 */
    public boolean isPureSpark() {
        return spark.signum() > 0 && coins.isEmpty() && physical.isEmpty();
    }

    /** 成分总数（星火 + 币种数 + 实物数 + EU），供 grid 摘要判断「+N」。 */
    public int componentCount() {
        return (spark.signum() > 0 ? 1 : 0) + coins.size() + physical.size() + (eu.signum() > 0 ? 1 : 0);
    }

    public List<ExchangeEntry.Ingredient> items() {
        List<ExchangeEntry.Ingredient> r = new ArrayList<>();
        for (ExchangeEntry.Ingredient in : physical) if (!in.isFluid) r.add(in);
        return r;
    }

    public List<ExchangeEntry.Ingredient> fluids() {
        List<ExchangeEntry.Ingredient> r = new ArrayList<>();
        for (ExchangeEntry.Ingredient in : physical) if (in.isFluid) r.add(in);
        return r;
    }
}
