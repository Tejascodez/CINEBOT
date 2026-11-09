package com.cinebot.booking.lock;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seat-locks")
public class SeatLockController {

    private final SeatLockService seatLockService;

    public SeatLockController(SeatLockService seatLockService) {
        this.seatLockService = seatLockService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeatLockResponse lock(@AuthenticationPrincipal AuthenticatedUser user,
                                 @Valid @RequestBody LockSeatsRequest request) {
        return seatLockService.lock(user, request);
    }

    @DeleteMapping("/{lockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID lockId) {
        seatLockService.release(user, lockId);
    }
}
