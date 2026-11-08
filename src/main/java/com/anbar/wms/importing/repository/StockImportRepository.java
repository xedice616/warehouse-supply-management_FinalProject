package com.anbar.wms.importing.repository;

import com.anbar.wms.common.repository.AppendOnlyRepository;
import com.anbar.wms.importing.entity.StockImport;
import java.util.Optional;

public interface StockImportRepository extends AppendOnlyRepository<StockImport, Long> {

    Optional<StockImport> findByIdempotencyKey(String idempotencyKey);

    <S extends StockImport> S saveAndFlush(S stockImport);
}
