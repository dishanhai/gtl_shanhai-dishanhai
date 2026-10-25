package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import java.math.BigInteger;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import com.gtladd.gtladditions.common.machine.trait.NetworkEnergyContainer;

/** 山海：遵循原生封裝後的符號，負數扣 IN、正數存 OUT。 */
public final class GuixuEnergyHandler implements IWirelessNetworkEnergyHandler {
    public enum Failure { NONE, DISCONNECTED, INPUT, OUTPUT, TRANSACTION }
    private final Supplier<List<IEnergyContainer>> inputs, outputs;
    private final BooleanSupplier online, remote;
    private final Function<IEnergyContainer, Object> inputSource;
    private Failure lastFailure = Failure.NONE;

    public Failure getLastFailure() { return lastFailure; }

    public GuixuEnergyHandler(GuixuVisionCoreMachine host) {
        this(() -> host.getGuixuEnergyContainers(IO.IN), () -> host.getGuixuEnergyContainers(IO.OUT),
                host::isFormed, host::isRemote);
    }

    GuixuEnergyHandler(Supplier<List<IEnergyContainer>> inputs, Supplier<List<IEnergyContainer>> outputs,
                       BooleanSupplier online, BooleanSupplier remote) {
        this(inputs, outputs, online, remote, GuixuEnergyHandler::sharedInputSource);
    }

    GuixuEnergyHandler(Supplier<List<IEnergyContainer>> inputs, Supplier<List<IEnergyContainer>> outputs,
                       BooleanSupplier online, BooleanSupplier remote, Function<IEnergyContainer, Object> inputSource) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.online = online;
        this.remote = remote;
        this.inputSource = inputSource;
    }

    private static Object sharedInputSource(IEnergyContainer container) {
        return container instanceof NetworkEnergyContainer network ? network.getMachine().getUUID() : null;
    }

    /** 無線輸入終端同 UUID 共用餘額；一般倉室是各自緩衝，必須保留 identity 語義。 */
    private List<IEnergyContainer> uniqueInputs() {
        var result = new ArrayList<IEnergyContainer>();
        var pools = new HashSet<Object>();
        var independent = Collections.newSetFromMap(new IdentityHashMap<IEnergyContainer, Boolean>());
        for (IEnergyContainer container : inputs.get()) {
            if (container == null) continue;
            Object source = inputSource.apply(container);
            if (source == null ? independent.add(container) : pools.add(source)) result.add(container);
        }
        return result;
    }

    @Override public boolean isOnline() { return online.getAsBoolean(); }
    @Override public boolean consumeEnergy(int amount) { return consumeEnergy(BigInteger.valueOf(amount)); }
    @Override public boolean consumeEnergy(long amount) { return consumeEnergy(BigInteger.valueOf(amount)); }

    @Override
    public BigInteger getMaxAvailableEnergy() {
        BigInteger result = BigInteger.ZERO;
        for (IEnergyContainer container : uniqueInputs()) {
            result = result.add(BigInteger.valueOf(Math.max(0L, container.getEnergyStored())));
        }
        return result;
    }

    @Override
    public boolean consumeEnergy(BigInteger amount) {
        lastFailure = Failure.NONE;
        if (remote.getAsBoolean() || !isOnline()) {
            lastFailure = Failure.DISCONNECTED;
            return false;
        }
        // buildWirelessRecipe 會對 totalEu / duration 再 negate，不能沿用配方建構前的符號。
        boolean extracting = amount.signum() < 0;
        List<IEnergyContainer> containers = extracting ? uniqueInputs() : outputs.get();
        long[] available = new long[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            IEnergyContainer container = containers.get(i);
            long stored = Math.max(0L, container.getEnergyStored());
            available[i] = extracting ? stored : Math.max(0L, container.getEnergyCapacity() - stored);
        }
        long[] plan = GuixuEnergyTransfer.plan(amount.abs(), available);
        if (plan == null) {
            lastFailure = extracting ? Failure.INPUT : Failure.OUTPUT;
            return false;
        }
        long[] applied = new long[plan.length];
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] == 0L) continue;
            long delta = extracting ? -plan[i] : plan[i];
            applied[i] = containers.get(i).changeEnergy(delta);
            if (applied[i] != delta) {
                lastFailure = Failure.TRANSACTION;
                // 原生倉室在同一服務端執行緒預檢後應可完整結算；拒絕部分成交。
                for (int j = i; j >= 0; j--) {
                    if (applied[j] != 0L && containers.get(j).changeEnergy(-applied[j]) != -applied[j]) {
                        throw new IllegalStateException("[SHANHAI-GUIXU] 能源倉違反回滾契約");
                    }
                }
                return false;
            }
        }
        return true;
    }
}
