package com.oms.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.oms.user.domain.UserRole;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private UUID id;
    private String email;
    private String fullName;
    private UserRole role;
    private boolean active;
    private Instant createdAt;
}
