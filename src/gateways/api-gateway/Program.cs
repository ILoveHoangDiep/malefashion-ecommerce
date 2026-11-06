using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using Yarp.ReverseProxy.Transforms;

var builder = WebApplication.CreateBuilder(args);

// 1. Cấu hình JWT Bearer đồng bộ theo JwtSettings và Secret
var jwtSettings = builder.Configuration.GetSection("JwtSettings");
var secret = jwtSettings["Secret"] ?? "MalefashionSecretKeyMustBeVeryLongAndSecure2026!";
var issuer = jwtSettings["Issuer"] ?? "malefashion-identity";
var audience = jwtSettings["Audience"] ?? "malefashion-clients";
var key = Encoding.UTF8.GetBytes(secret);

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.RequireHttpsMetadata = false;
        options.SaveToken = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(key),
            ValidateIssuer = true,
            ValidIssuer = issuer,
            ValidateAudience = true,
            // Chấp nhận cả 2 định dạng audience số ít và số nhiều để tránh lệch config
            ValidAudiences = new[] { "malefashion-client", "malefashion-clients", audience },
            ValidateLifetime = true,
            ClockSkew = TimeSpan.Zero
        };
    });

builder.Services.AddAuthorization(options =>
{
    options.AddPolicy("AuthenticatedUser", policy => policy.RequireAuthenticatedUser());
});

// 2. Cấu hình YARP Reverse Proxy & Transforms xử lý Header
builder.Services.AddReverseProxy()
    .LoadFromConfig(builder.Configuration.GetSection("ReverseProxy"))
    .AddTransforms(builderContext =>
    {
        builderContext.AddRequestTransform(transformContext =>
        {
            // Chống Header Spoofing: xóa header do client tự đính kèm
            transformContext.ProxyRequest.Headers.Remove("X-User-Id");
            transformContext.ProxyRequest.Headers.Remove("X-User-Role");

            var user = transformContext.HttpContext.User;
            if (user.Identity?.IsAuthenticated == true)
            {
                // Inject danh tính đã xác thực vào downstream request
                var userId = user.FindFirstValue(ClaimTypes.NameIdentifier) 
                             ?? user.FindFirstValue("sub");
                if (!string.IsNullOrEmpty(userId))
                {
                    transformContext.ProxyRequest.Headers.Add("X-User-Id", userId);
                }

                var role = user.FindFirstValue(ClaimTypes.Role);
                if (!string.IsNullOrEmpty(role))
                {
                    transformContext.ProxyRequest.Headers.Add("X-User-Role", role);
                }
            }

            return ValueTask.CompletedTask;
        });
    });

var app = builder.Build();

app.UseAuthentication();
app.UseAuthorization();

app.MapReverseProxy();

app.Run();