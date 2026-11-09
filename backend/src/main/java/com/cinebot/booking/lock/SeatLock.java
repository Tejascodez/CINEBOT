package com.cinebot.booking.lock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A user's temporary hold on seats of one show. The seats themselves point back via show_seats.lock_id.
 *
 * <p>Implements Persistable because the id is assigned by the app (it is also the Redis value). Without it,
 * Spring Data sees a non-null id, assumes the entity exists, and runs a SELECT + merge instead of a plain INSERT.
 */
@Entity
@Table(name = "seat_locks")
public class SeatLock implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @Column(name = "seat_count", nullable = false, updatable = false)
    private int seatCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatLockStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Transient
    private boolean isNew;

    protected SeatLock() {
        // JPA
    }

    SeatLock(UUID id, Long userId, Long showId, int seatCount, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.showId = showId;
        this.seatCount = seatCount;
        this.status = SeatLockStatus.ACTIVE;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.isNew = true;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    void close(SeatLockStatus outcome, Instant now) {
        if (status != SeatLockStatus.ACTIVE) {
            throw new IllegalStateException("Lock " + id + " already " + status);
        }
        status = outcome;
        closedAt = now;
    }

    public boolean isOwnedBy(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getShowId() {
        return showId;
    }

    public int getSeatCount() {
        return seatCount;
    }

    public SeatLockStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
