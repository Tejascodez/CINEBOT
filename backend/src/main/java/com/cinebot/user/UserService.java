package com.cinebot.user;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the user module. Other modules use this, never UserRepository. */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The existsByEmail check gives a friendly error in the common case, but two concurrent registrations
     * can both pass it. The unique constraint is the real guard; we translate its violation into the same 409.
     */
    @Transactional
    public User createUser(String email, String passwordHash, String fullName, String phone, Role role) {
        String normalized = normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        try {
            return users.saveAndFlush(new User(normalized, passwordHash, fullName.trim(), phone, role));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
    }
}
