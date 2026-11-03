package co.com.finova.wallet.model.settlement.gateways;

import co.com.finova.wallet.model.settlement.TransferSettlement;

import java.util.Optional;

public interface TransferSettlementRepository {

    /**
     * Claims the settlement of a transfer through its primary key (spec §4.7).
     *
     * @return empty when this call claimed it; otherwise the settlement that already existed and won
     */
    Optional<TransferSettlement> claim(TransferSettlement settlement);
}
