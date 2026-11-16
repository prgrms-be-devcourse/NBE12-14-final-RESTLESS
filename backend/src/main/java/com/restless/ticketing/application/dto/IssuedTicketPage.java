package com.restless.ticketing.application.dto;

import java.util.List;

public record IssuedTicketPage(List<IssuedTicketInfo> content, int page, int size, long totalElements, int totalPages) {
    public IssuedTicketPage {
        content = List.copyOf(content);
    }
}
