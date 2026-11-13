using System.ComponentModel.DataAnnotations;

namespace NotificationService.Data.Entities;

public class Notification
{
    [Key]
    public Guid Id {get; set;} = Guid.NewGuid();

    [Required]
    [MaxLength(64)]
    public string UserId {get; set;} = string.Empty;

    [Required]
    [MaxLength(64)]
    public string OrderId {get; set;} = string.Empty;

    [Required]
    [MaxLength(10)]
    public string Type {get; set;} = string.Empty; // confirm - created - cancelled

    public bool IsRead {get; set;} = false;

    [Required]
    [MaxLength(200)]
    public string Title {get; set;} = string.Empty;

    [Required]
    [MaxLength(2000)]
    public string Message {get; set;} = string.Empty;

    public DateTime CreatedAt {get; set;} =  DateTime.UtcNow;
}