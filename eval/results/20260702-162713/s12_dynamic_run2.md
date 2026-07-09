# s12 | mode=dynamic | run=2 | 15.8s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** isn’t scaling (ordered by how often they appear in real clusters)

| # | Likely cause | Why it stops scaling | How to verify (when you can reach the cluster) | How to fix |
|---|--------------|----------------------|-----------------------------------------------|------------|
| 1 | **Metrics‑server (or any metrics API) is missing or unhealthy** | The HPA needs the `metrics.k8s.io` API to read CPU/Memory usage. If the API is unavailable the controller reports “unable to get metrics” and never changes replica count. | ```bash\nkubectl get pods -n kube-system -l k8s-app=metrics-server\nkubectl describe apiservice v1beta1.metrics.k8s.io\nkubectl get --raw /apis/metrics.k8s.io/v1beta1/nodes\n``` | • Deploy or repair the metrics‑server (e.g. `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`). <br>• Ensure the API aggregation layer is enabled and the service can reach the kube‑let on each node. |
| 2 | **Target Pods have no resource **requests** for the metric the HPA watches** (e.g. no `cpu` request when the HPA uses `averageUtilization`) | HPA calculates utilization as *current usage / requested amount*. With no request the controller cannot compute a percentage and will stay idle. | ```bash\nkubectl get deployment <target‑name> -n kubexplain-eval -o yaml | grep -A5 resources\n``` | Add appropriate `resources.requests` (and optionally `limits`) to the containers in the workload spec, then re‑apply the manifest. |
| 3 | **Min/Max replica settings prevent any change** | If `spec.minReplicas == spec.maxReplicas` (or `maxReplicas` is set to the current replica count) the HPA has no room to scale. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E 'minReplicas|maxReplicas|replicas'\n``` | Adjust the HPA spec so `maxReplicas` is higher than the current replica count and `minReplicas` is lower than the desired upper bound. |
| 4 | **Wrong `scaleTargetRef` (wrong kind/name/namespace)** | The HPA updates the *scale* sub‑resource of the object referenced. If the reference points to a non‑existent or wrong resource, the controller logs “cannot find target” and does nothing. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep scaleTargetRef -A2\n``` | Correct the `scaleTargetRef` to point to the actual Deployment/StatefulSet/ReplicaSet you want to scale, then `kubectl apply -f` the updated HPA. |
| 5 | **HPA is implicitly deactivated (desired replicas = 0 while minReplicas > 0)** | When the target’s `.spec.replicas` is set to 0, the HPA stops reconciling until you manually change the replica count or lower `minReplicas`. | ```bash\nkubectl get deployment <target‑name> -n kubexplain-eval -o jsonpath='{.spec.replicas}'\nkubectl get hpa s12-hpa -n kubexplain-eval -o jsonpath='{.status.conditions[?(@.type==\"ScalingActive\")].status}'\n``` | Set the target workload’s replica count back to a non‑zero value (`kubectl scale deployment <target‑name> --replicas=1`) **or** lower `minReplicas` to 0. |
| 6 | **Behavior policies block scaling** (e.g., `scaleUp` rate limit set to 0, `scaleDown.selectPolicy: Disabled`) | Even if metrics exceed the threshold, the HPA obeys the `behavior` section. A mis‑configured policy can effectively freeze scaling. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A5 behavior\n``` | Edit the HPA to remove or adjust the restrictive policy (e.g., set a positive `percent` or `pods` value, or delete the `behavior` block). |
| 7 | **Observed metric never reaches the target** (e.g., CPU utilization stays below the configured `averageUtilization`) | The HPA only acts when the measured value crosses the threshold. If your load is too low, nothing happens. | ```bash\nkubectl top pods -n kubexplain-eval\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep -A3 'Metrics'\n``` | Generate load against the service (e.g., `kubectl run -i --rm load-generator --image=busybox -- /bin/sh -c "while true; do wget -q -O- http://<service>; done"`), or lower the target utilization/value in the HPA spec. |
| 8 | **Custom or external metrics API missing** (when the HPA uses a custom metric) | Without the `custom.metrics.k8s.io` or `external.metrics.k8s.io` API the controller cannot fetch the metric and will stay idle. | ```bash\nkubectl get --raw /apis/custom.metrics.k8s.io/v1beta1\nkubectl get --raw /apis/external.metrics.k8s.io/v1beta1\n``` | Deploy the appropriate metrics adapter (e.g., Prometheus Adapter) and verify the API is registered. |
| 9 | **Pods are not Ready or missing metrics** (e.g., crash‑looping pods, init containers still running) | The HPA ignores pods that are not Ready or have no recent metric data, which can keep the calculated average low enough to prevent scaling. | ```bash\nkubectl get pods -n kubexplain-eval\nkubectl describe pod <pod‑name> -n kubexplain-eval | grep -i 'Ready'\n``` | Fix the underlying pod health issue (restart crashes, adjust readiness probes) so the pods become Ready and report metrics. |
|10| **Another controller is overwriting the replica count** (e.g., a GitOps operator, a manual `kubectl scale` loop) | The HPA may set a new replica count, but a faster controller immediately reverts it, making it appear that the HPA never acted. | ```bash\nkubectl get events -n kubexplain-eval --field-selector involvedObject.kind=Deployment,involvedObject.name=<target‑name>\n``` | Identify the conflicting controller (ArgoCD, Flux, custom script) and adjust its configuration to respect the HPA, or disable it for that workload. |

---

### Quick checklist you can run once you regain access

```bash
# 1. Basic health of the HPA
kubectl get hpa s12-hpa -n kubexplain-eval
kubectl describe hpa s12-hpa -n kubexplain-eval

# 2. Verify metrics‑server
kubectl get pods -n kube-system -l k8s-app=metrics-server
kubectl get --raw /apis/metrics.k8s.io/v1beta1/nodes

# 3. Check that the target workload has resource requests
kubectl get deployment <target-name> -n kubexplain-eval -o yaml | grep -A5 resources

# 4. Confirm min/max replica limits
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E 'minReplicas|maxReplicas|replicas'

# 5. Look at the scaleTargetRef
kubectl describe hpa s12-hpa -n kubexplain-eval | grep -A3 scaleTargetRef

# 6. Review any custom behavior policies
kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A5 behavior

# 7. See current pod metrics
kubectl top pods -n kubexplain-eval

# 8. If using custom/external metrics, ensure the APIs exist
kubectl get --raw /apis/custom.metrics.k8s.io/v1beta1
kubectl get --raw /apis/external.metrics.k8s.io/v1beta1
```

Run the relevant checks, address the first failing item you encounter, and the HPA should start scaling again. If after fixing the top‑most cause the problem persists, move down the list until the behavior matches expectations. Good luck!
