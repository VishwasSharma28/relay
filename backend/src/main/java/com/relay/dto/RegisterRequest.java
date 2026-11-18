package com.relay.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotBlank @Size(max = 150) String fullName) {
    @AssertTrue(message = "Password must be no more than 72 UTF-8 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=[REDACTED], fullName=" + fullName + "]";
    }
}
