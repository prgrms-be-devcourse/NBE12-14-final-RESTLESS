package com.restless.drop.infrastructure;

import com.restless.drop.domain.Drop;
import com.restless.drop.domain.DropStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DropRepository extends JpaRepository<Drop, Long> {
    List<Drop> findAllByStatusInOrderByOpensAtAscIdAsc(Collection<DropStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drop d where d.id = :id")
    Optional<Drop> findByIdForUpdate(Long id);
}
