package com.cinebot.auth;

import com.cinebot.user.Role;

/**
 * The principal placed in the SecurityContext. Built purely from JWT claims: no DB hit per request.
 * Controllers receive it via {@code @AuthenticationPrincipal}.
 */
public record AuthenticatedUser(Long id, String email, Role role) {
}
