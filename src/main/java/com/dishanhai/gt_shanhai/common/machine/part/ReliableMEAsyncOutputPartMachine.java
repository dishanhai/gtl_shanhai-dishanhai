package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEExtendedOutputPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;

/**
 * Reliable replacement for GTLCore's async export buffer.
 * Outputs are accepted only after they enter the persisted buffer inherited from the normal extended export buffer.
 */
public class ReliableMEAsyncOutputPartMachine extends MEExtendedOutputPartMachine implements DShanhaiAENetworkMachine {

    public ReliableMEAsyncOutputPartMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    protected void registerDefaultServices() {
        getMainNode().addService(IGridTickable.class, new ReliableTicker());
    }

    @Override
    public String getAeJadeKind() {
        return "可靠 ME 输出总成";
    }

    @Override
    public int getAeStockedSlots() {
        return buffer.size();
    }

    protected class ReliableTicker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(1, 20, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!getMainNode().isActive()) {
                isSleeping = true;
                return TickRateModulation.SLEEP;
            }
            if (buffer.isEmpty()) {
                if (ticksSinceLastCall >= 20) {
                    isSleeping = true;
                    return TickRateModulation.SLEEP;
                }
                return TickRateModulation.SLOWER;
            }
            return AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource)
                    ? TickRateModulation.URGENT
                    : TickRateModulation.SLOWER;
        }
    }
}
