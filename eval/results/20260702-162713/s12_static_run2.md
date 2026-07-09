# s12 | mode=static | run=2 | 16.2s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** is not scaling (ordered by probability)

### 1. Metrics API unavailable or not providing data  
**Why it matters** – The HPA relies on the Kubernetes metrics API (usually served by *metrics‑server* or a custom‑metrics adapter). If the API cannot be reached or returns no data, the HPA will stay at its current replica count and report a “Unable to get metrics” condition.

**How to verify**  
```bash
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml
kubectl describe hpa s12-hpa -n kubexplain-eval
kubectl get --raw "/apis/metrics.k8s.io/v1beta1/namespaces/kubexplain-eval/pods" | jq .
kubectl get pods -n kube-system -l k8s-app=metrics-server
kubectl logs -n kube-system <metrics-server-pod>
```
* Look for the `conditions` field in the HPA output – you’ll see something like `Unable to get metrics` or `Metrics not available`.  
* If the raw metrics request returns an empty list or a 403/404, the metrics server is not functioning.

**Fix**  
* Ensure the **metrics‑server** deployment is running and healthy.  
* If it’s missing, install it (e.g., `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`).  
* Check that the APIService `v1beta1.metrics.k8s.io` is `Available`.  
* Verify that the API server has the `--requestheader-client-ca-file` and `--proxy-client-cert-file` flags needed for the metrics server (common in RBAC‑restricted clusters).  

---

### 2. Pods lack **resource requests** for the metric the HPA watches  
**Why it matters** – The HPA can only compute utilization percentages when the target pods declare **cpu** (or memory) *requests*. If the pods have no request, the HPA treats the metric as undefined and will not scale.

**How to verify**  
```bash
kubectl get deployment <deployment‑name> -n kubexplain-eval -o yaml | grep -A5 resources
kubectl describe pod <any‑pod‑of‑the‑deployment> -n kubexplain-eval | grep -A3 Resources
```
* If `resources.requests.cpu` (or memory) is missing, the HPA cannot calculate utilization.

**Fix**  
* Add appropriate `resources.requests` (and optionally `limits`) to the pod spec, then roll out a new replica set.  
```yaml
resources:
  requests:
    cpu: "200m"
    memory: "128Mi"
```
* After the new pods appear, the HPA will start receiving utilization data.

---

### 3. HPA target **utilization/value is unreachable** (e.g., set too high)  
**Why it matters** – If the target CPU (or custom metric) percentage is higher than the cluster can ever reach, the HPA will never trigger a scale‑up.

**How to verify**  
```bash
kubectl get hpa s12-hpa -n kubexplain-eval -o jsonpath='{.spec.targetCPUUtilizationPercentage}'
kubectl top pods -n kubexplain-eval
```
* Compare the current average CPU usage with the target. If the current usage is far below the target, scaling will not happen.

**Fix**  
* Lower the target to a realistic value (e.g., 50‑70 % for CPU).  
```bash
kubectl edit hpa s12-hpa -n kubexplain-eval
# adjust spec.targetCPUUtilizationPercentage
```
* Re‑apply the HPA and monitor.

---

### 4. **minReplicas == maxReplicas** (no scaling range)  
**Why it matters** – If the HPA is configured with the same value for `minReplicas` and `maxReplicas`, the controller has no room to change the replica count, even if metrics demand it.

**How to verify**  
```bash
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E 'minReplicas|maxReplicas'
```
* Identical numbers indicate a static replica count.

**Fix**  
* Increase `maxReplicas` (or lower `minReplicas`) to give the HPA a range.  
```bash
kubectl edit hpa s12-hpa -n kubexplain-eval
# set, for example, minReplicas: 2, maxReplicas: 10
```

---

