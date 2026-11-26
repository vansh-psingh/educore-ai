# EducoreAI auto-start watchdog
# Runs at user logon (via Task Scheduler). Ensures:
#   1. Java backend is listening on :8080
#   2. cloudflared quick tunnel is running
#   3. If the tunnel URL changed, api.js is patched and Firebase Hosting redeployed
$ErrorActionPreference = "Continue"
$Root   = "A:\Projects\EducoreAI"
$Java   = "A:\Projects\EducoreAI\target\educore-ai-1.0.0.jar"
$Cf     = "C:\Program Files (x86)\cloudflared\cloudflared.exe"
$Fb     = "C:\Users\Acer\AppData\Roaming\npm\firebase.cmd"
$JdkBin = "C:\Program Files\Microsoft\jdk-17.0.21.102-hotspot\bin"
$LogDir = Join-Path $Root "data"
New-Item -ItemType Directory -Force $LogDir | Out-Null

function Test-Port8080 {
    return [bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
}

# --- 1. Java backend ---
if (-not (Test-Port8080)) {
    $env:PATH = "$JdkBin;$env:PATH"
    Start-Process -FilePath "java" -ArgumentList '-jar', "`"$Java`"", 'web', '8080' `
        -WorkingDirectory $Root -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir "server.log") `
        -RedirectStandardError  (Join-Path $LogDir "server-err.log")
    Start-Sleep -Seconds 6
}

# --- 2. cloudflared tunnel ---
$tunnelRunning = Get-Process cloudflared -ErrorAction SilentlyContinue
if (-not $tunnelRunning) {
    Start-Process -FilePath $Cf -ArgumentList 'tunnel', '--url', 'http://localhost:8080' `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir "tunnel-out.log") `
        -RedirectStandardError  (Join-Path $LogDir "tunnel.log")
    Start-Sleep -Seconds 15
}

# --- 3. Discover tunnel URL and patch frontend if it changed ---
$url = $null
foreach ($logFile in @((Join-Path $LogDir "tunnel.log"), (Join-Path $LogDir "tunnel-out.log"))) {
    if (Test-Path $logFile) {
        $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        $m = [regex]::Matches($content, 'https://[a-z0-9\-]+\.trycloudflare\.com')
        if ($m.Count -gt 0) { $url = $m[$m.Count - 1].Value }
    }
}
if (-not $url) { exit 0 }   # tunnel still starting or failed - try again next logon

$apiJs = Join-Path $Root "src\main\resources\static\js\api.js"
$js = Get-Content $apiJs -Raw
if ($js -match [regex]::Escape($url)) { exit 0 }  # already current

$js = $js -replace 'https://[a-z0-9\-]+\.trycloudflare\.com', $url
Set-Content $apiJs $js -NoNewline

# sync static assets into deploy/ and push to Firebase Hosting
$src = Join-Path $Root "src\main\resources\static"
$dst = Join-Path $Root "deploy"
Get-ChildItem (Join-Path $src "*.html") | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $dst $_.Name) -Force
}
Copy-Item (Join-Path $src "css"), (Join-Path $src "js") (Join-Path $dst "static") -Recurse -Force
Push-Location $Root
& $Fb deploy --only hosting | Out-File (Join-Path $LogDir "firebase-deploy.log")
Pop-Location
