package com.cinebot.booking.lock;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatLockRepository extends JpaRepository<SeatLock, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SeatLock l where l.id = :id")
    Optional<SeatLock> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SeatLock l
            where l.userId = :userId and l.showId = :showId and l.status = com.cinebot.booking.lock.SeatLockStatus.ACTIVE
            """)
    Optional<SeatLock> findActiveForUpdate(@Param("userId") Long userId, @Param("showId") Long showId);

    /**
     * Claims a batch of expired locks. SKIP LOCKED: several app instances can sweep at once without
     * processing the same lock twice or waiting on each other; rows being used by a request are skipped
     * and picked up next round.
     */
    /*
     * Locks backing a PAYMENT_PENDING booking get a grace period (graceCutoff = now - grace): a payment captured
     * seconds after expiry can still confirm, as long as nobody took the seats over in the meantime.
     * Filtering in SQL (not in Java after the fetch) keeps skipped rows from filling every batch.
     */
    @Query(value = """
            SELECT l.* FROM seat_locks l
            WHERE l.status = 'ACTIVE' AND l.expires_at <= :now
              AND (l.expires_at <= :graceCutoff
                   OR NOT EXISTS (SELECT 1 FROM bookings b
                                  WHERE b.seat_lock_id = l.id AND b.status = 'PAYMENT_PENDING'))
            ORDER BY l.expires_at
            LIMIT :limit
            FOR UPDATE OF l SKIP LOCKED
            """, nativeQuery = true)
    List<SeatLock> claimExpired(@Param("now") Instant now, @Param("graceCutoff") Instant graceCutoff,
                                @Param("limit") int limit);
}
