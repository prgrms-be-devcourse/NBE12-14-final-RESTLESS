package com.restless.auction.api;

import com.restless.auction.application.CreditWalletService;
import com.restless.auction.application.CreditWalletService.WalletView;
import com.restless.auth.LoginMember;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테스트용 크레딧 지급 버튼. 누를 때마다 10,000을 준다.
 * 운영(prod) 프로필에서는 빈으로 등록하지 않으므로 이 경로는 404다. PG 크레딧 구매가 생기면 지운다.
 */
@RestController
@Profile("!prod")
@RequiredArgsConstructor
public class TestCreditGrantController {

    private final CreditWalletService creditWalletService;

    @PostMapping("/api/me/wallet/grants")
    public WalletView grant(@LoginMember Long userId) {
        return creditWalletService.grantTestCredit(userId);
    }
}
