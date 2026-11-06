using System.Security.Claims;
using IdentityService.Data;
using IdentityService.Data.Entities;
using IdentityService.DTOs;
using IdentityService.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace IdentityService.Controllers;

[ApiController]
[Route("api/auth")]
public class AuthController : ControllerBase
{
    private readonly IdentityDbContext _context;
    private readonly TokenService _tokenService;

    public AuthController(IdentityDbContext context, TokenService tokenService)
    {
        _context = context;
        _tokenService = tokenService;
    }

    [HttpPost("register")]
    public async Task<IActionResult> Register([FromBody] RegisterRequestDto request)
    {
        // 1. Kiểm tra nhanh trước (Fail fast)
        if (await _context.Users.AnyAsync(u => u.Email == request.Email))
        {
            return BadRequest(new { message = "Email already exists" });
        }

        var user = new User
        {
            FullName = request.FullName,
            Email = request.Email,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(request.Password),
            Role = "Customer"
        };

        // 2. Lưu User vào database trước để đảm bảo tính toàn vẹn và bắt race condition
        _context.Users.Add(user);
        try
        {
            await _context.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            // Bắt lỗi vi phạm ràng buộc Unique Email khi có 2 request chạy song song
            return BadRequest(new { message = "Email already exists" });
        }

        // 3. User đã lưu thành công -> sinh cặp token
        var accessToken = _tokenService.GenerateAccessToken(user);
        var rawRefreshToken = _tokenService.GenerateRefreshToken();

        user.RefreshTokenHash = _tokenService.HashToken(rawRefreshToken);
        user.RefreshTokenExpiryTime = DateTime.UtcNow.AddDays(7);

        await _context.SaveChangesAsync();

        return Ok(new AuthResponseDto(
            accessToken,
            rawRefreshToken,
            user.Id,
            user.FullName,
            user.Email,
            user.Role
        ));
    }

    [HttpPost("login")]
    public async Task<IActionResult> Login([FromBody] LoginRequestDto request)
    {
        var user = await _context.Users.SingleOrDefaultAsync(u => u.Email == request.Email);
        if (user == null || !BCrypt.Net.BCrypt.Verify(request.Password, user.PasswordHash))
        {
            return Unauthorized(new { message = "Invalid email or password" });
        }

        var accessToken = _tokenService.GenerateAccessToken(user);
        var rawRefreshToken = _tokenService.GenerateRefreshToken();

        user.RefreshTokenHash = _tokenService.HashToken(rawRefreshToken);
        user.RefreshTokenExpiryTime = DateTime.UtcNow.AddDays(7);

        await _context.SaveChangesAsync();

        return Ok(new AuthResponseDto(
            accessToken,
            rawRefreshToken,
            user.Id,
            user.FullName,
            user.Email,
            user.Role
        ));
    }

    [HttpPost("refresh")]
    public async Task<IActionResult> RefreshToken([FromBody] RefreshTokenRequestDto request)
    {
        if (string.IsNullOrWhiteSpace(request.RefreshToken))
        {
            return BadRequest(new { message = "Refresh token is required" });
        }

        var incomingHash = _tokenService.HashToken(request.RefreshToken);
        var user = await _context.Users.SingleOrDefaultAsync(u => u.RefreshTokenHash == incomingHash);

        if (user == null || user.RefreshTokenExpiryTime <= DateTime.UtcNow)
        {
            return Unauthorized(new { message = "Invalid or expired refresh token" });
        }

        // Xoay vòng Refresh Token (Token Rotation)
        var newAccessToken = _tokenService.GenerateAccessToken(user);
        var newRawRefreshToken = _tokenService.GenerateRefreshToken();

        user.RefreshTokenHash = _tokenService.HashToken(newRawRefreshToken);
        user.RefreshTokenExpiryTime = DateTime.UtcNow.AddDays(7);

        await _context.SaveChangesAsync();

        return Ok(new AuthResponseDto(
            newAccessToken,
            newRawRefreshToken,
            user.Id,
            user.FullName,
            user.Email,
            user.Role
        ));
    }

    [Authorize] // Bắt buộc phải có Bearer Token hợp lệ
    [HttpGet("me")]
    public async Task<IActionResult> GetProfile()
    {
        // Trích xuất an toàn từ JWT Claims đã được middleware xác thực chữ ký số
        var userIdClaim = User.FindFirstValue(ClaimTypes.NameIdentifier);

        if (string.IsNullOrEmpty(userIdClaim) || !Guid.TryParse(userIdClaim, out var userId))
        {
            return Unauthorized(new { message = "Invalid token claims" });
        }

        var user = await _context.Users.FindAsync(userId);
        if (user == null)
        {
            return NotFound(new { message = "User not found" });
        }

        return Ok(new UserProfileDto(
            user.Id,
            user.FullName,
            user.Email,
            user.Role,
            user.CreatedAt
        ));
    }

    [Authorize] // Bắt buộc phải có Bearer Token hợp lệ
    [HttpPost("logout")]
    public async Task<IActionResult> Logout()
    {
        var userIdClaim = User.FindFirstValue(ClaimTypes.NameIdentifier);

        if (Guid.TryParse(userIdClaim, out var userId))
        {
            var user = await _context.Users.FindAsync(userId);
            if (user != null)
            {
                // Thu hồi phiên làm việc bằng cách gán null
                user.RefreshTokenHash = null;
                user.RefreshTokenExpiryTime = null;
                await _context.SaveChangesAsync();
            }
        }

        return Ok(new { message = "Logged out successfully" });
    }
}