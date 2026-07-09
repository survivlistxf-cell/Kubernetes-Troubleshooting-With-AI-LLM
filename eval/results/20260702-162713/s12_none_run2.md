# s12 | mode=none | run=2 | 17.5s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** is not scaling (ordered by probability)

Below is a short‑list of the problems that most often keep an HPA from acting, together with **how to verify** each one (when you regain access) and **what to do to fix it**.

---

### 1️⃣ Metrics API unavailable or mis‑configured  
**Why it matters** – The HPA can only make scaling decisions when it can read the metric (CPU, memory, custom) for the target pods. If the metrics server (or a custom‑metrics provider) is down, the HPA will stay at its current replica count and report a *“unable to get metrics”* condition.

**How to verify**  

```bash
# 1. Check the HPA status for the “metrics” condition
kubectl -n kubexplain-eval describe hpa s12-hpa

# 2. Verify the metrics‑server (or custom‑metrics) pods are running
kubectl -n kube-system get pods -l k8s-app=metrics-server

# 3. Try to fetch a metric directly
kubectl top pods -n kubexplain-eval
```

If `kubectl top` fails with *“metrics API not available”* or the HPA description shows a condition like `Unable to get metrics` → the metrics pipeline is broken.

**Fix**  

| Symptom | Remedy |
|---------|--------|
| Metrics‑server pod not running / CrashLoopBackOff | `kubectl -n kube-system delete pod <metrics‑server-pod>` to force a restart, then inspect its logs (`kubectl logs …`). Upgrade or reinstall the metrics‑server chart if the image is outdated. |
| APIService `v1beta1.metrics.k8s.io` not **Available** | `kubectl get apiservice v1beta1.metrics.k8s.io -o yaml` – look for `status.conditions` with `False`. Re‑apply the metrics‑server manifests or fix the TLS secret it uses. |
| Custom‑metrics provider missing | Verify the `ExternalMetrics` or `CustomMetrics` APIService is present (`kubectl get apiservice | grep custom`). Deploy or restart the provider (e.g., Prometheus Adapter). |

---

### 2️⃣ Target utilization never reached (or is set too low)  
**Why it matters** – If the observed CPU/memory usage stays below the HPA’s `targetCPUUtilizationPercentage` (or other metric), the controller correctly decides that no scaling is needed.

**How to verify**  

```bash
# Show current usage vs. target
kubectl -n kubexplain-eval get hpa s12-hpa -o yaml | grep -A4 "metrics"
```

The output contains fields like `currentCPUUtilizationPercentage` and `targetCPUUtilizationPercentage`. Compare the two numbers.

**Fix**  

- **Raise the load** on the pods (e.g., run a load‑test) to confirm the HPA reacts.
- **Adjust the target** to a more realistic value: edit the HPA (`kubectl edit hpa s12-hpa`) and change `targetCPUUtilizationPercentage` (or the custom metric) to a higher number.
- If you’re using **custom metrics**, ensure the metric’s value actually reflects load (check the provider’s query).

---

### 3️⃣ Replica limits (minReplicas == maxReplicas or max already reached)  
**Why it matters** – The HPA can only scale within the range `[minReplicas, maxReplicas]`. If those two numbers are equal, or the current replica count already equals `maxReplicas`, no scaling will happen.

**How to verify**  

```bash
kubectl -n kubexplain-eval get hpa s12-hpa -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}'
kubectl -n kubexplain-eval get deployment <target‑deployment> -o jsonpath='{.spec.replicas}'
```

**Fix**  

- Increase `maxReplicas` (or lower `minReplicas`) in the HPA spec.
- Ensure the target deployment’s replica count is not being manually forced to a fixed number (e.g., via `kubectl scale` without the HPA).

---

### 4️⃣ Target Deployment missing **resource requests** for the metric  
**Why it matters** – For CPU/Memory‑based autoscaling the HPA computes utilization as *current usage / requested amount*. If the pods have **no `resources.requests.cpu`** defined, the HPA cannot calculate a percentage and will stay idle.

**How to verify**  

```bash
kubectl -n kubexplain-eval get deployment <target‑deployment> -o yaml | grep -A5 "resources:"
```

Look for `requests:` under the container spec. If it’s absent or set to `0`, the HPA will be ineffective.

**Fix**  

