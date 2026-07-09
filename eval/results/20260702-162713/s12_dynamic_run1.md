# s12 | mode=dynamic | run=1 | 128.8s | needs_search=1

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** is not scaling (ordered by probability)

### 1. Metrics Server (or other metrics API) is missing or unhealthy  
**Why it matters** – The HPA reads CPU/Memory (or custom) metrics from the `metrics.k8s.io` API, which is normally provided by the Metrics Server. If that API is unavailable the HPA cannot compute utilization and will stay at its current replica count.  

**How to verify**  
- Look at the HPA status (`kubectl describe hpa s12-hpa -n kubexplain-eval`).  
  - The **Conditions** section will show `ScalingActive=False` with a reason such as *MetricsUnavailable* or *UnableToGetMetrics*.  
- Check the `metrics.k8s.io` API endpoint: `kubectl get --raw "/apis/metrics.k8s.io/v1beta1/nodes"` (or pods). Errors or empty output indicate the server is not serving.  
- Review the Metrics Server pod logs and its `Ready` condition; any crash‑loop or pending state points to a problem.  

**Fix**  
- Deploy or restart the Metrics Server (e.g., `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`).  
- Ensure the API aggregation layer is enabled on the API server.  
- Verify that the Metrics Server has the necessary RBAC permissions and that the cluster network allows it to reach the kubelets.  

---

### 2. Target Pods lack resource **requests** for the metric the HPA uses  
**Why it matters** – The HPA calculates utilization as *current usage / requested amount*. If a container does not declare a CPU (or memory) request, the utilization metric is undefined and the HPA will ignore that metric, effectively disabling scaling.  

**How to verify**  
- Inspect the Deployment (or StatefulSet) that the HPA references (`kubectl get deployment <name> -n kubexplain-eval -o yaml`).  
- Look for `resources.requests.cpu` (or `memory`) under each container. Missing entries mean the HPA cannot compute utilization.  

**Fix**  
- Add appropriate `resources.requests` (and optionally `limits`) to the container spec.  
- Re‑apply the updated manifest.  
- After the pods restart, the HPA will be able to compute utilization and act.  

---

### 3. HPA **minReplicas / maxReplicas** configuration prevents scaling  
**Why it matters** – If `minReplicas` equals `maxReplicas`, the HPA is locked to a single replica count. Likewise, a `maxReplicas` of 0 or a `minReplicas` higher than the current load can stop scaling.  

**How to verify**  
- In the HPA description, check the `Min Replicas` and `Max Replicas` fields.  
- Confirm that the current replica count lies between those bounds.  

**Fix**  
- Adjust the HPA spec to give a reasonable range (e.g., `minReplicas: 1`, `maxReplicas: 10`).  
- Apply the corrected HPA manifest.  

---

### 4. HPA **ScalingActive** condition is false because the target workload is set to zero (maintenance mode)  
**Why it matters** – When the target Deployment’s `.spec.replicas` is manually set to 0 while the HPA’s `minReplicas` is > 0, the HPA disables itself (`ScalingActive=False`). It will stay inactive until the workload is re‑enabled.  

**How to verify**  
- In the HPA description, look for a condition with `Reason: MaintenanceMode` or a message indicating the target replica count is 0.  
- Check the target Deployment’s replica count (`kubectl get deployment <name> -n kubexplain-eval -o jsonpath='{.spec.replicas}'`).  

**Fix**  
- Scale the target Deployment back up (`kubectl scale deployment <name> --replicas=1`).  
- The HPA will automatically become active again.  

---

### 5. Pods are **not Ready** or are being ignored due to readiness‑delay settings  
**Why it matters** – The HPA ignores metrics from Pods that are not Ready, or that have become Ready only within the `--horizontal-pod-autoscaler-initial-readiness-delay` window (default 30 s). If most Pods are still in this window, the HPA may see a low effective replica count and decide no scaling is needed.  

**How to verify**  
- Examine the HPA’s `status.currentReplicas` vs. `status.desiredReplicas`. A large discrepancy may indicate many Pods are being ignored.  
- Look at the target Deployment’s pod list and check the `READY` column; many `0/1` or `Init` states point to readiness issues.  

**Fix**  
- Adjust the Deployment’s readinessProbe to succeed only after the application has warmed up, or increase the `initialReadinessDelay` flag on the controller manager if you have cluster‑wide control.  
- Ensure the application starts quickly enough to become Ready before the HPA’s delay expires.  

---

### 6. **RBAC / API aggregation** prevents the HPA controller from reading metrics  
**Why it matters** – The HPA controller runs with a service account that must be allowed to read the `metrics.k8s.io` API. Missing ClusterRole/ClusterRoleBinding will cause “Forbidden” errors and the HPA will stay idle.  

**How to verify**  
- In the HPA events (`kubectl describe hpa s12-hpa`), look for messages like `Failed to get metrics: pods.metrics.k8s.io "cpu" is forbidden`.  
- Check the HPA controller’s service account permissions (`kubectl get clusterrolebinding <hpa‑controller‑binding>`).  

**Fix**  
- Grant the required `system:metrics-reader` ClusterRole to the HPA controller’s service account.  
- Reconcile any custom RBAC policies that might be blocking access.  

---

### 7. **Custom or external metrics** referenced by the HPA are unavailable  
**Why it matters** – If the HPA is configured to use a custom metric (e.g., `type: External` or `type: Pods`) that the metrics adapter does not expose, the HPA cannot compute a desired replica count.  

**How to verify**  
- In the HPA spec, identify any `metrics` entries that are not `type: Resource`.  
- Check the corresponding custom‑metrics API (`kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1"`).  
- Look for events indicating “Unable to fetch metric …”.  

**Fix**  
- Deploy or fix the custom metrics adapter that provides the metric.  
- Correct the metric name, selector, or target value in the HPA spec.  

---

### 8. HPA controller **tuning parameters** (tolerance, sync period) suppress scaling  
**Why it matters** – The controller’s `--horizontal-pod-autoscaler-tolerance` (default 10 %) and `--horizontal-pod-autoscaler-sync-period` (default 15 s) can cause small metric fluctuations to be ignored, or delay reactions enough that you think scaling never happens.  

**How to verify**  
- Review the controller‑manager arguments (`kubectl get pod -n kube-system -l component=kube-controller-manager -o yaml`).  
- Check if a custom tolerance or sync period has been set to a high value.  

**Fix**  
- Adjust the tolerance to a lower percentage if you need more sensitive scaling.  
- Reduce the sync period if you want the HPA to react faster.  

---

## Quick verification checklist (no commands needed)

1. **HPA status & conditions** – look for `ScalingActive=False` or error reasons.  
2. **Metrics API health** – confirm the `metrics.k8s.io` endpoint returns data.  
3. **Target workload spec** – ensure containers declare CPU/Memory requests.  
4. **Replica bounds** – verify `minReplicas` < `maxReplicas` and that they allow scaling.  
5. **Target replica count** – make sure the workload isn’t manually set to 0.  
6. **Pod readiness** – check that most pods are Ready and not stuck in init/startup.  
7. **RBAC / API aggregation** – ensure the HPA controller can read metrics APIs.  
8. **Custom/external metrics** – confirm any non‑resource metrics exist and are exposed.  
9. **Controller tuning** – review tolerance and sync‑period settings if scaling feels “stuck”.  

Address the first failing item on the list; in most clusters the root cause is a missing or unhealthy Metrics Server, followed by missing resource requests on the target pods. Fixing those will usually restore normal HPA behavior.
