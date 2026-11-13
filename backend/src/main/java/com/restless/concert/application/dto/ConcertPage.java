package com.restless.concert.application.dto;

import java.util.List;

public record ConcertPage(List<ConcertDetail> content, int page, int size, long totalElements, int totalPages) {
    public ConcertPage {
        content = List.copyOf(content);
    }
}
