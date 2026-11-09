package com.cinebot.seat;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    /** JOIN FETCH the physical seat: a 300-seat map is one query, not 301. */
    @Query("""
            select ss from ShowSeat ss join fetch ss.seat s
            where ss.showId = :showId
            order by s.rowIndex, s.seatNumber
            """)
    List<ShowSeat> findSeatMap(@Param("showId") Long showId);

    long countByShowIdAndStatusIn(Long showId, Collection<ShowSeatStatus> statuses);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ShowSeat ss set ss.status = com.cinebot.seat.ShowSeatStatus.BLOCKED, ss.version = ss.version + 1
            where ss.showId = :showId and ss.status = com.cinebot.seat.ShowSeatStatus.AVAILABLE
            """)
    int blockAvailable(@Param("showId") Long showId);
}
