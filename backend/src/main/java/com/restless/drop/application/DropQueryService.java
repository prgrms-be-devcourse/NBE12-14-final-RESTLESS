package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.DropCatalogResponse.*;
import com.restless.drop.application.dto.DropListSort;
import com.restless.drop.domain.DropStatus;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropQueryService {
    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropInventoryRepository inventories;
    private final Clock clock;

    public Page getList() {
        return getList(null, null);
    }

    public Page getList(String q, String sort) {
        String query = q == null ? "" : q.strip();
        if (query.codePointCount(0, query.length()) > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var ordering = DropListSort.parse(sort).order();
        // An explicit escape character keeps SQL wildcards and backslashes literal.
        String pattern = "%" + query.replace("!", "!!").replace("\\", "!\\")
                .replace("%", "!%").replace("_", "!_") + "%";
        var now = clock.instant();
        return new Page(drops.searchPublishedTitles(
                List.of(DropStatus.SCHEDULED, DropStatus.OPEN, DropStatus.CLOSED), pattern, ordering).stream()
                .map(d -> new Item(d.getId(), d.getTitle(), d.getImageUrl(), d.getOpensAt(),
                        d.getClosesAt(), d.saleStatus(now))).toList());
    }

    public Detail getDetail(Long dropId) {
        var drop = drops.findById(dropId).filter(d -> d.getStatus().isPublished())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var skus = variants.findAllByDropIdOrderByIdAsc(dropId);
        // soldOut = the SKU's bucket sum is 0 (ADR-0005), read for all SKUs in one GROUP BY.
        var ids = skus.stream().map(v -> v.getId()).toList();
        Map<Long, Long> available = ids.isEmpty() ? Map.of() : inventories.availableByVariant(ids).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> ((Number) row[1]).longValue()));
        var result = skus.stream().map(v -> {
            var stock = available.get(v.getId());
            if (stock == null) throw new IllegalStateException("Drop inventory is missing");
            return new Variant(v.getId(), v.getSkuCode(), v.getOptionName(), v.getPriceAmount(), stock == 0);
        }).toList();
        return new Detail(drop.getId(), drop.getTitle(), drop.getDescription(), drop.getImageUrl(),
                drop.getOpensAt(), drop.getClosesAt(), drop.saleStatus(clock.instant()), result);
    }
}
