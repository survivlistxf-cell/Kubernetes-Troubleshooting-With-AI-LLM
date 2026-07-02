# ============================================================================
# setup-readonly-kubeconfig.ps1
# Genereaza un kubeconfig READ-ONLY pentru Kubexplain, pe baza clusterului tau.
#
# Rezultat: un fisier nou ~/.kube/licenta-cluster-readonly.yaml care se
# autentifica drept ServiceAccount-ul "kubexplain-readonly" (doar citire).
#
# Conditii:
#   - kubectl instalat, cu contextul curent setat pe clusterul tinta (cu drepturi
#     de admin, ca sa poata aplica RBAC).
#   - Kubernetes 1.24+ (pentru `kubectl create token`).
#
# Rulare (din folderul k8s):
#   powershell -ExecutionPolicy Bypass -File .\setup-readonly-kubeconfig.ps1
# ============================================================================

$ErrorActionPreference = "Stop"
$SA   = "kubexplain-readonly"
$NS   = "kube-system"
$Out  = Join-Path $env:USERPROFILE ".kube\licenta-cluster-readonly.yaml"

Write-Host "1/4  Aplic RBAC read-only pe cluster..." -ForegroundColor Cyan
kubectl apply -f (Join-Path $PSScriptRoot "rbac-readonly.yaml")

Write-Host "2/4  Generez token pentru ServiceAccount-ul read-only..." -ForegroundColor Cyan
# durata 1 an; daca clusterul refuza durata, scoate parametrul --duration
$token = kubectl create token $SA -n $NS --duration=8760h
if ([string]::IsNullOrWhiteSpace($token)) { throw "Nu am putut genera token-ul." }

Write-Host "3/4  Citesc adresa si CA-ul clusterului din contextul curent..." -ForegroundColor Cyan
$server = kubectl config view --minify --raw -o jsonpath="{.clusters[0].cluster.server}"
$ca     = kubectl config view --minify --raw -o jsonpath="{.clusters[0].cluster.certificate-authority-data}"
if ([string]::IsNullOrWhiteSpace($server)) { throw "Nu am putut citi adresa serverului." }

Write-Host "4/4  Scriu kubeconfig-ul read-only la: $Out" -ForegroundColor Cyan
$caLine = if ([string]::IsNullOrWhiteSpace($ca)) { "    insecure-skip-tls-verify: true" } else { "    certificate-authority-data: $ca" }

$kubeconfig = @"
apiVersion: v1
kind: Config
clusters:
  - name: licenta
    cluster:
      server: $server
$caLine
users:
  - name: $SA
    user:
      token: $token
contexts:
  - name: licenta-readonly
    context:
      cluster: licenta
      user: $SA
current-context: licenta-readonly
"@

$kubeconfig | Out-File -Encoding ascii -FilePath $Out
Write-Host ""
Write-Host "GATA. Verifica ca e read-only:" -ForegroundColor Green
Write-Host "  kubectl --kubeconfig `"$Out`" auth can-i create pods -A   (asteptat: no)"
Write-Host "  kubectl --kubeconfig `"$Out`" get pods -A                  (asteptat: lista)"
Write-Host ""
Write-Host "Apoi in Kubexplain adauga clusterul folosind acest fisier:" -ForegroundColor Green
Write-Host "  $Out"
