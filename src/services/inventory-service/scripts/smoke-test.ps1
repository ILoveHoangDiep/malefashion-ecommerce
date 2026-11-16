#requires -Version 5.1
<#
.SYNOPSIS
    End-to-end smoke test for inventory-service against real PostgreSQL and RabbitMQ.

.DESCRIPTION
    Publishes real commands through the RabbitMQ management API, observes events on a temporary
    queue bound to malefashion.events with routing key "inventory.*", and checks the REST API.
    Every step prints PASS or FAIL; the script exits with 1 if any step failed.

    The temporary queue is always deleted, even when a step fails.

.EXAMPLE
    .\scripts\smoke-test.ps1

.EXAMPLE
    .\scripts\smoke-test.ps1 -ManagementUrl http://localhost:15674 `
        -VariantId 514a23b0-f6c0-52a2-95ca-2f5fce134d37
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8085',
    [string]$ManagementUrl = 'http://localhost:15672',
    [string]$RabbitUser = 'guest',
    [string]$RabbitPassword = 'guest',
    [string]$VHost = '/',
    [string]$VariantId = '52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5',
    [int]$WaitSeconds = 20
)

$ErrorActionPreference = 'Stop'
$script:passes = 0
$script:failures = 0
$script:tempQueue = 'smoke-inventory-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$script:vhostPath = [uri]::EscapeDataString($VHost)
$script:mgmtHeaders = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
}
$script:dlq = 'inventory-service.commands.dlq'

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

