package com.restless.auction.infrastructure;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.AuctionStatus;
import jakarta.persistence.LockModeType;
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
}
