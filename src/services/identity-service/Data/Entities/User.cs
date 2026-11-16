using System.ComponentModel.DataAnnotations;
using Microsoft.EntityFrameworkCore;

namespace IdentityService.Data.Entities;

[Index(nameof(Email), IsUnique = true)]
[Index(nameof(RefreshTokenHash))] 
public class User
{
    public Guid Id { get; set; } = Guid.NewGuid();

    [Required]
    [MaxLength(100)]
    public string FullName { get; set; } = string.Empty;

    [Required]
    [MaxLength(150)]
    public string Email { get; set; } = string.Empty;

    [Required]
    public string PasswordHash { get; set; } = string.Empty;

    [Required]
    [MaxLength(20)]
    public string Role { get; set; } = "Customer";

    [MaxLength(64)]
    public string? RefreshTokenHash { get; set; }

    public DateTime? RefreshTokenExpiryTime { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}