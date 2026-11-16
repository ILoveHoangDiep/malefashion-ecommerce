using NotificationService.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace NotificationService.Data;

public class NotificationDbContext : DbContext
{
   public NotificationDbContext(DbContextOptions<NotificationDbContext> options) : base(options){}
    public DbSet<Notification> Notifications => Set<Notification>();

    protected override void OnModelCreating (ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.Entity<Notification>(entity =>
        {
            entity.ToTable("notifications");
            entity.HasIndex(e => new{e.UserId, e.CreatedAt});
        });
    }
}