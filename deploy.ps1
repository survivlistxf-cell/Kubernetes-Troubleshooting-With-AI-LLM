<#
.SYNOPSIS
  Build + push + rollout pentru aplicatia kubexplain pe clusterul OpenStack.

.DESCRIPTION
  Alegi ce servicii redeployezi cu -Ai / -Backend / -Frontend.
  FARA niciun switch de serviciu le face pe toate trei.

  Ordinea (corecta, ca sa nu ruleze imagine veche):
    1. docker build + push pentru serviciile alese
    2. kubectl apply -k k8s/   (sync env / imagePullPolicy / hostAliases / secret refs)
    3. kubectl rollout restart (forteaza un pod nou, deci trage latest proaspat)
    4. rollout status + verificare num_ctx in loguri (pt. ai-server)

.PARAMETER Ai        Deployeaza ai-server (build din ./Server).
.PARAMETER Backend   Deployeaza backend (build din ./backend).
.PARAMETER Frontend  Deployeaza frontend (build din ./frontend).
.PARAMETER NoCache   docker build --no-cache (rebuild complet, fara cache).
.PARAMETER SkipApply Sare peste apply -k k8s/ (doar cod, fara sync manifeste).
.PARAMETER Tag       Tag imagine (default latest). Un tag diferit de latest face set-image pe el.

.EXAMPLE
  .\deploy.ps1                      # toate trei
  .\deploy.ps1 -Ai                  # doar ai-server
  .\deploy.ps1 -Backend -Frontend   # backend + frontend
  .\deploy.ps1 -Ai -NoCache         # ai-server, rebuild fara cache
  .\deploy.ps1 -Ai -Tag v3          # build+push :v3 si pune deployment-ul pe :v3
#>
[CmdletBinding()]
param(
    [switch]$Ai,
    [switch]$Backend,
    [switch]$Frontend,
    [switch]$NoCache,
    [switch]$SkipApply,
    [string]$Tag            = "latest",
    [string]$Registry       = "axiiiiiiii",
    [string]$Namespace      = "kubexplain",
    [string]$Kubeconfig     = "$HOME\.kube\licenta-cluster.yaml",
    [string]$RolloutTimeout = "420s"
)

$ErrorActionPreference = "Stop"
# Ruleaza mereu din folderul scriptului (radacina proiectului), ca ./Server, ./backend,
# ./frontend si k8s/ sa se rezolve indiferent de unde dai scriptul.
Set-Location $PSScriptRoot

function Assert-Ok($what) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n[X] ESUAT: $what (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}
function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# catalog servicii: cheie -> context build / deployment / container / repo imagine
$catalog = @{
    ai       = @{ Context = "./Server";   Deploy = "ai-server"; Container = "ai-server"; Repo = "proiect_licenta-ai-server" }
    backend  = @{ Context = "./backend";  Deploy = "backend";   Container = "backend";   Repo = "proiect_licenta-backend"  }
    frontend = @{ Context = "./frontend"; Deploy = "frontend";  Container = "frontend";  Repo = "proiect_licenta-frontend" }
}

# ce deployam: switch-urile date, altfel toate trei
$selected = @()
if ($Ai)       { $selected += "ai" }
if ($Backend)  { $selected += "backend" }
if ($Frontend) { $selected += "frontend" }
if ($selected.Count -eq 0) { $selected = @("ai", "backend", "frontend") }

if (-not (Test-Path $Kubeconfig)) {
    Write-Host "[X] Kubeconfig negasit: $Kubeconfig" -ForegroundColor Red
    exit 1
}

Write-Host "Servicii: $($selected -join ', ')   |   tag: $Tag   |   ns: $Namespace" -ForegroundColor Green

# 1. build + push
foreach ($key in $selected) {
    $svc   = $catalog[$key]
    $image = "$Registry/$($svc.Repo):$Tag"

    Step "docker build  $image"
    if ($NoCache) { docker build --no-cache -t $image $svc.Context }
    else          { docker build           -t $image $svc.Context }
    Assert-Ok "docker build $image"

    Step "docker push   $image"
    docker push $image
    Assert-Ok "docker push $image"
}

# 2. sync manifeste (env LLM_CHAT_NUM_CTX, imagePullPolicy, hostAliases, secret refs)
if (-not $SkipApply) {
    Step "kubectl apply -k k8s/"
    kubectl --kubeconfig $Kubeconfig apply -k k8s/
    Assert-Ok "kubectl apply -k k8s/"
}

# 3. rollout (restart pt. latest = pull proaspat; set-image pt. tag versionat)
foreach ($key in $selected) {
    $svc   = $catalog[$key]
    $image = "$Registry/$($svc.Repo):$Tag"

    if ($Tag -eq "latest") {
        Step "rollout restart  $($svc.Deploy)"
        kubectl --kubeconfig $Kubeconfig -n $Namespace rollout restart "deploy/$($svc.Deploy)"
        Assert-Ok "rollout restart $($svc.Deploy)"
    }
    else {
        Step "set image  $($svc.Deploy) = $image"
        kubectl --kubeconfig $Kubeconfig -n $Namespace set image "deploy/$($svc.Deploy)" "$($svc.Container)=$image"
        Assert-Ok "set image $($svc.Deploy)"
    }

    Step "rollout status  $($svc.Deploy)"
    kubectl --kubeconfig $Kubeconfig -n $Namespace rollout status "deploy/$($svc.Deploy)" --timeout=$RolloutTimeout
    Assert-Ok "rollout status $($svc.Deploy)"
}

Write-Host "`nGata. Deployat: $($selected -join ', ')" -ForegroundColor Green
