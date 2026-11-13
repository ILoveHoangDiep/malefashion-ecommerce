using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.Authorization;

namespace NotificationService.Hubs;

[Authorize]
public class NotificationHub : Hub
{
    private readonly ILogger<NotificationHub> _logger;

    public NotificationHub(ILogger<NotificationHub> logger)
    {
        _logger = logger;
    }

    // khi user connnect vao hub
    public override async Task OnConnectedAsync()
    {
        var UserId = Context.UserIdentifier ?? "Unknown";
        _logger.LogInformation(
            "ConnectionId : {ConnectionId}, UserId : {UserId}",
            Context.ConnectionId,
            UserId
        );
        await base.OnConnectedAsync();

    }

    // khi user disconnect khoi hub
    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var UserId = Context.UserIdentifier ?? "Unknown";
        _logger.LogInformation(
            "ConnectionId : {ConnectionId}, UserId : {UserId}",
            Context.ConnectionId,
            UserId
        );
        await base.OnDisconnectedAsync(exception);
    }
}