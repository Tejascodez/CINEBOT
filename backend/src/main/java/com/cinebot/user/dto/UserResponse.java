package com.cinebot.user.dto;

import com.cinebot.user.Role;
import com.cinebot.user.User;

public record UserResponse(Long id, String email, String fullName, String phone, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(), user.getRole());
    }
}
