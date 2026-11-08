package com.anbar.wms.purchasing.repository;

import com.anbar.wms.common.repository.AppendOnlyRepository;
import com.anbar.wms.purchasing.entity.GoodsReceipt;
import java.util.List;
import java.util.Optional;

public interface GoodsReceiptRepository extends AppendOnlyRepository<GoodsReceipt, Long> {

    Optional<GoodsReceipt> findByIdempotencyKey(String idempotencyKey);

    List<GoodsReceipt> findByPurchaseOrderIdOrderByReceivedAtAsc(Long purchaseOrderId);

    <S extends GoodsReceipt> S saveAndFlush(S receipt);
}
