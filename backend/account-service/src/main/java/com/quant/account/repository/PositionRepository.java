package com.quant.account.repository;

import com.quant.account.entity.PositionEntity;
import com.quant.account.entity.PositionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<PositionEntity, PositionId> {}
