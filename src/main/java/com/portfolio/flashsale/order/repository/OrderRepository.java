package com.portfolio.flashsale.order.repository;

import com.portfolio.flashsale.order.domain.Order;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"items", "items.product", "voucher", "user"})
    Optional<Order> findDetailedById(Long id);

    @Query("SELECT o.id FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC, o.id DESC")
    Page<Long> findOrderIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"items", "items.product", "voucher", "user"})
    List<Order> findAllDetailedByIdIn(List<Long> ids);

    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
              AND o.expiresAt <= :now
            ORDER BY o.expiresAt ASC, o.id ASC
            """)
    List<Long> findExpiredPendingOrderIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
            ORDER BY o.expiresAt ASC, o.id ASC
            """)
    List<Long> findAllPendingOrderIds(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.portfolio.flashsale.order.domain.OrderStatus.CONFIRMED,
                o.confirmedAt = :now,
                o.updatedAt = :now
            WHERE o.id = :orderId
              AND o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
              AND o.expiresAt > :now
            """)
    int markConfirmedIfPendingAndNotExpired(@Param("orderId") Long orderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.portfolio.flashsale.order.domain.OrderStatus.CANCELLED,
                o.cancelledAt = :now,
                o.updatedAt = :now
            WHERE o.id = :orderId
              AND o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
            """)
    int markCancelledIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.portfolio.flashsale.order.domain.OrderStatus.EXPIRED,
                o.updatedAt = :now
            WHERE o.id = :orderId
              AND o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
              AND o.expiresAt <= :now
            """)
    int markExpiredIfPendingAndPastDeadline(@Param("orderId") Long orderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.portfolio.flashsale.order.domain.OrderStatus.EXPIRED,
                o.expiresAt = CASE WHEN o.expiresAt > :now THEN :now ELSE o.expiresAt END,
                o.updatedAt = :now
            WHERE o.id = :orderId
              AND o.status = com.portfolio.flashsale.order.domain.OrderStatus.PENDING_PAYMENT
            """)
    int markExpiredImmediatelyIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);
}
