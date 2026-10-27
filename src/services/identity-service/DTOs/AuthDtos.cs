namespace IdentityService.DTOs;

public record RegisterRequest(string FullName, string Email, string Password);

public record LoginRequest(string Email, string Password);

public record AuthResponse(string Token, Guid UserId, string FullName, string Email, string Role);