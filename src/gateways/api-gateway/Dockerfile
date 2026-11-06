# 1. Giai đoạn biên dịch (Build Stage)
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app

# Restore dependency trước để tận dụng Docker Cache
COPY api-gateway.csproj ./
RUN dotnet restore

# Biên dịch mã nguồn và publish bản release
COPY . ./
RUN dotnet publish -c Release -o /app/publish /p:UseAppHost=false

# 2. Giai đoạn thực thi (Runtime Stage)
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app

COPY --from=build /app/publish ./

EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080

ENTRYPOINT ["dotnet", "api-gateway.dll"]