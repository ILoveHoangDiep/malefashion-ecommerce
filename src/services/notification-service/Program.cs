using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using NotificationService.Consumers;
using NotificationService.Data;
using NotificationService.Hubs;

var builder = WebApplication.CreateBuilder(args);

// 1. Đăng ký PostgreSQL EF Core (notification_db)
var connectionString = builder.Configuration.GetConnectionString("DefaultConnection")
    ?? "Host=malefashion-postgres;Port=5432;Database=notification_db;Username=postgres;Password=postgres";

builder.Services.AddDbContext<NotificationDbContext>(options =>
    options.UseNpgsql(connectionString));

// 2. Cấu hình JWT Authentication (đồng bộ Secret Key và hỗ trợ query string cho SignalR)
var jwtSettings = builder.Configuration.GetSection("JwtSettings");
var secret = jwtSettings["Secret"] ?? "MalefashionSecretKeyMustBeVeryLongAndSecure2026!";
var key = Encoding.UTF8.GetBytes(secret);

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.RequireHttpsMetadata = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(key),
            ValidateIssuer = true,
            ValidIssuer = jwtSettings["Issuer"] ?? "malefashion-identity",
            ValidateAudience = true,
            ValidAudiences = new[] { "malefashion-client", "malefashion-clients" },
            ValidateLifetime = true,
            ClockSkew = TimeSpan.Zero
        };

        // Trích xuất JWT từ query string ?access_token=... khi client mở kết nối WebSocket SignalR
        options.Events = new JwtBearerEvents
        {
            OnMessageReceived = context =>
            {
                var accessToken = context.Request.Query["access_token"];
                var path = context.HttpContext.Request.Path;

                if (!string.IsNullOrEmpty(accessToken) && path.StartsWithSegments("/hubs/notification"))
                {
                    context.Token = accessToken;
                }

                return Task.CompletedTask;
            }
        };
    });

builder.Services.AddAuthorization();

// 3. Cấu hình CORS cho phép Frontend kết nối SignalR kèm thông tin xác thực
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowFrontend", policy =>
    {
        policy.WithOrigins("http://localhost:3000")
              .AllowAnyHeader()
              .AllowAnyMethod()
              .AllowCredentials();
    });
});

// 4. Đăng ký SignalR, Controllers và RabbitMQ Background Consumer
builder.Services.AddSignalR();
builder.Services.AddControllers();
builder.Services.AddHostedService<OrderEventsConsumer>();

var app = builder.Build();

// Tự động chạy EF Core Migration tạo bảng notifications khi container khởi động
using (var scope = app.Services.CreateScope())
{
    var dbContext = scope.ServiceProvider.GetRequiredService<NotificationDbContext>();
    dbContext.Database.Migrate();
}

app.UseCors("AllowFrontend");
app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();
app.MapHub<NotificationHub>("/hubs/notification");

app.Run();