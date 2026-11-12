package com.restless.credit.infrastructure;

import com.restless.credit.domain.CreditWallet;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditWalletRepository extends JpaRepository<CreditWallet, Long> {

    /** 잠그지 않는 조회. 화면 표시용이다. 금액을 바꿀 때는 쓰지 않는다. */
    Optional<CreditWallet> findByUserId(Long userId);

    /** 잠금 순서를 정하려고 지갑 ID만 읽는다. 엔티티를 영속성 컨텍스트에 올리지 않는다. */
    @Query("select w.id from CreditWallet w where w.userId in :userIds order by w.id")
    List<Long> findIdsByUserIdIn(@Param("userIds") Collection<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from CreditWallet w where w.id = :id")
    Optional<CreditWallet> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from CreditWallet w where w.userId = :userId")
    Optional<CreditWallet> findByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * 지갑이 없으면 만든다. 동시에 여러 번 불려도 UNIQUE(user_id) 때문에 하나만 생기고, 나머지는 아무것도 하지 않는다.
     * 예외를 내지 않으므로 같은 트랜잭션에서 이어서 쓸 수 있다. {@code now}는 UTC 시각이다.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO credit_wallets (user_id, balance_amount, reserved_amount, created_at, updated_at)
            VALUES (:userId, 0, 0, :now, :now)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """)
    void insertIfAbsent(@Param("userId") Long userId, @Param("now") LocalDateTime nowUtc);
}
