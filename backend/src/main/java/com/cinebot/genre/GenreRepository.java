package com.cinebot.genre;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    Optional<Genre> findByTmdbGenreId(Integer tmdbGenreId);

    List<Genre> findAll(Sort sort);
}
