#requires -Version 5.1
<#
.SYNOPSIS
    End-to-end smoke test for payment-service against real PostgreSQL and RabbitMQ.

.DESCRIPTION
    Publishes real commands through the RabbitMQ management API, observes events on a temporary
    queue bound to malefashion.events with routing key "payment.*", and checks the actuator health
    endpoint. Every step prints PASS or FAIL; the script exits with 1 if any step failed.

    The temporary queue and the service DLQ are always cleaned up, even when a step fails.

    Mỗi lần chạy dùng orderId và eventId mới nên có thể chạy lặp lại nhiều lần.

    LƯU Ý: mockScenario chỉ có tác dụng khi service chạy profile local
    (SPRING_PROFILES_ACTIVE=local). Nếu chạy profile mặc định, mọi payment đều SUCCESS và các
    bước FAILURE/TIMEOUT sẽ FAIL. Khi cổng 5432 trên máy bị chiếm, chạy service với
    POSTGRES_PORT=15432 (xem README mục 3.1).

.EXAMPLE
    .\scripts\smoke-test.ps1

.EXAMPLE
    .\scripts\smoke-test.ps1 -BaseUrl http://localhost:8086 -WaitSeconds 30
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8086',
    [string]$ManagementUrl = 'http://localhost:15672',
    [string]$RabbitUser = 'guest',
    [string]$RabbitPassword = 'guest',
    [string]$VHost = '/',
    [int]$WaitSeconds = 20
)

$ErrorActionPreference = 'Stop'
$script:passes = 0
$script:failures = 0
$script:tempQueue = 'smoke-payment-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$script:vhostPath = [uri]::EscapeDataString($VHost)
$script:mgmtHeaders = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
}
$script:dlq = 'payment-service.commands.dlq'
$script:amount = '578000.00'

function Add-Pass([string]$message) {
    Write-Host "[PASS] $message" -ForegroundColor Green
    $script:passes++
}

function Add-Fail([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    $script:failures++
}

function Assert-That([bool]$condition, [string]$message) {
    if ($condition) { Add-Pass $message } else { Add-Fail $message }
}

# Windows PowerShell 5.1 hands back a byte[] for responses whose content type has no charset
# (Spring Boot actuator answers application/vnd.spring-boot.actuator.v3+json), so decode explicitly.
function ConvertTo-Text($content) {
    if ($null -eq $content) { return '' }
    if ($content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($content) }
    return [string]$content
}

# Invoke-WebRequest throws on 4xx/5xx; this returns status, content type and body instead, on both
# Windows PowerShell 5.1 and PowerShell 7.
function Invoke-Http {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [string]$JsonBody
    )
    $arguments = @{
        Uri = $Uri
        Method = $Method
        UseBasicParsing = $true
        TimeoutSec = 30
        ErrorAction = 'Stop'
    }
    if ($Headers.Count -gt 0) { $arguments.Headers = $Headers }
    if (-not [string]::IsNullOrEmpty($JsonBody)) {
        $arguments.Body = $JsonBody
        $arguments.ContentType = 'application/json'
    }
    try {
        $response = Invoke-WebRequest @arguments
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            ContentType = [string]$response.Headers['Content-Type']
            Body = ConvertTo-Text $response.Content
        }
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        $status = [int]$webResponse.StatusCode
        # Windows PowerShell 5.1 surfaces HttpWebResponse, PowerShell 7 surfaces HttpResponseMessage.
        # Duck-typing keeps the script free of type literals from assemblies PS 5.1 has not loaded.
        if ($webResponse.PSObject.Methods['GetResponseStream']) {
            $contentType = [string]$webResponse.Headers['Content-Type']
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Dispose()
        } else {
            $contentType = [string]$webResponse.Content.Headers.ContentType
            $body = $webResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        return [pscustomobject]@{ Status = $status; ContentType = $contentType; Body = (ConvertTo-Text $body) }
    }
}

function Invoke-Management {
    param([string]$Method, [string]$Path, $Payload)
    $json = $null
    if ($null -ne $Payload) { $json = $Payload | ConvertTo-Json -Depth 10 -Compress }
    return Invoke-Http -Method $Method -Uri "$ManagementUrl$Path" -Headers $script:mgmtHeaders -JsonBody $json
}

