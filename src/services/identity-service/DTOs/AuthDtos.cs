namespace IdentityService.DTOs;

public record RegisterRequestDto(
    string FullName, 
    string Email, 
    string Password
);

public record LoginRequestDto(
    string Email, 
    string Password
);

public record RefreshTokenRequestDto(
    string RefreshToken
);

public record AuthResponseDto(
    string AccessToken,
    string RefreshToken, 
    Guid UserId, 
    string FullName, 
    string Email, 
    string Role
);

public record UserProfileDto(
    Guid Id,
    string FullName,
    string Email,
    string Role,
    DateTime CreatedAt
);

