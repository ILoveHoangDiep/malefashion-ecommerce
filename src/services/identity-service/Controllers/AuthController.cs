using IdentityService.Data;
using IdentityService.Data.Entities;
using IdentityService.DTOs;
using IdentityService.Services;
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

    [HttpPost("register")] // nhận data -> kiểm tra email -> hash password -> save user -> jwt -> response
    public async Task<IActionResult> Register([FromBody] RegisterRequest request)
    {
        // trung email
        if(await _context.Users.AnyAsync(u => u.Email == request.Email))
            return BadRequest(new {message = "The email already exists."});

        // tao user & hash password
        var user = new User
        {
            FullName = request.FullName,
            Email = request.Email,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(request.Password)
        };
        _context.Users.Add(user);
        await _context.SaveChangesAsync();

        // jwt & response
        var token = _tokenService.GenerateToken(user);
        return Ok(new AuthResponse(token, user.Id, user.FullName, user.Email, user.Role));
    }

    [HttpPost("Login")] // nhan data -> search user -> check pass -> jwt -> response
    public async Task<IActionResult> Login([FromBody] LoginRequest request)
    {
        // search user
        var user = await _context.Users.SingleOrDefaultAsync(u => u.Email == request.Email);

        // check pass
        if(user == null || !BCrypt.Net.BCrypt.Verify(request.Password, user.PasswordHash))
            return Unauthorized(new {message = "Incorrect email or password"});

        // jwt & response
        var token = _tokenService.GenerateToken(user);
        return Ok(new AuthResponse(token, user.Id, user.FullName, user.Email, user.Role));
    }
}