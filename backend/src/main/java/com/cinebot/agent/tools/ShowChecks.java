package com.cinebot.agent.tools;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.show.ShowService;
import com.cinebot.show.ShowStatus;
import com.cinebot.show.dto.ShowResponse;
import java.time.Instant;

/** Show lookups shared by the seat and booking tools. */
final class ShowChecks {

    private ShowChecks() {
    }

    /** The show, if it can still be booked: exists (else SHOW_NOT_FOUND), not cancelled, not started. */
    static ShowResponse requireBookable(ShowService shows, long showId, Instant now) {
        ShowResponse show = shows.get(showId);
        if (show.status() != ShowStatus.SCHEDULED) {
            throw new ApiException(ErrorCode.SHOW_NOT_BOOKABLE, "This show has been cancelled.");
        }
        if (!show.startTime().toInstant().isAfter(now)) {
            throw new ApiException(ErrorCode.SHOW_NOT_BOOKABLE, "This show has already started.");
        }
        return show;
    }

    static String describe(ShowResponse show) {
        return show.movieTitle() + " · " + show.theatreName() + " (" + show.screenName() + ") · "
                + Display.when(show.startTime()) + " · " + show.format() + " " + show.language();
    }

    static void remember(AgentTurn turn, ShowResponse show) {
        ConversationState state = turn.state();
        state.selectMovie(show.movieId(), show.movieTitle());
        state.selectCity(show.city());
        state.selectTheatre(show.theatreId(), show.theatreName());
        state.setDate(Display.date(show.startTime()));
        state.selectShow(show.id(), describe(show));
    }
}
