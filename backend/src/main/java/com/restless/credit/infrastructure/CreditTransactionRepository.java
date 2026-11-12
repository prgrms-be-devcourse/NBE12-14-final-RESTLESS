package com.restless.credit.infrastructure;

import com.restless.credit.domain.CreditTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

    List<CreditTransaction> findByCreditWalletIdOrderByIdAsc(Long creditWalletId);
}
