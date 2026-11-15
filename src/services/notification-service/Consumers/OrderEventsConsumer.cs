using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using NotificationService.Data;
using NotificationService.Data.Entities;
using NotificationService.DTOs;
using NotificationService.Hubs;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace NotificationService.Consumers;

public class OrderEventsConsumer : BackgroundService
{
    private readonly IConfiguration _configuration;
    private readonly IHubContext<NotificationHub> _hubContext;
    private readonly IServiceScopeFactory _serviceScopeFactory; 
    private readonly ILogger<OrderEventsConsumer> _logger;

    private IConnection? _connection;
    private IModel? _channel;

    private static readonly string[] SupportedEvents =
    {
        "order-confirmed",
        "order-cancelled",
        "order-created"
    };

    public OrderEventsConsumer(IConfiguration configuration, IHubContext<NotificationHub> hubContext, IServiceScopeFactory serviceScopeFactory, ILogger<OrderEventsConsumer> logger)
    {
        _configuration = configuration;
        _hubContext = hubContext;
        _serviceScopeFactory = serviceScopeFactory;
        _logger = logger;
    }

    /*
        Ứng dụng khởi động
    → thử kết nối và đăng ký consumer
    → nếu thành công: giữ service chạy, chờ ứng dụng tắt
    → nếu thất bại: ghi log, dọn tài nguyên, đợi 5 giây, thử lại
    → khi ứng dụng tắt: kết thúc vòng lặp
    */
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while(!stoppingToken.IsCancellationRequested)
        {
            try
            {
                InitializeRabbitMQ();
                _logger.LogInformation("OrderEventsConsumer started consuming messages.");
                await Task.Delay(Timeout.Infinite, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                _logger.LogInformation("OrderEventsConsumer is stopping due to cancellation.");
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "An error occurred in OrderEventsConsumer. Retrying in 5 seconds...");
                CleanupRabbitMq();
                await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken);
            }
        }
    }

    /*
        Kết nối RabbitMQ
    → khai báo exchange
    → tạo queue cho từng event
    → bind queue bằng routing key
    → đăng ký consumer
    → nhận message và gọi ProcessEventAsync
    → ACK nếu thành công, NACK nếu lỗi
    */
    private void InitializeRabbitMQ()

    {
        var factory = new ConnectionFactory
        {
            HostName = _configuration["RabbitMQ:Host"] ?? "malefashion-rabbitmq",
            Port = int.Parse(_configuration["RabbitMQ:Port"] ?? "5672"),
            UserName = _configuration["RabbitMQ:Username"] ?? "guest",
            Password = _configuration["RabbitMQ:Password"] ?? "guest",
            DispatchConsumersAsync = true, // bat xu li callback dong bo
            AutomaticRecoveryEnabled = true // tu dong ket noi lai khi bi mat ket noi
        };

        _connection = factory.CreateConnection();
        _channel = _connection.CreateModel();

        const string exchangeName = "malefashion.events";
        _channel.ExchangeDeclare(
            exchange: exchangeName,
            type: ExchangeType.Topic,
            durable: true
        );

        foreach (var eventName in SupportedEvents)
        {
            _channel.QueueDeclare(
                queue: eventName,
                durable: true, // luu tru queue khi server bi restart
                exclusive: false, // chi cho phep 1 connection truy cap vao queue - false
                autoDelete: false // xoa queue khi khong con connection nao truy cap vao - false
            );

            _channel.QueueBind(
                queue: eventName,
                exchange: exchangeName,
                routingKey: eventName
            );

            _channel.QueueBind(
                queue: eventName,
                exchange: exchangeName,
                routingKey: eventName.Replace("-", ".") // order-cancelled hoac order.cancelled deu duoc
            );

            var consumer = new AsyncEventingBasicConsumer(_channel);
            var currentEvent = eventName;

            consumer.Received += async (_, ea) =>
            {
                var body = ea.Body.ToArray();
                var rawJson = Encoding.UTF8.GetString(body);
                _logger.LogInformation("Received event [{EventName}]: {Payload}", currentEvent, rawJson);

                try
                {
                    await ProcessEventAsync(currentEvent, rawJson);
                    _channel.BasicAck(deliveryTag: ea.DeliveryTag, multiple: false);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Error processing event [{EventName}]: {Payload}", currentEvent, rawJson);
                    _channel.BasicNack(deliveryTag: ea.DeliveryTag, multiple: false, requeue: true);
                }
            };

            _channel.BasicConsume(
                queue: eventName,
                autoAck: false, // tu dong gui ack/nack - false
                consumer: consumer
            );
        }

        
    }

    /*
        JSON từ RabbitMQ
    → OrderEventDto
    → kiểm tra UserId
    → tạo nội dung và Notification
    → lưu database
    → gửi SignalR
    → ghi log
    */
    private async Task ProcessEventAsync(string eventType, string rawJson)
    {
        var options = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        };

        var payload = JsonSerializer.Deserialize<OrderEventDto>(rawJson, options)
                    ?? throw new InvalidOperationException("Invalid event JSON payload.");

        if(string.IsNullOrEmpty(payload.UserId))
        {
           throw new InvalidOperationException("UserId is missing in the event payload.");
        }

        var (title, message) = BuildNotificationContent(eventType, payload);

        var notification = new Notification
        {
            UserId = payload.UserId,
            OrderId = payload.OrderId,
            Type = eventType,
            Title = title,
            Message = message,
            CreatedAt = DateTime.UtcNow
        };

        using (var scope = _serviceScopeFactory.CreateScope())
        {
            var dbContext =
                scope.ServiceProvider.GetRequiredService<NotificationDbContext>();

            dbContext.Notifications.Add(notification);
            await dbContext.SaveChangesAsync();
        }

        await _hubContext.Clients.User(notification.UserId) // realtime
        .SendAsync("ReceiveNotification", new
        {
            notification.Id,
            notification.OrderId,
            notification.Type,
            notification.Title,
            notification.Message,
            notification.IsRead,
            notification.CreatedAt
        });
        _logger.LogInformation(
            "Saved notification {NotificationId} and pushed via SignalR to User {UserId}",
            notification.Id,
            notification.UserId
        );
    }

    private static (string title, string message) BuildNotificationContent(string eventType, OrderEventDto data)
    {
        return eventType switch
        {
            "order-confirmed" => (
                "Đơn hàng đã được xác nhận",
                $"Đơn hàng #{data.OrderId} đã được xác nhận và đang chuẩn bị giao."
            ),
            "order-cancelled" => (
                "Đơn hàng đã bị hủy",
                string.IsNullOrEmpty(data.Reason)
                ? $"Đơn hàng #{data.OrderId} của bạn đã bị hủy."
                : $"Đơn hàng #{data.OrderId} của bạn đã bị hủy. Lý do: {data.Reason}"
            ),
            "order-created" => (
                "Đặt hàng thành công",
                $"Đơn hàng #{data.OrderId} của bạn đã được tiếp nhận và đang chờ xác nhận."
            ),
            _ => (
                "Cập nhật đơn hàng",
                $"Đơn hàng #{data.OrderId} có cập nhật trạng thái mới."
            )
        };
    }

    private void CleanupRabbitMq()
    {
        try
        {
            _channel?.Close();
        } catch {};

        try
        {
            _connection?.Close();
        } catch {};
    }

    public override void Dispose()
    {
        CleanupRabbitMq();
        base.Dispose();
    }
}
