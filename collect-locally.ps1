<#
    Collects one airline from this machine into the same cloud database everything else writes
    to - the one the scheduled GitHub runs and the website use. There is no separate local
    store: what this collects is live the moment it lands.

    Transavia needs it. It answers GitHub's runners 403 to every request, a flat refusal from
    the very first call, while the identical requests at the identical pace answer normally from
    a home connection. It blocks the address rather than the rate, so there is nothing to fix on
    our side and it is left out of the scheduled run. A full pass takes about ten minutes.

    Any airline can be named; the other four collect themselves on schedule and only need this
    if you want them refreshed sooner.

    Run it through collect-locally.cmd rather than directly - Windows refuses to run .ps1 files
    by default, and the wrapper gets around that for the one invocation without changing any
    machine setting.

        collect-locally.cmd                       # transavia, reusing known routes
        collect-locally.cmd wizzair               # some other airline
        collect-locally.cmd transavia -Rediscover # re-scan the network as well
#>
param(
    [string]$Airline = "transavia",
    [switch]$Rediscover,
    [int]$Port = 8171
)

$ErrorActionPreference = "Stop"

# Java 21 specifically, not merely the newest installed. The build compiles with
# --enable-preview and preview features are tied to the exact release that produced them, so
# classes built by 21 will not run on 25 - which is what "pick the highest version" quietly did.
$RequiredJava = "21"

function Test-JavaVersion($javaHome) {
    if (-not $javaHome -or -not (Test-Path "$javaHome\bin\java.exe")) { return $false }
    # Read from the JDK's own release file rather than running "java -version". The version
    # only goes to stderr, and Windows PowerShell turns a native command's stderr into a
    # terminating error under ErrorActionPreference = Stop - so asking Java directly makes the
    # check fail on exactly the machine it was meant to help.
    $release = Join-Path $javaHome "release"
    if (-not (Test-Path $release)) { return $false }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
    if (-not $line) { return $false }
    return $line.Matches[0].Groups[1].Value -like "$RequiredJava.*"
}

function Resolve-JavaHome {
    if (Test-JavaVersion $env:JAVA_HOME) { return $env:JAVA_HOME }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        $fromPath = Split-Path (Split-Path $onPath.Source -Parent) -Parent
        if (Test-JavaVersion $fromPath) { return $fromPath }
    }
    $places = @(
        "$env:USERPROFILE\.jdks\*",
        "${env:ProgramFiles}\Eclipse Adoptium\jdk*",
        "${env:ProgramFiles}\Java\jdk*",
        "${env:ProgramFiles}\Microsoft\jdk*"
    )
    foreach ($place in $places) {
        $match = Get-ChildItem $place -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-JavaVersion $_.FullName } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($match) { return $match.FullName }
    }
    throw "No Java $RequiredJava found. Set JAVA_HOME to a JDK $RequiredJava or put one on the PATH - the build uses preview features, which run only on the release that compiled them."
}

$env:JAVA_HOME = Resolve-JavaHome
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "Java: $env:JAVA_HOME"

# The database is whatever these say. They are normally set for the user account rather than the
# session, so they are read from there when the session hasn't inherited them.
foreach ($name in @("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
    if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue)) {
        $stored = [Environment]::GetEnvironmentVariable($name, "User")
        if (-not $stored) { throw "$name is not set - the collection has no database to write to." }
        Set-Item -Path "env:$name" -Value $stored
    }
}

Set-Location $PSScriptRoot

Write-Host "Building..."
& ".\gradlew.bat" :app:bootJar -q --console=plain 2>&1 | Out-String | Write-Verbose
if ($LASTEXITCODE -ne 0) { throw "Build failed - run .\gradlew.bat :app:bootJar to see why." }

$jar = Get-ChildItem "app\build\libs\*.jar" | Where-Object { $_.Name -notlike "*-plain.jar" } | Select-Object -First 1
$log = "collect-$Airline.log"

Write-Host "Starting the application on port $Port..."
$app = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -PassThru -WindowStyle Hidden `
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
    # Fired and then watched in the log rather than waited on: a full pass outlasts any sensible
    # HTTP timeout, and the request returning is not what tells you whether it went well.
    Start-Job -ScriptBlock {
        param($uri)
        try { Invoke-WebRequest -Method Post -Uri $uri -TimeoutSec 21600 -UseBasicParsing | Out-Null } catch { }
    } -ArgumentList "http://localhost:$Port/collect/$Airline`?rediscoverRoutes=$rediscoverRoutes" | Out-Null

    $airlineUpper = $Airline.ToUpper()
    $finished = "$airlineUpper collection (completed|abandoned)|Failed to collect data for $airlineUpper"
    while ($true) {
        Start-Sleep -Seconds 10
        if ($app.HasExited) { throw "The application stopped mid-collection - see $log." }
        if (Select-String -Path $log -Pattern $finished -Quiet -ErrorAction SilentlyContinue) { break }
        $routes = (Select-String -Path $log -Pattern "Loading .* fares for route" -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-Host "  $routes routes so far..."
    }

    Select-String -Path $log -Pattern "collection completed|collection abandoned|not one fare back|refused \d+ requests|answered 404" |
        ForEach-Object { "  " + ($_.Line -replace '^.*?\s:\s','') }
}
finally {
    Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue
    if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "Done. Full log in $log"
}
