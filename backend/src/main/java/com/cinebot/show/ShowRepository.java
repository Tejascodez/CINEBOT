package com.cinebot.show;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowRepository extends JpaRepository<Show, Long> {

    /** FOR UPDATE: taken by cancel. Conflicts with the FOR SHARE below. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Show s where s.id = :id")
    Optional<Show> findByIdForUpdate(@Param("id") Long id);

    /**
     * FOR SHARE: taken by every seat-lock attempt. Shared locks don't block each other (many users can lock
     * seats at once) but do block FOR UPDATE, so a cancel waits for in-flight lock attempts and vice versa.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Show s where s.id = :id")
    Optional<Show> findByIdForShare(@Param("id") Long id);
}
