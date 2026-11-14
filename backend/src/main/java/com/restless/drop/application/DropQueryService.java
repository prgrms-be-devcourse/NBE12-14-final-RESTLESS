package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.DropCatalogResponse.*;
import com.restless.drop.application.dto.DropListSort;
import com.restless.drop.domain.DropStatus;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;
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
        var stock = inventories.findAllById(skus.stream().map(v -> v.getId()).toList()).stream()
                .collect(Collectors.toMap(i -> i.getVariantId(), Function.identity()));
        var result = skus.stream().map(v -> {
            var inventory = stock.get(v.getId());
            if (inventory == null) throw new IllegalStateException("Drop inventory is missing");
            return new Variant(v.getId(), v.getSkuCode(), v.getOptionName(), v.getPriceAmount(), inventory.isSoldOut());
        }).toList();
        return new Detail(drop.getId(), drop.getTitle(), drop.getDescription(), drop.getImageUrl(),
                drop.getOpensAt(), drop.getClosesAt(), drop.saleStatus(clock.instant()), result);
    }
}