function Get-Stock {
    param([string]$Id)
    $response = Invoke-Http -Method GET -Uri "$BaseUrl/api/v1/inventory/variants/$Id" `
        -Headers @{ 'X-Trace-Id' = '4bf92f3577b34da6a3ce929d0e0e4736' }
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($response.Body)) {
        try { $json = $response.Body | ConvertFrom-Json } catch { $json = $null }
    }
    return [pscustomobject]@{
        Status = $response.Status
        ContentType = $response.ContentType
        Body = $response.Body
        Json = $json
    }
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

# Waits for the first matching event and consumes it. Non-matching messages are discarded; the
# temporary queue only carries inventory.* events from this smoke run.
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

Write-Host "inventory-service smoke test"
Write-Host "  service      : $BaseUrl"
Write-Host "  management   : $ManagementUrl (vhost $VHost)"
Write-Host "  variant      : $VariantId"
Write-Host "  temp queue   : $script:tempQueue"
Write-Host ''

try {
    # ---------------------------------------------------------------- setup
    $health = Invoke-Http -Method GET -Uri "$BaseUrl/actuator/health"
    $healthUp = $false
    if ($health.Status -eq 200) { $healthUp = (($health.Body | ConvertFrom-Json).status -eq 'UP') }
    Assert-That $healthUp "0.1 service /actuator/health is UP"

    $created = Invoke-Management -Method PUT -Path "/api/queues/$script:vhostPath/$script:tempQueue" -Payload @{
        durable = $false
        auto_delete = $true
        arguments = @{}
    }
    Assert-That ($created.Status -in @(201, 204)) "0.2 temporary queue $script:tempQueue created"

    $bound = Invoke-Management -Method POST `
        -Path "/api/bindings/$script:vhostPath/e/malefashion.events/q/$script:tempQueue" `
        -Payload @{ routing_key = 'inventory.*' }
    Assert-That ($bound.Status -in @(201, 204)) "0.3 temporary queue bound to malefashion.events with inventory.*"

    Clear-Queue -Queue $script:dlq

    # ---------------------------------------------------------------- (a) stock before
    $before = Get-Stock -Id $VariantId
    $sku = $null
    $availableBefore = -1
    if ($null -ne $before.Json) {
        $sku = $before.Json.sku
        $availableBefore = [int]$before.Json.availableQuantity
    }
    Assert-That ($before.Status -eq 200 -and $availableBefore -ge 2) `
        "(a) GET stock before: status=$($before.Status) sku=$sku available=$availableBefore"

    # ---------------------------------------------------------------- (b) reserve
    $orderId = [guid]::NewGuid().ToString()
    $reserveEventId = [guid]::NewGuid().ToString()
    $reservePayload = @{
        orderId = $orderId
        items = @(@{ variantId = $VariantId; sku = $sku; quantity = 2 })
    }
    $published = Send-Command -EventType 'inventory.reserve-stock' -EventId $reserveEventId `
        -OrderId $orderId -Payload $reservePayload
    $routed = $false
    if ($published.Status -eq 200) { $routed = (($published.Body | ConvertFrom-Json).routed -eq $true) }
    Assert-That $routed "(b.1) reserve-stock command published and routed"

    $reserved = Wait-ForEvent -EventType 'inventory.stock-reserved' -OrderId $orderId
    Assert-That ($null -ne $reserved) "(b.2) inventory.stock-reserved received for order $orderId"

    $afterReserve = Get-Stock -Id $VariantId
    $reservedQuantity = -1
    if ($null -ne $afterReserve.Json) { $reservedQuantity = [int]$afterReserve.Json.availableQuantity }
    Assert-That ($reservedQuantity -eq ($availableBefore - 2)) `
        "(b.3) available after reserve = $reservedQuantity (expected $($availableBefore - 2))"

    $reservationId = $null
    $reservedItems = -1
    if ($null -ne $reserved) {
        $reservationId = $reserved.payload.reservationId
        $reservedItems = @($reserved.payload.items).Count
    }
    Assert-That (-not [string]::IsNullOrEmpty($reservationId) -and $reservedItems -eq 1) `
        "(b.4) stock-reserved carries reservationId=$reservationId and 1 item"

    # ---------------------------------------------------------------- (c) duplicate command
    Clear-Queue -Queue $script:tempQueue
    Send-Command -EventType 'inventory.reserve-stock' -EventId $reserveEventId `
        -OrderId $orderId -Payload $reservePayload | Out-Null
    Assert-That (Test-NoMatchingEvent -EventType 'inventory.stock-reserved' -OrderId $orderId) `
        "(c.1) resending the same eventId produced no new stock-reserved"

    $afterDuplicate = Get-Stock -Id $VariantId
    $availableAfterDuplicate = -1
    if ($null -ne $afterDuplicate.Json) { $availableAfterDuplicate = [int]$afterDuplicate.Json.availableQuantity }
    Assert-That ($availableAfterDuplicate -eq $reservedQuantity) `
        "(c.2) resending the same eventId kept available at $availableAfterDuplicate"

    # ---------------------------------------------------------------- (d) commit
    $commitEventId = [guid]::NewGuid().ToString()
    $commitPublished = Send-Command -EventType 'inventory.commit-stock' -EventId $commitEventId `
        -OrderId $orderId -Payload @{ orderId = $orderId; reservationId = $reservationId }
    Assert-That ($commitPublished.Status -eq 200) "(d.1) commit-stock command published"

    $committed = Wait-ForEvent -EventType 'inventory.stock-committed' -OrderId $orderId
    Assert-That ($null -ne $committed) "(d.2) inventory.stock-committed received for order $orderId"

    $afterCommit = Get-Stock -Id $VariantId
    $availableAfterCommit = -1
    if ($null -ne $afterCommit.Json) { $availableAfterCommit = [int]$afterCommit.Json.availableQuantity }
    Assert-That ($availableAfterCommit -eq $reservedQuantity) `
        "(d.3) commit kept available at $availableAfterCommit (no return to stock)"

    # ---------------------------------------------------------------- (e) reserve + release
    $releaseOrderId = [guid]::NewGuid().ToString()
    $releaseReserveEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'inventory.reserve-stock' -EventId $releaseReserveEventId `
        -OrderId $releaseOrderId `
        -Payload @{ orderId = $releaseOrderId; items = @(@{ variantId = $VariantId; sku = $sku; quantity = 1 }) } | Out-Null
    $secondReserved = Wait-ForEvent -EventType 'inventory.stock-reserved' -OrderId $releaseOrderId
    Assert-That ($null -ne $secondReserved) "(e.1) second stock-reserved received for order $releaseOrderId"

    $secondReservationId = $null
    if ($null -ne $secondReserved) { $secondReservationId = $secondReserved.payload.reservationId }
    $afterSecondReserve = Get-Stock -Id $VariantId
    $availableAfterSecondReserve = -1
    if ($null -ne $afterSecondReserve.Json) {
        $availableAfterSecondReserve = [int]$afterSecondReserve.Json.availableQuantity
    }
    Assert-That ($availableAfterSecondReserve -eq ($availableAfterCommit - 1)) `
        "(e.2) second reserve left available at $availableAfterSecondReserve"

    $releaseEventId = [guid]::NewGuid().ToString()
    Send-Command -EventType 'inventory.release-stock' -EventId $releaseEventId `
        -OrderId $releaseOrderId `
        -Payload @{
            orderId = $releaseOrderId
            reservationId = $secondReservationId
            reason = 'PAYMENT_FAILED'
        } | Out-Null
    $released = Wait-ForEvent -EventType 'inventory.stock-released' -OrderId $releaseOrderId
    Assert-That ($null -ne $released) "(e.3) inventory.stock-released received for order $releaseOrderId"

    $afterRelease = Get-Stock -Id $VariantId
    $availableAfterRelease = -1
    if ($null -ne $afterRelease.Json) { $availableAfterRelease = [int]$afterRelease.Json.availableQuantity }
    Assert-That ($availableAfterRelease -eq $availableAfterCommit) `
        "(e.4) release returned stock: available=$availableAfterRelease (expected $availableAfterCommit)"

    # ---------------------------------------------------------------- (f) insufficient stock
    $failedOrderId = [guid]::NewGuid().ToString()
    $failedEventId = [guid]::NewGuid().ToString()
    $tooMany = $availableAfterRelease + 1
    Send-Command -EventType 'inventory.reserve-stock' -EventId $failedEventId `
        -OrderId $failedOrderId `
        -Payload @{ orderId = $failedOrderId; items = @(@{ variantId = $VariantId; sku = $sku; quantity = $tooMany }) } | Out-Null
    $failed = Wait-ForEvent -EventType 'inventory.stock-reservation-failed' -OrderId $failedOrderId
    Assert-That ($null -ne $failed) "(f.1) inventory.stock-reservation-failed received for order $failedOrderId"

    $failedReason = $null
    $failedItems = 0
    if ($null -ne $failed) {
        $failedReason = $failed.payload.reason
        $failedItems = @($failed.payload.failedItems).Count
    }
    Assert-That ($failedReason -eq 'INSUFFICIENT_STOCK' -and $failedItems -ge 1) `
        "(f.2) failure reason=$failedReason with $failedItems failed item(s)"

    $afterFailure = Get-Stock -Id $VariantId
    $availableAfterFailure = -1
    if ($null -ne $afterFailure.Json) { $availableAfterFailure = [int]$afterFailure.Json.availableQuantity }
    Assert-That ($availableAfterFailure -eq $availableAfterRelease) `
        "(f.3) failed reserve kept available at $availableAfterFailure"

    # ---------------------------------------------------------------- (g) problem detail 404
    $missing = Get-Stock -Id '00000000-0000-4000-8000-000000000000'
    $problemCode = $null
    if ($null -ne $missing.Json) { $problemCode = $missing.Json.code }
    $problemJson = ($missing.ContentType -like '*application/problem+json*')
    Assert-That ($missing.Status -eq 404 -and $problemJson -and $problemCode -eq 'VARIANT_NOT_FOUND') `
        "(g) unknown variant returned 404 problem+json code=$problemCode"

    # ---------------------------------------------------------------- (h) malformed JSON -> DLQ
    Clear-Queue -Queue $script:dlq
    Send-Command -EventType 'inventory.reserve-stock' -EventId ([guid]::NewGuid().ToString()) `
        -OrderId ([guid]::NewGuid().ToString()) -RawPayload '{ "eventId": "not-json", "eventType": ' | Out-Null
    Assert-That (Wait-ForDlqMessage) "(h) malformed JSON landed in $script:dlq"
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
