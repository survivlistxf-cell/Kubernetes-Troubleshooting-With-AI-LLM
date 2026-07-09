# s12 | mode=dynamic | run=3 | 14.5s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **`s12-hpa`** isn’t scaling (ordered by how often they cause a silent failure)

| # | Likely cause | Why it stops scaling | How to verify (when you can) | How to fix |
|---|--------------|----------------------|------------------------------|------------|
| 1 | **Metrics‑server (or other metrics API) is missing or unhealthy** | The HPA can only compute utilization if it can read metrics from `metrics.k8s.io`. If the API is unavailable the HPA reports *“Unable to get metrics”* and stays at the current replica count. | ```bash\nkubectl get apiservice v1beta1.metrics.k8s.io -o yaml\nkubectl get pods -n kube-system -l k8s-app=metrics-server\nkubectl logs -n kube-system <metrics‑server‑pod>\n``` Look for `Available: False` or error logs. | • Deploy or reinstall the metrics‑server (e.g. `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`). <br>• Ensure the API aggregation layer is enabled on the API server (`--requestheader‑allowed‑names`, `--requestheader‑client‑ca‑file`, etc.). |
| 2 | **Target workload has no resource **requests** for the metric the HPA watches** (e.g. CPU request missing) | HPA calculates utilization as *current usage / requested amount*. If the request is `0` or not set, the calculation fails and the HPA stays idle. | ```bash\nkubectl get deployment <target‑name> -n kubexplain-eval -o yaml | grep -A5 resources:\n``` Verify that every container in the target has `resources.requests.cpu` (and/or memory) defined. | Add appropriate requests (and optionally limits) to the pod spec, then apply the updated manifest. |
| 3 | **HPA’s `scaleTargetRef` points to a non‑existent or wrong object** | The controller can’t patch the replica count of a missing/incorrect resource, so no scaling occurs. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep scaleTargetRef\nkubectl get <kind> <name> -n kubexplain-eval\n``` | Correct the `scaleTargetRef` (kind/name) in the HPA definition or create the missing workload. |
| 4 | **HPA is disabled by its own status (`ScalingActive: False`)** | When the HPA cannot obtain metrics or the target replica count is set to `0` while `minReplicas > 0`, the controller sets `ScalingActive=False` and stops reconciling. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep -A3 Conditions\n``` | Resolve the underlying condition (usually metrics‑server or missing requests). If you intentionally want to pause scaling, set `minReplicas: 0` and later restore it. |
| 5 | **Manual changes to `.spec.replicas` while an HPA is attached** | The HPA continuously reconciles the replica count. If a user manually patches the Deployment’s `replicas` to a value outside the HPA’s `[min,max]` range, the HPA will keep overriding it, which can look like “no scaling”. | ```bash\nkubectl get deployment <target‑name> -n kubexplain-eval -o jsonpath='{.spec.replicas}'\n``` | Stop manual edits; let the HPA own the replica count. If you need a temporary override, set `spec.replicas` **and** adjust `minReplicas`/`maxReplicas` accordingly, or delete the HPA temporarily. |
| 6 | **`minReplicas` / `maxReplicas` limits are too tight** | If the current replica count is already at `maxReplicas`, the HPA cannot scale up; if it is at `minReplicas`, it cannot scale down. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E 'minReplicas|maxReplicas'\n``` | Increase `maxReplicas` (or lower `minReplicas`) to give the HPA room to act. |
| 7 | **Behavior policies explicitly disable scaling** (`selectPolicy: Disabled` for up or down) | The HPA respects the `behavior` field. If `scaleUp.selectPolicy` or `scaleDown.selectPolicy` is set to `Disabled`, the corresponding direction will never happen. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A5 behavior\n``` | Remove or adjust the `behavior` block so that scaling is allowed. |
| 8 | **Custom / external metrics API not available** (when the HPA uses a custom metric) | The HPA will report “missing metrics” and stay at the current replica count. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep -i 'metric'\n``` Look for messages like *“unable to fetch custom metric”*. | Deploy the appropriate custom‑metrics adapter, or fall back to a built‑in resource metric (CPU/Memory). |
| 9 | **Pods are not Ready / are being terminated** | The HPA ignores pods that have a deletion timestamp or are not Ready when computing averages. If all pods are in a non‑Ready state, the utilization appears as `0%` and the HPA does nothing. | ```bash\nkubectl get pods -l <selector-of-target> -n kubexplain-eval\n``` | Fix the underlying pod health issue (image pull errors, readiness probes, etc.) so that at least one pod is Ready. |
| 10 | **API aggregation layer disabled** (rare on managed clusters) | Without aggregation the `metrics.k8s.io`, `custom.metrics.k8s.io`, and `external.metrics.k8s.io` APIs cannot be served, causing the same effect as a missing metrics‑server. | ```bash\nkubectl get apiservice | grep -E 'metrics|custom|external'\n``` Look for `Available: False`. | Enable the aggregation layer on the API server (`--requestheader‑allowed‑names`, `--requestheader‑client‑ca‑file`, etc.) or use a cluster that provides it. |

---

### Quick verification checklist (run when you regain access)

1. **Check HPA status & conditions**  
   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```
   - Look for `ScalingActive`, `AbleToScale`, `ScalingLimited` conditions.

2. **Confirm metrics source**  
   ```bash
   kubectl get --raw "/apis/metrics.k8s.io/v1beta1/nodes"   # should return JSON, not 404
   ```

3. **Validate target workload**  
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep scaleTargetRef
   kubectl get deployment <target> -n kubexplain-eval -o yaml | grep -A5 resources:
   ```

4. **Inspect behavior block (if any)**  
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A10 behavior
   ```

5. **Look at pod readiness**  
   ```bash
   kubectl get pods -l <target‑selector> -n kubexplain-eval
   ```

6. **Check min/max limits**  
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}'
   ```

7. **If using custom/external metrics** – verify the adapter is running and its API is `Available`.

---

### Typical “one‑line” fix for the most common case (missing metrics‑server)

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

After the metrics‑server becomes healthy, the HPA should start reconciling again (you’ll see the `ScalingActive` condition turn `True`).

---

**Bottom line:**  
Start by confirming that the HPA can actually read the metric it cares about (metrics‑server or custom adapter). If the metric pipeline is healthy, then move down the list—resource requests, target reference, behavior policies, and replica limits are the next most frequent culprits. Fix the first issue you encounter, re‑check the HPA’s conditions, and the scaling should resume.
