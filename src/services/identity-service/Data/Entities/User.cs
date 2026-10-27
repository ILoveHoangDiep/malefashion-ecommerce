using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace IdentityService.Data.Entities;

[Table("users")]
public class User
{
    [Key]
    public Guid Id {get; set;} = Guid.NewGuid();

    [Required]
    [MaxLength(50)]
    public string FullName {get; set;} = string.Empty;

    [Required]
    [EmailAddress]
    [MaxLength(100)]
    public string Email {get; set;} = string.Empty;

    [Required]
    public string PasswordHash {get; set;} = string.Empty;
    
    [Required]
    public string Role {get; set;} = "Customer"; // Customer/Admin
    [Required]
    public DateTime CreatedAt {get; set;} = DateTime.UtcNow;
}