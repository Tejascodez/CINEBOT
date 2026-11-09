package com.cinebot.screen.dto;

import com.cinebot.screen.ScreenType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScreenRequest(@NotBlank @Size(max = 50) String name, @NotNull ScreenType screenType) {
}
