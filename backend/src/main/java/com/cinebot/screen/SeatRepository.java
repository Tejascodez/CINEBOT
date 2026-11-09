package com.cinebot.screen;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowIndexAscSeatNumberAsc(Long screenId);

    @Query("select distinct s.seatType from Seat s where s.screenId = :screenId")
    Set<SeatType> findSeatTypes(@Param("screenId") Long screenId);

    /** Fails with an FK violation if any show inventory references these seats: the DB is the guard. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Seat s where s.screenId = :screenId")
    int deleteByScreenId(@Param("screenId") Long screenId);
}
