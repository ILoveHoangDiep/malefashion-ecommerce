namespace NotificationService.DTOs;

public class OrderEventDto
{
    public string OrderId {get; set;} = string.Empty;
    public string UserId {get; set;} = string.Empty;
    
    public decimal? TotalAmout { get; set;}

    public string? Reason {get; set;} // li do huy don hang, hoac ly do khong thanh cong khi thanh toan

    public DateTime? Timestamp {get; set;} // thoi gian xay ra event, vd: thoi gian huy don hang, thoi gian thanh toan khong thanh cong
}