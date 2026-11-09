package com.cinebot.theatre;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.web.PageResponse;
import com.cinebot.screen.ScreenService;
import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.theatre.dto.TheatreDetailResponse;
import com.cinebot.theatre.dto.TheatreListQuery;
import com.cinebot.theatre.dto.TheatreRequest;
import com.cinebot.theatre.dto.TheatreSummaryResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TheatreService {

    private final TheatreRepository theatres;
    private final ScreenService screenService;

    public TheatreService(TheatreRepository theatres, ScreenService screenService) {
        this.theatres = theatres;
        this.screenService = screenService;
    }

    @Transactional(readOnly = true)
    public PageResponse<TheatreSummaryResponse> listByCity(TheatreListQuery query) {
        String brand = query.brand() == null || query.brand().isBlank() ? null : query.brand().trim();
        var pageable = PageRequest.of(query.page(), query.size(), Sort.by("name", "id"));
        // Optional filters are added only when present. The "(:brand IS NULL OR ...)" JPQL idiom fails on
        // PostgreSQL: a null parameter has no type (sent as bytea), so lower(?) cannot be resolved.
        // lower(city) = lower(:city) matches the expression index uk_theatres_city_name.
        String city = query.city().trim().toLowerCase(java.util.Locale.ROOT);
        Specification<Theatre> spec = (root, q, cb) -> cb.and(
                cb.isTrue(root.get("active")),
                cb.equal(cb.lower(root.get("city")), city));
        if (brand != null) {
            String brandLower = brand.toLowerCase(java.util.Locale.ROOT);
            spec = spec.and((root, q, cb) -> cb.equal(cb.lower(root.get("brand")), brandLower));
        }
        return PageResponse.from(theatres.findAll(spec, pageable), TheatreSummaryResponse::from);
    }

    /** Public view: inactive theatres are invisible (404), not "disabled". */
    @Transactional(readOnly = true)
    public TheatreDetailResponse getPublic(Long id) {
        Theatre theatre = theatres.findByIdAndActiveTrue(id).orElseThrow(() -> notFound(id));
        return TheatreDetailResponse.from(theatre, screenService.listByTheatre(id));
    }

    @Transactional
    public TheatreDetailResponse create(TheatreRequest request) {
        try {
            Theatre theatre = theatres.saveAndFlush(new Theatre(request));
            return TheatreDetailResponse.from(theatre, java.util.List.of());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.THEATRE_ALREADY_EXISTS);
        }
    }

    @Transactional
    public TheatreDetailResponse update(Long id, TheatreRequest request) {
        Theatre theatre = theatres.findById(id).orElseThrow(() -> notFound(id));
        theatre.apply(request);
        try {
            theatres.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.THEATRE_ALREADY_EXISTS);
        }
        return TheatreDetailResponse.from(theatre, screenService.listByTheatre(id));
    }

    @Transactional
    public ScreenResponse addScreen(Long theatreId, ScreenRequest request) {
        if (!theatres.existsById(theatreId)) {
            throw notFound(theatreId);
        }
        return screenService.create(theatreId, request);
    }

    /** Used by scheduling: shows can only be added to active theatres. */
    @Transactional(readOnly = true)
    public void requireActive(Long theatreId) {
        Theatre theatre = theatres.findById(theatreId).orElseThrow(() -> notFound(theatreId));
        if (!theatre.isActive()) {
            throw new ApiException(ErrorCode.THEATRE_INACTIVE);
        }
    }

    private static ApiException notFound(Long id) {
        return new ApiException(ErrorCode.THEATRE_NOT_FOUND, "Theatre " + id + " not found");
    }
}
