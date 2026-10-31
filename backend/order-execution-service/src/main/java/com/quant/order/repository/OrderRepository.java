package com.quant.order.repository;

import com.quant.order.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);
    java.util.List<OrderEntity> findByStatusOrderByIdAsc(String status);
    List<OrderEntity> findTop50ByStatusOrderByUpdatedAtAsc(String status);
    List<OrderEntity> findByStrategyIdIsNotNullOrderByIdAsc();
    List<OrderEntity> findTop1000ByStrategyIdAndStatusOrderByIdAsc(String strategyId, String status);
    List<OrderEntity> findTop200ByStatusInAndMarketOrderByUpdatedAtAsc(List<String> statuses, String market);
    List<OrderEntity> findTop200ByStatusAndExchangeOrderIdOrderByIdAsc(String status, String exchangeOrderId);
    List<OrderEntity> findTop200ByStatusInOrderByIdAsc(List<String> statuses);
    Page<OrderEntity> findAllByOrderByIdDesc(Pageable pageable);

    @Query("select o.strategyId, count(o), coalesce(sum(o.filledQty),0) " +
           "from OrderEntity o where o.status = 'FILLED' group by o.strategyId")
    List<Object[]> attribution();
}
