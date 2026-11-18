package com.relay.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;

public record LoginRequest(@NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 72) String password) {
    @AssertTrue(message = "Password must be no more than 72 UTF-8 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=[REDACTED]]";
    }
}
