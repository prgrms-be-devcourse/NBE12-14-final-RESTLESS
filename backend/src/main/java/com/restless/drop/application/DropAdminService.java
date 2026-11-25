package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.OrganizerConcertService;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.DropCatalogResponse.Admin;
import com.restless.drop.application.dto.DropCatalogResponse.Managed;
import com.restless.drop.application.dto.DropCatalogResponse.ManagedPage;
import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropAdminService {
    static final ZoneId ORGANIZER_ZONE = ZoneId.of("Asia/Seoul");
    static final int FIRST_OPEN_HOUR = 8;
    static final int LAST_OPEN_HOUR = 22;

    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropInventoryRepository inventories;
    private final ConcertQueryService concerts;
    private final OrganizerConcertService organizerConcerts;
    private final MemberService members;
    private final DropQueueGateRepository queueGates;
    private final DropInventorySettings inventorySettings;
    private final Clock clock;

    /** 관리자 등록. 주최자가 없는 드롭이 되고 관리자만 관리한다. 권한은 {@code /api/admin/**} 경로가 막는다. */
    @Transactional
    public Admin create(CreateDropRequest request) {
        validate(request);
        return register(request, null);
    }

    /**
     * 주최자 등록 (#429). 주최자는 본인 공연에만, 관리자는 모든 공연에 등록한다. 드롭 주최자는 공연 주최자다.
     * 남의 공연과 없는 공연은 모두 404다.
     */
    @Transactional
    public Admin createAsOrganizer(Long actorId, CreateDropRequest request) {
        validate(request);
        requireOrganizerOpenHour(request.opensAt());
        Long organizerId = organizerConcerts.getManageable(actorId, request.concertId()).organizerId();
        return register(request, organizerId);
    }

    /**
     * 주최자 오픈 시각은 KST 정각, 08:00부터 22:00까지만 받는다 (#438). 트래픽이 몰리는 시각을 운영이 예측할 수 있게 한다.
     * 관리자 등록에는 적용하지 않는다.
     */
    static void requireOrganizerOpenHour(Instant opensAt) {
        var local = opensAt.atZone(ORGANIZER_ZONE);
        boolean onTheHour = local.getMinute() == 0 && local.getSecond() == 0 && local.getNano() == 0;
        if (!onTheHour || local.getHour() < FIRST_OPEN_HOUR || local.getHour() > LAST_OPEN_HOUR) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "오픈 시각은 08시부터 22시까지 정각만 고를 수 있습니다.");
        }
    }

    private void validate(CreateDropRequest request) {
        var now = clock.instant();
        if (!request.opensAt().isBefore(request.closesAt()) || !request.closesAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var codes = new HashSet<String>();
        for (var sku : request.variants()) {
            if (!codes.add(sku.skuCode())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "SKU 코드가 중복됩니다.");
            }
        }
    }

    private Admin register(CreateDropRequest request, Long organizerId) {
        var now = clock.instant();
        concerts.getDetail(request.concertId());
        Drop drop = drops.save(Drop.draft(request.concertId(), organizerId, request.title(), request.description(),
                request.imageUrl(), request.opensAt(), request.closesAt(), now));
        for (var sku : request.variants()) {
            DropVariant variant;
            try {
                variant = variants.save(DropVariant.create(drop.getId(), sku.skuCode(),
                        sku.optionName(), sku.priceAmount()));
            } catch (DataIntegrityViolationException e) {
                // The database collation also considers some different Unicode spellings equal.
                if (e.getCause() instanceof ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().endsWith("uk_drop_variants_sku")) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "SKU 코드가 중복됩니다.");
                }
                throw e;
            }
            // Head row first: the buckets reference it. Same transaction, so the SKU appears with all its stock.
            var inventory = inventories.saveAndFlush(DropInventory.initial(variant.getId(), sku.totalQuantity(),
                    inventorySettings.bucketCount()));
            for (int bucket = 0; bucket < inventory.getBucketCount(); bucket++) {
                inventories.insertBucket(variant.getId(), bucket, inventory.bucketQuantity(bucket));
            }
            queueGates.create(variant.getId());
        }
        return new Admin(drop.getId(), drop.getStatus());
    }

    @Transactional
    public Admin publish(Long dropId) {
        Drop drop = drops.findByIdForUpdate(dropId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        drop.publish();
        return new Admin(drop.getId(), drop.getStatus());
    }

    /** 남의 드롭은 없는 드롭과 같은 404다. 다른 주최자의 드롭이 있는지 알려 주지 않는다. */
    @Transactional
    public Admin publishAsOrganizer(Long actorId, Long dropId) {
        boolean admin = isAdmin(actorId);
        Drop drop = drops.findByIdForUpdate(dropId)
                .filter(d -> d.isManageableBy(actorId, admin))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        drop.publish();
        return new Admin(drop.getId(), drop.getStatus());
    }

    /** 삭제 (#430). 주최자는 본인 드롭, 관리자는 모든 드롭을 삭제한다. 남의 드롭은 404다. */
    @Transactional
    public Admin cancelAsOrganizer(Long actorId, Long dropId) {
        boolean admin = isAdmin(actorId);
        Drop drop = drops.findByIdForUpdate(dropId)
                .filter(d -> d.isManageableBy(actorId, admin))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        drop.cancel(clock.instant());
        return new Admin(drop.getId(), drop.getStatus());
    }

    /** 주최자는 본인 드롭을, 관리자는 전체 드롭을 등록 최신순으로 본다. 게시 전 드롭도 포함한다. */
    @Transactional(readOnly = true)
    public ManagedPage listManaged(Long actorId) {
        var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        var result = isAdmin(actorId) ? drops.findAll(sort) : drops.findAllByOrganizerId(actorId, sort);
        return new ManagedPage(result.stream().map(d -> new Managed(d.getId(), d.getConcertId(), d.getOrganizerId(),
                d.getTitle(), d.getStatus(), d.getOpensAt(), d.getClosesAt(), d.getCreatedAt())).toList());
    }

    private boolean isAdmin(Long actorId) {
        if (actorId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        return role == MemberRole.ADMIN;
    }
}
