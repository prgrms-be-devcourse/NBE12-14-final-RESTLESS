package com.restless.credit.application;

import com.restless.credit.domain.CreditHold;
import com.restless.credit.domain.CreditHoldStatus;
import com.restless.credit.domain.CreditTransaction;
import com.restless.credit.domain.CreditTransactionType;
import com.restless.credit.domain.CreditWallet;
import com.restless.credit.infrastructure.CreditHoldRepository;
import com.restless.credit.infrastructure.CreditTransactionRepository;
import com.restless.credit.infrastructure.CreditWalletRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크레딧 지갑: 지급, 확보, 해제, 차감 (docs/domains/auction/transactions.md).
 *
 * <p>확보, 해제, 차감은 입찰·종료 트랜잭션 안에서 불린다({@link Propagation#MANDATORY}).
 * 호출하는 쪽은 경매 행을 먼저 잠그고, 지갑이 둘 이상이면 {@link #lockWallets}로 지갑 ID 오름차순으로 잠근다.
 * 모든 변동은 같은 트랜잭션에서 {@code credit_transactions}에 한 건씩 기록한다 (INV-AU-14, 15).
 */
@Service
@RequiredArgsConstructor
public class CreditWalletService {

    /** 테스트용 지급 버튼 한 번의 금액. */
    public static final long TEST_GRANT_AMOUNT = 10_000L;

    private final CreditWalletRepository wallets;
    private final CreditHoldRepository holds;
    private final CreditTransactionRepository transactions;
    private final Clock clock;

    /** 내 지갑. 지갑이 없으면 모두 0이다. */
    @Transactional(readOnly = true)
    public WalletView getWallet(Long userId) {
        return wallets.findByUserId(userId).map(WalletView::of).orElse(WalletView.EMPTY);
    }

    /** 테스트용 지급 (TX-AU-1). 지갑이 없으면 만든다. 누를 때마다 지급한다. */
    @Transactional
    public WalletView grantTestCredit(Long userId) {
        Instant now = clock.instant();
        wallets.insertIfAbsent(userId, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        CreditWallet wallet = wallets.findByUserIdForUpdate(userId).orElseThrow();
        wallet.grant(TEST_GRANT_AMOUNT, now);
        transactions.save(CreditTransaction.record(wallet, null, CreditTransactionType.GRANT,
                TEST_GRANT_AMOUNT, 0, "GRANT:" + UUID.randomUUID(), now));
        return WalletView.of(wallet);
    }

    /**
     * 여러 회원의 지갑을 지갑 ID 오름차순으로 잠근다. 지갑이 없는 회원은 건너뛴다.
     * 입찰은 새 입찰자와 이전 최고 입찰자의 지갑을 이 메서드로 먼저 잠근다 (데드락 방지).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockWallets(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        for (Long walletId : wallets.findIdsByUserIdIn(userIds)) {
            wallets.findByIdForUpdate(walletId).orElseThrow();
        }
    }

    /**
     * 입찰 금액을 묶는다. 사용 가능 크레딧이 부족하거나 지갑이 없으면 CREDIT_INSUFFICIENT.
     *
     * @return 만든 확보
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditHold hold(Long userId, Long auctionBidId, long amount) {
        Instant now = clock.instant();
        CreditWallet wallet = wallets.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT_INSUFFICIENT));
        wallet.reserve(amount, now);
        CreditHold hold = holds.save(CreditHold.held(wallet.getId(), auctionBidId, amount, now));
        transactions.save(CreditTransaction.record(wallet, hold.getId(), CreditTransactionType.HOLD,
                0, amount, "HOLD:" + auctionBidId, now));
        return hold;
    }

    /** 추월된 입찰의 확보를 푼다. 이미 RELEASED면 아무것도 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long auctionBidId) {
        CreditHold hold = holds.findByAuctionBidId(auctionBidId).orElseThrow();
        if (hold.getStatus() == CreditHoldStatus.RELEASED) {
            return;
        }
        Instant now = clock.instant();
        CreditWallet wallet = wallets.findByIdForUpdate(hold.getCreditWalletId()).orElseThrow();
        hold.release(now);
        wallet.release(hold.getHeldAmount(), now);
        transactions.save(CreditTransaction.record(wallet, hold.getId(), CreditTransactionType.RELEASE,
                0, -hold.getHeldAmount(), "RELEASE:" + hold.getId(), now));
    }

    /** 낙찰된 입찰의 확보를 보유액에서 차감한다. 이미 CAPTURED면 아무것도 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(Long auctionBidId) {
        CreditHold hold = holds.findByAuctionBidId(auctionBidId).orElseThrow();
        if (hold.getStatus() == CreditHoldStatus.CAPTURED) {
            return;
        }
        Instant now = clock.instant();
        CreditWallet wallet = wallets.findByIdForUpdate(hold.getCreditWalletId()).orElseThrow();
        hold.capture(now);
        wallet.capture(hold.getHeldAmount(), now);
        transactions.save(CreditTransaction.record(wallet, hold.getId(), CreditTransactionType.CAPTURE,
                -hold.getHeldAmount(), -hold.getHeldAmount(), "CAPTURE:" + hold.getId(), now));
    }

    public record WalletView(long balanceAmount, long reservedAmount, long availableAmount) {
        static final WalletView EMPTY = new WalletView(0, 0, 0);

        static WalletView of(CreditWallet wallet) {
            return new WalletView(wallet.getBalanceAmount(), wallet.getReservedAmount(), wallet.availableAmount());
        }
    }
}