### 5. **Scale‑up/down cooldown / stabilization windows** prevent immediate scaling  
**Why it matters** – The HPA respects `behavior.scaleUp.stabilizationWindowSeconds` and `behavior.scaleDown.stabilizationWindowSeconds`. If recent scaling activity occurred, the HPA may be waiting for the window to expire before acting again.

**How to verify**  
```bash
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A5 behavior
kubectl describe hpa s12-hpa -n kubexplain-eval | grep -i stabilization
```
* Look for `stabilizationWindowSeconds` values and recent `ScaleUp`/`ScaleDown` events.

**Fix**  
* Adjust the stabilization windows to shorter periods if rapid scaling is desired.  
```bash
kubectl edit hpa s12-hpa -n kubexplain-eval
# modify .spec.behavior.scaleUp.stabilizationWindowSeconds, etc.
```
* Remember that too‑short windows can cause thrashing.

---

### 6. **Custom metric** referenced by the HPA is missing or mis‑named  
**Why it matters** – If the HPA uses a custom metric (e.g., via Prometheus Adapter) and that metric does not exist or the name is wrong, the HPA will stay idle and report a condition like `FailedGetObjectMetric`.

**How to verify**  
```bash
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A3 external
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/kubexplain-eval/pods/*/my_metric" | jq .
```
* Errors or empty results indicate a problem with the metric source.

**Fix**  
* Ensure the custom‑metrics adapter is deployed and the metric name matches exactly.  
* Verify the metric exists in the backing system (e.g., Prometheus query).  
* Update the HPA spec to the correct metric name or adjust the adapter configuration.

---

### 7. **Cluster‑autoscaler** cannot provision new nodes, causing pending pods  
**Why it matters** – The HPA may successfully request more replicas, but if the cluster cannot schedule them (no free node capacity), you’ll see pods stuck in `Pending`. From the HPA’s perspective it *did* scale, but you perceive no effect.

**How to verify**  
```bash
kubectl get pods -n kubexplain-eval -o wide | grep Pending
kubectl describe pod <pending‑pod> -n kubexplain-eval | grep -i events
kubectl get nodes
kubectl get cs
```
* Look for events like `FailedScheduling` or `Insufficient cpu`.

**Fix**  
* Check that the **cluster‑autoscaler** is enabled and has appropriate node‑group limits.  
* Increase node pool size manually or adjust autoscaler parameters.  
* If you cannot add nodes, consider lowering the HPA target or increasing pod resource requests to fit existing capacity.

---

### 8. **HPA controller manager** is not running or is mis‑configured  
**Why it matters** – The HPA controller is part of the kube‑controller‑manager. If that component is down, no HPA objects will be reconciled.

**How to verify**  
```bash
kubectl get pods -n kube-system -l component=kube-controller-manager
kubectl logs -n kube-system <controller‑manager‑pod> | grep HorizontalPodAutoscaler
```
* Absence of the controller manager or error logs indicate a problem.

**Fix**  
* Restart the controller‑manager (or the control‑plane node).  
* Review its startup flags; ensure `--horizontal-pod-autoscaler-use-rest-clients=true` (for newer versions) is set if required.

---

## Quick verification checklist (run when you regain access)

1. `kubectl describe hpa s12-hpa -n kubexplain-eval` – look for **Conditions** and recent **Events**.  
2. `kubectl get --raw "/apis/metrics.k8s.io/v1beta1/namespaces/kubexplain-eval/pods"` – confirm metrics are returned.  
3. Verify the target deployment’s pods have **cpu/memory requests**.  
4. Confirm `minReplicas` ≠ `maxReplicas`.  
5. Check any **behavior** (stabilization) settings.  
6. If using custom metrics, query the custom‑metrics API and validate the metric exists.  
7. Ensure the **cluster‑autoscaler** (or node capacity) can accommodate extra pods.  
8. Confirm the **controller manager** is healthy.

Address the first failing check you encounter; in most clusters the root cause is **missing or broken metrics** (cause #1). Fixing that usually restores HPA functionality.
