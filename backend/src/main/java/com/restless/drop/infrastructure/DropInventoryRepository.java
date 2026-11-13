package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropInventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DropInventoryRepository extends JpaRepository<DropInventory, Long> {
}