function Get-QueueMessages {
    param([string]$Queue)
    $response = Invoke-Management -Method POST -Path "/api/queues/$script:vhostPath/$Queue/get" -Payload @{
        count = 50
        ackmode = 'ack_requeue_false'
        encoding = 'auto'
    }
    if ($response.Status -ne 200) { throw "Cannot read queue $Queue ($($response.Status)): $($response.Body)" }
    if ([string]::IsNullOrWhiteSpace($response.Body)) { return @() }
    return @($response.Body | ConvertFrom-Json)
}

function Clear-Queue {
    param([string]$Queue)
    Invoke-Management -Method DELETE -Path "/api/queues/$script:vhostPath/$Queue/contents" | Out-Null
}

function Get-QueueDepth {
    param([string]$Queue)
    $response = Invoke-Management -Method GET -Path "/api/queues/$script:vhostPath/$Queue"
    if ($response.Status -ne 200) { return -1 }
    return [int](($response.Body | ConvertFrom-Json).messages)
}

# Publishes one command; a raw payload is used for the malformed-JSON step.
function Send-Command {
    param(
        [string]$EventType,
        [string]$EventId,
        [string]$OrderId,
        [hashtable]$Payload,
        [string]$RawPayload
    )
    if ([string]::IsNullOrEmpty($RawPayload)) {
        $envelope = [ordered]@{
            eventId = $EventId
            eventType = $EventType
            eventVersion = 1
            occurredAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
            producer = 'order-service'
            correlationId = $OrderId
            traceId = [guid]::NewGuid().ToString('N')
            payload = $Payload
        }
        $RawPayload = $envelope | ConvertTo-Json -Depth 10 -Compress
    }
    return Invoke-Management -Method POST -Path "/api/exchanges/$script:vhostPath/malefashion.commands/publish" -Payload @{
        properties = @{
            content_type = 'application/json'
            delivery_mode = 2
            message_id = $EventId
            type = $EventType
            correlation_id = $OrderId
        }
        routing_key = $EventType
        payload = $RawPayload
        payload_encoding = 'string'
    }
}

function New-ProcessCommand {
    param([string]$OrderId, [string]$Scenario)
    return @{
        orderId = $OrderId
        amount = @{ amount = $script:amount; currency = 'VND' }
        idempotencyKey = $OrderId
        mockScenario = $Scenario
    }
}

function New-RefundCommand {
    param([string]$OrderId, [string]$PaymentId)
    return @{
        orderId = $OrderId
        paymentId = $PaymentId
        amount = @{ amount = $script:amount; currency = 'VND' }
        reason = 'STOCK_COMMIT_FAILED'
    }
}

# Waits for the first matching event and consumes it. Non-matching messages are discarded; the
# temporary queue only carries payment.* events from this smoke run.
function Wait-ForEvent {
    param([string]$EventType, [string]$OrderId)
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($message in (Get-QueueMessages -Queue $script:tempQueue)) {
            $envelope = $message.payload | ConvertFrom-Json
            if ($envelope.eventType -eq $EventType -and $envelope.correlationId -eq $OrderId) {
                return $envelope
            }
        }
        Start-Sleep -Milliseconds 250
    }
    return $null
}

function Test-NoMatchingEvent {
    param([string]$EventType, [string]$OrderId, [int]$Seconds = 4)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($message in (Get-QueueMessages -Queue $script:tempQueue)) {
            $envelope = $message.payload | ConvertFrom-Json
            if ($envelope.eventType -eq $EventType -and $envelope.correlationId -eq $OrderId) {
                return $false
            }
        }
        Start-Sleep -Milliseconds 250
    }
    return $true
}

function Wait-ForDlqMessage {
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-QueueDepth -Queue $script:dlq) -ge 1) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

Write-Host "payment-service smoke test"
Write-Host "  service      : $BaseUrl"
Write-Host "  management   : $ManagementUrl (vhost $VHost)"
Write-Host "  temp queue   : $script:tempQueue"
Write-Host '  profile note : mockScenario requires SPRING_PROFILES_ACTIVE=local'
Write-Host ''

