package com.restless.credit.api;

import com.restless.credit.application.CreditWalletService;
import com.restless.credit.application.CreditWalletService.WalletView;
import com.restless.auth.LoginMember;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 크레딧 지갑 조회. 지급 버튼은 {@link TestCreditGrantController}에 있다. */
@RestController
@RequestMapping("/api/me/wallet")
@RequiredArgsConstructor
public class CreditWalletController {

    private final CreditWalletService creditWalletService;

    @GetMapping
    public WalletView myWallet(@LoginMember Long userId) {
        return creditWalletService.getWallet(userId);
    }
}
