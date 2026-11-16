FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app

# Sao chép file .csproj và restore trước để tận dụng Docker layer cache
COPY notification-service.csproj ./
RUN dotnet restore

# Sao chép toàn bộ mã nguồn và publish bản Release
COPY . ./
RUN dotnet publish -c Release -o /app/publish /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish ./

EXPOSE 8080
ENTRYPOINT ["dotnet", "notification-service.dll"]