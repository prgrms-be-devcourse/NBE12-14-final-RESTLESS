package com.restless.ticketing.api;

import com.restless.auth.LoginMember;
import com.restless.ticketing.application.TicketQueryService;
import com.restless.ticketing.application.dto.IssuedTicketInfo;
import com.restless.ticketing.application.dto.IssuedTicketPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketQueryService tickets;

    @GetMapping
    public IssuedTicketPage getList(@LoginMember Long userId,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        return tickets.getList(userId, page, size);
    }

    @GetMapping("/{ticketId}")
    public IssuedTicketInfo getDetail(@LoginMember Long userId, @PathVariable("ticketId") Long ticketId) {
        return tickets.getDetail(userId, ticketId);
    }
}
