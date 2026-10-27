using IdentityService.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace IdentityService.Data;

public class IdentityDbContext : DbContext
{
   public IdentityDbContext(DbContextOptions<IdentityDbContext> options) : base(options){}
    public DbSet<User> Users => Set<User>();

    //Fluent API
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        modelBuilder.Entity<User>()
            .HasIndex(user => user.Email) // username = email
            .IsUnique();
    }
}