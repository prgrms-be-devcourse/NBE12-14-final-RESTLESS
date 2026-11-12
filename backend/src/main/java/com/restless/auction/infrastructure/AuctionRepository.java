package com.restless.auction.infrastructure;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.AuctionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    /** 경매 행을 잠근다. 수정·삭제·입찰·종료는 이 조회로 시작한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auction a where a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);

    Page<Auction> findByStatus(AuctionStatus status, Pageable pageable);

    /** 시작 시각이 지난 예정 경매. 인덱스 (status, starts_at). */
    @Query("select a.id from Auction a where a.status = :status and a.startsAt <= :now order by a.startsAt")
    List<Long> findIdsToOpen(@Param("status") AuctionStatus status, @Param("now") Instant now, Pageable limit);

    /** 종료 시각이 지났는데 아직 종료되지 않은 경매. 인덱스 (status, ends_at). */
    @Query("select a.id from Auction a where a.status in :statuses and a.endsAt <= :now order by a.endsAt")
    List<Long> findIdsToClose(@Param("statuses") Collection<AuctionStatus> statuses, @Param("now") Instant now,
                              Pageable limit);
}
