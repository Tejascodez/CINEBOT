package com.cinebot.movie.sync;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieSyncRunRepository extends JpaRepository<MovieSyncRun, Long> {

    List<MovieSyncRun> findTop20ByOrderByStartedAtDesc();

    /** A crashed instance leaves its run RUNNING forever; without this, no sync could ever start again. */
    @Modifying
    @Query("""
            update MovieSyncRun r set r.status = com.cinebot.movie.sync.SyncStatus.FAILED,
                   r.finishedAt = :now, r.error = 'Abandoned: exceeded maximum run time'
            where r.status = com.cinebot.movie.sync.SyncStatus.RUNNING and r.startedAt < :cutoff
            """)
    int abandonStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