- Add sensible CPU (and optionally memory) requests to the pod template, then roll out the change:

```yaml
resources:
  requests:
    cpu: "200m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

- Apply the updated deployment (`kubectl apply -f …`). The HPA will start using the new requests on the next reconciliation loop.

---

### 5️⃣ `scaleTargetRef` points to a non‑existent or wrong object  
**Why it matters** – The HPA must know which controller (Deployment, ReplicaSet, StatefulSet, etc.) to scale. If the `kind`/`name` is misspelled or the object was deleted, the HPA will report a *“cannot find the scale target”* condition and do nothing.

**How to verify**  

```bash
kubectl -n kubexplain-eval get hpa s12-hpa -o yaml | grep scaleTargetRef -A2
kubectl -n kubexplain-eval get deployment <name-from-above>
```

If the `kubectl get` command for the referenced object returns *NotFound*, the reference is broken.

**Fix**  

- Edit the HPA to point to the correct controller (`kubectl edit hpa s12-hpa`) or recreate the missing target object.
- If you renamed the deployment, update the HPA accordingly.

---

### 6️⃣ Cluster‑level constraints (ResourceQuota, LimitRange, PodSecurityPolicy) block scaling  
**Why it matters** – Even when the HPA decides to increase replicas, the API server may reject the update because a `ResourceQuota` in the namespace is already exhausted (e.g., `count/pods` or `cpu` limits).

**How to verify**  

```bash
kubectl -n kubexplain-eval describe quota
kubectl -n kubexplain-eval get quota -o yaml
```

Look for `used` vs. `hard` values that are already at the limit.

**Fix**  

- Increase the quota (`kubectl edit quota <quota-name>`) or delete unused resources.
- If you cannot change the quota, lower the HPA’s `maxReplicas` to stay within the allowed limits.

---

### 7️⃣ HPA controller manager not running or mis‑configured  
**Why it matters** – The HPA objects are reconciled by the `horizontal-pod-autoscaler` controller in the kube‑controller‑manager. If that component is down, no HPA will ever act.

**How to verify**  

```bash
kubectl get componentstatuses
# or, on a control‑plane node:
kubectl -n kube-system get pods -l component=kube-controller-manager
```

If the controller‑manager pod is `CrashLoopBackOff` or missing, the HPA logic is dead.

**Fix**  

- Restart the controller‑manager (depends on your installation – e.g., `systemctl restart kube-controller-manager` on static‑pod setups, or redeploy the control‑plane pod in a managed service).
- Check its logs for errors (`kubectl logs -n kube-system <controller‑manager-pod>`). Common issues are missing RBAC permissions for the `autoscaling` API group.

---

### 8️⃣ Scaling throttling / back‑off (recent rapid changes)  
**Why it matters** – The HPA respects a built‑in back‑off to avoid flapping. If many scaling events happened in the last few minutes, the controller will pause further changes.

**How to verify**  

```bash
kubectl -n kubexplain-eval describe hpa s12-hpa | grep -i "last scale"
```

The description shows the timestamp of the last scaling operation. If it’s very recent and you’re already at the desired load, the HPA may be waiting.

**Fix**  

- Wait for the back‑off window (default ~5 minutes) and then re‑trigger load.
- If you need faster reaction, adjust the HPA flags on the controller (`--horizontal-pod-autoscaler-downscale-delay`, `--horizontal-pod-autoscaler-upscale-delay`) – requires control‑plane changes.

---

## Quick checklist you can run once you have cluster access

1. `kubectl -n kubexplain-eval describe hpa s12-hpa` – look for **Conditions** (MetricsAvailable, AbleToScale, ScalingActive).  
2. Verify the **metrics pipeline** (`kubectl top pods`, metrics‑server pods).  
3. Confirm **resource requests** exist in the target deployment’s pod spec.  
4. Check **min/max replica** values and current replica count.  
5. Ensure the **scaleTargetRef** points to a live controller.  
6. Look for **ResourceQuota** limits in the namespace.  
7. Make sure the **controller‑manager** is healthy.  
8. Observe the **lastScaleTime** to rule out throttling.

Address the first failing item on the list; in most clusters the root cause is the **missing or broken metrics server** (cause #1). Fix that, then re‑evaluate the remaining items. Once the metrics flow is restored, the HPA will begin scaling according to the target utilization you have defined.
