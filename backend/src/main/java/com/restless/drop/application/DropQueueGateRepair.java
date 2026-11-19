package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueGateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropQueueGateRepair {
    private final DropQueueGateRepository gates;

    // Called only after the admission transaction has ended. Concurrent repairs cannot hold
    // an outer admission lock while INSERT IGNORE resolves the duplicate primary key.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 3)
    public void createIfMissing(Long variantId) {
        gates.createIfMissing(variantId);
    }
}