try {
    # ---------------------------------------------------------------- setup
    $health = Invoke-Http -Method GET -Uri "$BaseUrl/actuator/health"
    $healthUp = $false
    if ($health.Status -eq 200) { $healthUp = (($health.Body | ConvertFrom-Json).status -eq 'UP') }
    Assert-That $healthUp "(a) service /actuator/health is UP"

    $created = Invoke-Management -Method PUT -Path "/api/queues/$script:vhostPath/$script:tempQueue" -Payload @{
        durable = $false
        auto_delete = $true
        arguments = @{}
    }
    Assert-That ($created.Status -in @(201, 204)) "0.1 temporary queue $script:tempQueue created"

    $bound = Invoke-Management -Method POST `
        -Path "/api/bindings/$script:vhostPath/e/malefashion.events/q/$script:tempQueue" `
        -Payload @{ routing_key = 'payment.*' }
    Assert-That ($bound.Status -in @(201, 204)) "0.2 temporary queue bound to malefashion.events with payment.*"

    Clear-Queue -Queue $script:dlq

    # ---------------------------------------------------------------- (b) payment succeeds
    $orderId = [guid]::NewGuid().ToString()
    $processEventId = [guid]::NewGuid().ToString()
    $processPayload = New-ProcessCommand -OrderId $orderId -Scenario 'SUCCESS'
    $published = Send-Command -EventType 'payment.process-payment' -EventId $processEventId `
        -OrderId $orderId -Payload $processPayload
    $routed = $false
    if ($published.Status -eq 200) { $routed = (($published.Body | ConvertFrom-Json).routed -eq $true) }
    Assert-That $routed "(b.1) process-payment SUCCESS published and routed for order $orderId"

    $succeeded = Wait-ForEvent -EventType 'payment.succeeded' -OrderId $orderId
    Assert-That ($null -ne $succeeded) "(b.2) payment.succeeded received for order $orderId"

    $paymentId = $null
    $providerTransactionId = $null
    if ($null -ne $succeeded) {
        $paymentId = $succeeded.payload.paymentId
        $providerTransactionId = $succeeded.payload.providerTransactionId
    }
    Assert-That (-not [string]::IsNullOrEmpty($paymentId)) "(b.3) payment.succeeded carries paymentId=$paymentId"

    $amountOk = $false
    if ($null -ne $succeeded) {
        $amountOk = ($succeeded.payload.amount.amount -eq $script:amount) -and
                    ($succeeded.payload.amount.currency -eq 'VND')
    }
    Assert-That $amountOk "(b.4) payment.succeeded amount is $script:amount VND"

    $providerOk = $false
    if (-not [string]::IsNullOrEmpty($providerTransactionId)) {
        $providerOk = $providerTransactionId -match '^MOCK-TXN-\d{8}-\d{6}$'
    }
    Assert-That $providerOk "(b.5) providerTransactionId matches MOCK-TXN-yyyyMMdd-NNNNNN ($providerTransactionId)"

    # ---------------------------------------------------------------- (c) duplicate eventId
    Clear-Queue -Queue $script:tempQueue
    Send-Command -EventType 'payment.process-payment' -EventId $processEventId `
        -OrderId $orderId -Payload $processPayload | Out-Null
    Assert-That (Test-NoMatchingEvent -EventType 'payment.succeeded' -OrderId $orderId) `
        "(c) resending the same eventId produced no new payment.succeeded"

    # ---------------------------------------------------------------- (d) payment fails
    $failedOrderId = [guid]::NewGuid().ToString()
    $failedEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'payment.process-payment' -EventId $failedEventId `
        -OrderId $failedOrderId -Payload (New-ProcessCommand -OrderId $failedOrderId -Scenario 'FAILURE') | Out-Null
    $failed = Wait-ForEvent -EventType 'payment.failed' -OrderId $failedOrderId
    $failedCode = $null
    if ($null -ne $failed) { $failedCode = $failed.payload.failureCode }
    Assert-That ($null -ne $failed -and $failedCode -eq 'PAYMENT_DECLINED') `
        "(d) payment.failed PAYMENT_DECLINED received for order $failedOrderId"

    # ---------------------------------------------------------------- (e) payment times out
    $timeoutOrderId = [guid]::NewGuid().ToString()
    $timeoutEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'payment.process-payment' -EventId $timeoutEventId `
        -OrderId $timeoutOrderId -Payload (New-ProcessCommand -OrderId $timeoutOrderId -Scenario 'TIMEOUT') | Out-Null
    $timedOut = Wait-ForEvent -EventType 'payment.failed' -OrderId $timeoutOrderId
    $timeoutCode = $null
    if ($null -ne $timedOut) { $timeoutCode = $timedOut.payload.failureCode }
    Assert-That ($null -ne $timedOut -and $timeoutCode -eq 'PAYMENT_TIMEOUT') `
        "(e) payment.failed PAYMENT_TIMEOUT received for order $timeoutOrderId"

    # ---------------------------------------------------------------- (f) refund succeeds
    $refundEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'payment.refund-payment' -EventId $refundEventId `
        -OrderId $orderId -Payload (New-RefundCommand -OrderId $orderId -PaymentId $paymentId) | Out-Null
    $refunded = Wait-ForEvent -EventType 'payment.refunded' -OrderId $orderId
    Assert-That ($null -ne $refunded) "(f.1) payment.refunded received for order $orderId"

    $refundId = $null
    $refundPaymentId = $null
    if ($null -ne $refunded) {
        $refundId = $refunded.payload.refundId
        $refundPaymentId = $refunded.payload.paymentId
    }
    Assert-That (-not [string]::IsNullOrEmpty($refundId) -and $refundPaymentId -eq $paymentId) `
        "(f.2) payment.refunded carries refundId=$refundId and paymentId=$refundPaymentId"

    # ---------------------------------------------------------------- (g) refund replay
    $secondRefundEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'payment.refund-payment' -EventId $secondRefundEventId `
        -OrderId $orderId -Payload (New-RefundCommand -OrderId $orderId -PaymentId $paymentId) | Out-Null
    $replayed = Wait-ForEvent -EventType 'payment.refunded' -OrderId $orderId
    $replayedRefundId = $null
    if ($null -ne $replayed) { $replayedRefundId = $replayed.payload.refundId }
    Assert-That ($null -ne $replayed -and $replayedRefundId -eq $refundId) `
        "(g) second refund reused refundId=$replayedRefundId"

    # ---------------------------------------------------------------- (h) refund unknown order
    $unknownOrderId = [guid]::NewGuid().ToString()
    $unknownRefundEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'payment.refund-payment' -EventId $unknownRefundEventId `
        -OrderId $unknownOrderId `
        -Payload (New-RefundCommand -OrderId $unknownOrderId -PaymentId ([guid]::NewGuid().ToString())) | Out-Null
    Assert-That (Test-NoMatchingEvent -EventType 'payment.refunded' -OrderId $unknownOrderId) `
        "(h) refund for unknown order $unknownOrderId produced no event"

    # ---------------------------------------------------------------- (i) malformed JSON -> DLQ
    Clear-Queue -Queue $script:dlq
    Send-Command -EventType 'payment.process-payment' -EventId ([guid]::NewGuid().ToString()) `
        -OrderId ([guid]::NewGuid().ToString()) -RawPayload '{ "eventId": "not-json", "eventType": ' | Out-Null
    Assert-That (Wait-ForDlqMessage) "(i) malformed JSON landed in $script:dlq"
}
catch {
    Add-Fail "unexpected error: $($_.Exception.Message)"
}
finally {
    try {
        Invoke-Management -Method DELETE -Path "/api/queues/$script:vhostPath/$script:tempQueue" | Out-Null
    } catch {
        Add-Fail "could not delete temporary queue $script:tempQueue ($($_.Exception.Message))"
    }
    try {
        Clear-Queue -Queue $script:dlq
    } catch {
        # the DLQ belongs to the service; a failed purge must not hide the test result
    }
}

Write-Host ''
Write-Host ("PASS: {0}  FAIL: {1}" -f $script:passes, $script:failures)
if ($script:failures -gt 0) { exit 1 }
exit 0
