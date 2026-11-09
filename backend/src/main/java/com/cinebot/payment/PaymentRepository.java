package com.cinebot.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.bookingId = :bookingId")
    Optional<Payment> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    // Projections: resolve the booking first, so rows are then locked in the global order.
    @Query("select p.bookingId from Payment p where p.providerOrderId = :orderId")
    Optional<Long> findBookingIdByOrderId(@Param("orderId") String orderId);

    @Query("select p.bookingId from Payment p where p.providerPaymentId = :paymentId")
    Optional<Long> findBookingIdByPaymentId(@Param("paymentId") String paymentId);

    List<Payment> findByStatusAndCreatedAtBeforeOrderByCreatedAt(PaymentStatus status, Instant cutoff, Limit limit);

    List<Payment> findByStatusOrderByCreatedAt(PaymentStatus status, Limit limit);
}
