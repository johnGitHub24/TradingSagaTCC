# run-api-smoke.ps1 - L1 API Runtime Smoke (no Node required)
# Usage:
#   .\docs\run-api-smoke.ps1
#   .\docs\run-api-smoke.ps1 -BaseUrl http://localhost:8093
param(
    [string]$BaseUrl = 'http://localhost:8093',
    [int]$PollTimeoutSec = 15
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$api = '{0}/api/v1' -f $BaseUrl
$healthUrl = '{0}/actuator/health' -f $BaseUrl

function Wait-SagaStatus {
    param([string]$SagaId, [string]$Expect)
    $deadline = (Get-Date).AddSeconds($PollTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $s = Invoke-RestMethod -Uri ('{0}/sagas/{1}' -f $api, $SagaId) -TimeoutSec 5
            if ($s.status -eq $Expect) { return $s }
            if ($Expect -eq 'COMPENSATED' -and $s.status -eq 'FAILED') { return $s }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "Saga $SagaId did not reach $Expect within ${PollTimeoutSec}s"
}

function Reset-Account {
    $a = Invoke-RestMethod -Method Post -Uri ('{0}/accounts/ACC-001/reset' -f $api)
    if ([decimal]$a.available -ne 100000) {
        throw "reset expected available=100000 got $($a.available)"
    }
}

function Get-Available {
    $a = Invoke-RestMethod -Uri ('{0}/accounts/ACC-001' -f $api)
    return [decimal]$a.available
}

function Assert-Reservation {
    param([string]$SagaId, [bool]$Exists, [string]$State, [string]$CaseId)
    $r = Invoke-RestMethod -Uri ('{0}/tcc/reservations/{1}' -f $api, $SagaId)
    if ([bool]$r.exists -ne $Exists) { throw "$CaseId TCC-001 exists expected $Exists got $($r.exists)" }
    if ($Exists -and $r.state -ne $State) { throw "$CaseId TCC-001 state expected $State got $($r.state)" }
    if ($Exists) { return $r.state } else { return 'NO_TICKET' }
}

function Place-Trade {
    param([int]$Qty, [int]$Price, [bool]$ForceFail)
    $body = @{
        accountId = 'ACC-001'
        symbol    = 'BTCUSDT'
        side      = 'BUY'
        quantity  = $Qty
        price     = $Price
        forceFail = $ForceFail
    } | ConvertTo-Json
    $data = Invoke-RestMethod -Method Post -Uri ('{0}/trades' -f $api) `
        -ContentType 'application/json' -Body $body
    if (-not $data.sagaId) { throw 'POST /trades missing sagaId' }
    return $data.sagaId
}

Write-Host "API Smoke L1 -> $BaseUrl" -ForegroundColor Cyan

$h = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
if ($h.status -ne 'UP') { throw "health not UP: $($h.status)" }
Write-Host 'health=UP' -ForegroundColor Green

$ui = Invoke-WebRequest -Uri $BaseUrl -UseBasicParsing -TimeoutSec 5
if ($ui.StatusCode -ne 200) { throw "UI home not 200: $($ui.StatusCode)" }
Write-Host 'UI=200' -ForegroundColor Green

$results = @{}

# DASH-001: Dashboard static assets reachable with markers
$dashJs = Invoke-WebRequest -Uri ('{0}/dashboard.js' -f $BaseUrl) -UseBasicParsing -TimeoutSec 5
if ($dashJs.StatusCode -ne 200 -or $dashJs.Content -notmatch 'export function buildLanes') {
    throw 'DASH-001 /dashboard.js missing buildLanes'
}
if ($ui.Content -notmatch 'data-testid="sm-dashboard"') { throw 'DASH-001 index.html missing sm-dashboard' }
$spec = Invoke-WebRequest -Uri ('{0}/test/dashboard.spec.js' -f $BaseUrl) -UseBasicParsing -TimeoutSec 5
if ($spec.StatusCode -ne 200) { throw 'DASH-001 /test/dashboard.spec.js not 200' }
$results['DASH-001'] = 'assets=200'
Write-Host 'DASH-001 PASS (dashboard assets)' -ForegroundColor Green

Reset-Account | Out-Null
$id = Place-Trade -Qty 1 -Price 10000 -ForceFail $false
$s = Wait-SagaStatus -SagaId $id -Expect 'COMPLETED'
$av = Get-Available
if ($av -ne 90000) { throw "SAGA-001 available expected 90000 got $av" }
$t1 = Assert-Reservation -SagaId $id -Exists $true -State 'CONFIRMED' -CaseId 'SAGA-001'
$results['SAGA-001'] = "COMPLETED/$av/$t1"
Write-Host "SAGA-001 PASS ($($s.status) available=$av reservation=$t1)" -ForegroundColor Green

Reset-Account | Out-Null
$id = Place-Trade -Qty 1 -Price 999999 -ForceFail $false
$s = Wait-SagaStatus -SagaId $id -Expect 'COMPENSATED'
$av = Get-Available
if ($av -ne 100000) { throw "SAGA-002 available expected 100000 got $av" }
$t2 = Assert-Reservation -SagaId $id -Exists $false -State '' -CaseId 'SAGA-002'
$results['SAGA-002'] = "COMPENSATED/$av/$t2"
Write-Host "SAGA-002 PASS ($($s.status) available=$av reservation=$t2)" -ForegroundColor Green

Reset-Account | Out-Null
$id = Place-Trade -Qty 1 -Price 10000 -ForceFail $true
$s = Wait-SagaStatus -SagaId $id -Expect 'COMPENSATED'
$av = Get-Available
if ($av -ne 100000) { throw "TCC-002 available expected 100000 got $av" }
$t3 = Assert-Reservation -SagaId $id -Exists $true -State 'CANCELLED' -CaseId 'TCC-002'
$results['TCC-002'] = "COMPENSATED/$av/$t3"
Write-Host "TCC-002 PASS ($($s.status) available=$av reservation=$t3)" -ForegroundColor Green

$t0 = Assert-Reservation -SagaId 'no-such-saga' -Exists $false -State '' -CaseId 'TCC-001'
$results['TCC-001'] = "unknown=$t0"
Write-Host "TCC-001 PASS (unknown sagaId -> 200 exists=false)" -ForegroundColor Green

try {
    Invoke-RestMethod -Uri ('{0}/trades/missing-order' -f $api) -ErrorAction Stop
    throw 'TRADE-001 expected 404'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -ne 404) { throw "TRADE-001 expected 404 got $code" }
}
$results['TRADE-001'] = '404'
Write-Host 'TRADE-001 PASS (404)' -ForegroundColor Green

Write-Host ''
Write-Host 'ALL_API_SMOKE_OK' -ForegroundColor Green
Write-Host ('劇情: ' + (($results.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join ' ; '))
