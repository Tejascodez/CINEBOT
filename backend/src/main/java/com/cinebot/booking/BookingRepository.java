package com.cinebot.booking;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findBySeatLockId(UUID seatLockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.seatLockId = :lockId")
    Optional<Booking> findBySeatLockIdForUpdate(@Param("lockId") UUID lockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    Page<Booking> findByUserId(Long userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(Long userId, BookingStatus status, Pageable pageable);

    boolean existsByBookingReference(String bookingReference);

    @Query("select b.seatLockId from Booking b where b.id = :id")
    Optional<UUID> findSeatLockIdById(@Param("id") Long id);

    /** Projection, not an entity: nothing stale enters the persistence context before the rows are locked. */
    @Query("select b.seatLockId from Booking b where b.id = :id and b.userId = :userId")
    Optional<UUID> findSeatLockIdOwnedBy(@Param("id") Long id, @Param("userId") Long userId);
}
