package com.quant.account.repository;

import com.quant.account.entity.EquityPoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquityRepository extends JpaRepository<EquityPoint, Long> {
    Page<EquityPoint> findAllByOrderByIdDesc(Pageable pageable);
}
