package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropVariant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DropVariantRepository extends JpaRepository<DropVariant, Long> {
    java.util.List<DropVariant> findAllByDropIdOrderByIdAsc(Long dropId);
}
