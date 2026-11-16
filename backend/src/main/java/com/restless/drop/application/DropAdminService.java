package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.DropCatalogResponse.Admin;
import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropAdminService {
    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropInventoryRepository inventories;
    private final ConcertQueryService concerts;
    private final Clock clock;

    @Transactional
    public Admin create(CreateDropRequest request) {
        var now = clock.instant();
        if (!request.opensAt().isBefore(request.closesAt()) || !request.closesAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var codes = new HashSet<String>();
        for (var sku : request.variants()) {
            if (!codes.add(sku.skuCode())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "SKU 코드가 중복됩니다.");
            }
        }
        concerts.getDetail(request.concertId());
        Drop drop = drops.save(Drop.draft(request.concertId(), request.title(), request.description(),
                request.imageUrl(), request.opensAt(), request.closesAt(), now));
        for (var sku : request.variants()) {
            DropVariant variant;
            try {
                variant = variants.save(DropVariant.create(drop.getId(), sku.skuCode(),
                        sku.optionName(), sku.priceAmount()));
            } catch (DataIntegrityViolationException e) {
                // The database collation also considers some different Unicode spellings equal.
                if (e.getCause() instanceof ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().endsWith("uk_drop_variants_sku")) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "SKU 코드가 중복됩니다.");
                }
                throw e;
            }
            inventories.save(DropInventory.initial(variant.getId(), sku.totalQuantity()));
        }
        return new Admin(drop.getId(), drop.getStatus());
    }

    @Transactional
    public Admin publish(Long dropId) {
        Drop drop = drops.findByIdForUpdate(dropId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        drop.publish();
        return new Admin(drop.getId(), drop.getStatus());
    }
}
