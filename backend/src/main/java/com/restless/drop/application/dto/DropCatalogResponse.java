package com.restless.drop.application.dto;

import com.restless.drop.domain.DropStatus;
import java.time.Instant;
import java.util.List;

public final class DropCatalogResponse {
    private DropCatalogResponse() {}

    public record Admin(Long dropId, DropStatus status) {}
    public record Page(List<Item> items) {}
    public record Item(Long dropId, String title, String imageUrl, Instant opensAt, Instant closesAt,
                       String saleStatus) {}
    public record Detail(Long dropId, String title, String description, String imageUrl,
                         Instant opensAt, Instant closesAt, String saleStatus, List<Variant> variants) {}
    public record Variant(Long variantId, String skuCode, String optionName, long priceAmount, boolean soldOut) {}
}
