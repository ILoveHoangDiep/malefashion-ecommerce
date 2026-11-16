# 1. Giai đoạn biên dịch (Build Stage)
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app

# Copy riêng file .csproj và restore trước để tận dụng triệt để Docker Layer Cache
COPY identity-service.csproj ./
RUN dotnet restore

# Copy toàn bộ mã nguồn còn lại và publish bản build tối ưu
COPY . ./
RUN dotnet publish -c Release -o /app/publish /p:UseAppHost=false

# 2. Giai đoạn thực thi (Runtime Stage)
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app

# Copy sản phẩm đã biên dịch từ build stage sang
COPY --from=build /app/publish ./

# .NET 8 chạy mặc định dưới tài khoản non-root app trên cổng 8080
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080

ENTRYPOINT ["dotnet", "identity-service.dll"]