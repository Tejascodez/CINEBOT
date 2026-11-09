package com.cinebot.genre;

import com.cinebot.genre.dto.GenreResponse;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenreService {

    private final GenreRepository genres;

    public GenreService(GenreRepository genres) {
        this.genres = genres;
    }

    @Transactional(readOnly = true)
    public List<GenreResponse> listAll() {
        return genres.findAll(Sort.by("name")).stream().map(GenreResponse::from).toList();
    }

    /** Insert-or-update by TMDB id. TMDB occasionally renames genres; the TMDB id is the stable key. */
    @Transactional
    public Genre upsert(int tmdbGenreId, String name) {
        Genre genre = genres.findByTmdbGenreId(tmdbGenreId)
                .orElseGet(() -> genres.save(new Genre(tmdbGenreId, name)));
        if (!genre.getName().equals(name)) {
            genre.rename(name);
        }
        return genre;
    }

}
