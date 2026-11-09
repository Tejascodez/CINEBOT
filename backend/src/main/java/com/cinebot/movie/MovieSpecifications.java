package com.cinebot.movie;

import com.cinebot.genre.Genre;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

final class MovieSpecifications {

    private MovieSpecifications() {
    }

    /**
     * EXISTS subquery instead of joining genres into the main query: a join would duplicate a movie row per
     * matching genre and inflate the page count.
     */
    static Specification<Movie> hasGenre(Long genreId) {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Movie> m = sub.from(Movie.class);
            Join<Movie, Genre> g = m.join("genres");
            sub.select(m.get("id")).where(cb.equal(m.get("id"), root.get("id")), cb.equal(g.get("id"), genreId));
            return cb.exists(sub);
        };
    }

    static Specification<Movie> idIn(java.util.Collection<Long> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    static Specification<Movie> hasLanguage(String language) {
        return (root, query, cb) -> cb.equal(root.get("originalLanguage"), language);
    }
}
