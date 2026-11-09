package com.cinebot.movie;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    Optional<Movie> findByTmdbId(Long tmdbId);

    /**
     * Title search, typo tolerant. Two predicates, both served by the GIN trigram index on lower(title):
     * <ul>
     *   <li>{@code LIKE '%dark kn%'}: substring / prefix matches.</li>
     *   <li>{@code :q <% lower(title)}: pg_trgm word similarity (threshold 0.6). Matches typos such as
     *       "intersteller" because most of its 3-letter chunks also occur in "interstellar".</li>
     * </ul>
     * Best matches first, then popularity; id as the last key keeps paging deterministic.
     */
    @Query(value = """
            SELECT m.* FROM movies m
            WHERE lower(m.title) LIKE :pattern ESCAPE '\\'
               OR :q <% lower(m.title)
            ORDER BY word_similarity(:q, lower(m.title)) DESC, m.popularity DESC, m.id
            """,
            countQuery = """
            SELECT count(*) FROM movies m
            WHERE lower(m.title) LIKE :pattern ESCAPE '\\'
               OR :q <% lower(m.title)
            """,
            nativeQuery = true)
    Page<Movie> searchByTitle(@Param("q") String normalizedQuery, @Param("pattern") String likePattern,
                              Pageable pageable);
}
