<#
    Collects one airline from this machine.

    Transavia needs it. It answers GitHub's runners 403 to every request - a flat refusal
    from the first call, not one that creeps in under load - while the same requests at the
    same pace answer normally from a home connection. It blocks the address rather than the
    rate, and there is nothing to fix on our side, so the scheduled job leaves it out and it
    is collected from here instead. A full pass takes about ten minutes.

    Any airline can be named; the other four collect themselves on schedule and only need
    this if you want them refreshed sooner.

        .\collect-locally.ps1                     # transavia, reusing known routes
        .\collect-locally.ps1 -Airline wizzair    # some other airline
        .\collect-locally.ps1 -Rediscover         # re-scan the network as well

    The database is whatever SPRING_DATASOURCE_* say - the same cloud database the
    scheduled runs and the website use, so what this collects is live immediately.
#>
param(
    [string]$Airline = "transavia",
    [switch]$Rediscover,
    [int]$Port = 8171
)

$ErrorActionPreference = "Stop"

foreach ($name in @("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        $fromUser = [Environment]::GetEnvironmentVariable($name, "User")
        if (-not $fromUser) { throw "$name is not set - the collection has no database to write to." }
        Set-Item -Path "env:$name" -Value $fromUser
    }
}

Write-Host "Building..."
& ".\gradlew.bat" :app:bootJar -q --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$jar = Get-ChildItem "app\build\libs\*.jar" | Where-Object { $_.Name -notlike "*-plain.jar" } | Select-Object -First 1
$log = "collect-$Airline.log"

Write-Host "Starting the application on port $Port..."
$app = Start-Process -FilePath "java" -PassThru -WindowStyle Hidden `
    -ArgumentList "--enable-preview", "-jar", $jar.FullName, "--server.port=$Port" `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"

try {
    $up = $false
    foreach ($attempt in 1..40) {
        Start-Sleep -Seconds 2
        if (Select-String -Path $log -Pattern "Started Application" -Quiet -ErrorAction SilentlyContinue) { $up = $true; break }
        if ($app.HasExited) { throw "The application stopped while starting up - see $log." }
    }
    if (-not $up) { throw "The application did not start in time - see $log." }

    $rediscoverRoutes = if ($Rediscover) { "true" } else { "false" }
    Write-Host "Collecting $Airline (rediscoverRoutes=$rediscoverRoutes). This can take a while."
    # Fired and then watched in the log rather than waited on: a full pass outlasts any
    # sensible HTTP timeout, and the request returning is not what tells you it went well.
    try {
        Invoke-RestMethod -Method Post -TimeoutSec 5 `
            -Uri "http://localhost:$Port/collect/$($Airline)?rediscoverRoutes=$rediscoverRoutes" | Out-Null
    } catch [System.Net.WebException], [System.TimeoutException] { }

    $airlineUpper = $Airline.ToUpper()
    while ($true) {
        Start-Sleep -Seconds 10
        if ($app.HasExited) { throw "The application stopped mid-collection - see $log." }
        $done = Select-String -Path $log -Pattern "$airlineUpper collection (completed|abandoned)|$airlineUpper collected no fares" -ErrorAction SilentlyContinue
        if ($done) { break }
        $routes = (Select-String -Path $log -Pattern "Loading .* fares for route" -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-Host "  $routes routes so far..."
    }

    Select-String -Path $log -Pattern "collection completed|collection abandoned|collected no fares|refused \d+ requests" |
        ForEach-Object { "  " + $_.Line.Substring([Math]::Max(0, $_.Line.IndexOf(': ') + 2)) }
}
finally {
    if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "Done. Full log in $log"
}
