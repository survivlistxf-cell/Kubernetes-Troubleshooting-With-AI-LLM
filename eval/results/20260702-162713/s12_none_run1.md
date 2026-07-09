# s12 | mode=none | run=1 | 17.6s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons an HPA isn’t scaling (ordered by how often they bite)

| # | Likely cause | Why it stops scaling | How to verify | How to fix |
|---|--------------|----------------------|---------------|------------|
| 1 | **Metrics API can’t supply the required data** (metrics‑server missing, API‑service down, or the pod isn’t exposing the metric) | The HPA polls the metrics API; if it gets no data it assumes the current usage is *0* and never reaches the target. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval\n``` Look for events such as *“failed to get cpu utilization”* or *“metrics not available”*. <br>Check the metrics server: <br>```bash\nkubectl get apiservice v1beta1.metrics.k8s.io -o yaml\n``` and <br>```bash\nkubectl top pods -n kubexplain-eval\n``` (should return CPU/Memory). | • Install or restart the metrics‑server (`kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`). <br>• Ensure the APIService is **Available**. <br>• If you use custom/external metrics, verify the corresponding adapter is running and the metric name matches the HPA spec. |
| 2 | **Target utilization/metric never reaches the threshold** (e.g., CPU request is too high, workload is idle) | The HPA only scales when the observed metric > *target* × *current replicas*. If the workload never spikes, nothing happens. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml\n``` Check `spec.metrics[].resource.target.averageUtilization` (or `targetAverageValue`). <br>Run `kubectl top pods` on the pods behind the HPA to see current %‑utilization. | • Lower the target value (e.g., from 80 % to 50 %). <br>• Increase the pod’s resource **requests** so the same absolute usage translates to a higher %‑utilization. <br>• Generate load (e.g., a simple `stress` or `hey` test) to confirm scaling works. |
| 3 | **`minReplicas` equals `maxReplicas` (or the range is too narrow)** | Even if the metric exceeds the target, the HPA can’t go outside the configured replica window. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}'\n``` | • Adjust the range to give the controller room to scale: <br>```yaml\nspec:\n  minReplicas: 2\n  maxReplicas: 10\n``` |
| 4 | **Label selector on the HPA does not match any pods** (wrong `matchLabels` or missing `selector` on the target workload) | The HPA can’t compute utilization because it can’t find the pods it should monitor. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval\n``` Look for *“no matching pods”* in the events. <br>Check the selector of the target Deployment/StatefulSet: <br>```bash\nkubectl get deployment <target-name> -n kubexplain-eval -o yaml | grep selector -A3\n``` | • Align the HPA’s `scaleTargetRef` and `metrics[].resource.selector` with the pod labels. <br>• Update the Deployment/StatefulSet labels or the HPA selector so they match. |
| 5 | **ResourceQuota or LimitRange blocks additional replicas** | The cluster may reject the scale‑up request because it would exceed a namespace‑wide quota. | ```bash\nkubectl get quota -n kubexplain-eval\n``` Look for `hard` vs `used` on `pods` or `cpu`/`memory`. | • Raise the quota limits or delete the quota if it’s not needed. <br>• Reduce the HPA `maxReplicas` to stay within the quota. |
| 6 | **RBAC prevents the HPA controller from reading metrics or updating the scale subresource** | The controller‑manager runs with a ServiceAccount that needs `get`, `list`, `watch` on metrics and `update` on the target’s `scale` subresource. | ```bash\nkubectl auth can-i get --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler -n kubexplain-eval pods/metrics\nkubectl auth can-i update --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler deployment/<target-name>\n``` | • Grant the missing permissions via a ClusterRole/RoleBinding. <br>Typical rule: <br>```yaml\n- apiGroups: [\"metrics.k8s.io\"]\n  resources: [\"pods\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n- apiGroups: [\"apps\"]\n  resources: [\"deployments/scale\"]\n  verbs: [\"get\",\"update\"]\n``` |
| 7 | **HPA controller is not running or is unhealthy** (e.g., kube‑controller‑manager down, HPA feature gate disabled) | No controller → no reconciliation → HPA never reacts. | ```bash\nkubectl get pods -n kube-system -l component=kube-controller-manager\n``` Check the pod’s status and logs for errors about “horizontal‑pod‑autoscaling”. | • Restart the controller‑manager (or the control‑plane node). <br>• Ensure the `--horizontal-pod-autoscaling` flag is enabled. |
| 8 | **Using an external metric that isn’t being produced** (e.g., Prometheus adapter mis‑configured) | The HPA sees “no data” for the external metric and stays at the current replica count. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval\n``` Look for events like *“failed to get external metric”*. <br>Query the external source directly (e.g., `curl` Prometheus) to see if the metric exists. | • Fix the external metric adapter configuration (service name, auth, metric name). <br>• Verify the metric name in the HPA spec matches exactly what the adapter exposes. |
| 9 | **Stabilization window / cooldown prevents a new scale‑up** (default 5 min) | After a recent scale‑up/down, the HPA will ignore further changes until the window expires. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -i stabilization\n``` | • Reduce `behavior.scaleUp.stabilizationWindowSeconds` (or `scaleDown`) if you need faster reactions. <br>• Wait for the window to pass and re‑test. |
|10| **PodDisruptionBudget (PDB) blocks scaling** (e.g., maxUnavailable = 0) | The HPA may try to add pods, but the scheduler refuses because the PDB would be violated. | ```bash\nkubectl get pdb -n kubexplain-eval\n``` | • Adjust the PDB to allow the extra replicas (e.g., `maxUnavailable: 1`). |

---

## How to systematically verify the root cause when you regain cluster access

1. **Inspect the HPA object**  
   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```  
   - Look for *Events* that mention “failed to get metrics”, “no matching pods”, or “scale-up limited by …”.

2. **Check the current metric values**  
   ```bash
   kubectl top pods -n kubexplain-eval
   ```  
   - Verify the CPU/Memory percentages relative to the requests.

3. **Validate the target workload**  
   ```bash
   kubectl get deployment <target-name> -n kubexplain-eval -o yaml
   ```  
   - Confirm `spec.replicas`, `spec.selector`, and pod labels line up with the HPA selector.

4. **Confirm the metrics pipeline**  
   - APIService status: `kubectl get apiservice v1beta1.metrics.k8s.io -o yaml` (should be *Available*).  
   - Metrics‑server pods: `kubectl get pods -n kube-system -l k8s-app=metrics-server`.  

5. **Look for quota or PDB constraints**  
   ```bash
   kubectl get quota -n kubexplain-eval
   kubectl get pdb -n kubexplain-eval
   ```

6. **Check controller health**  
   ```bash
   kubectl get pods -n kube-system -l component=kube-controller-manager
   kubectl logs <controller-pod> -n kube-system
   ```

7. **Review RBAC** (if you suspect permission issues)  
   ```bash
   kubectl auth can-i get pods/metrics --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler
   kubectl auth can-i update deployment/<target-name> --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler
   ```

---

## Quick “fix checklist” you can run once you’re back in the cluster

1. **Metrics server** – install or restart if `kubectl top` fails.  
2. **Target utilization** – lower the `targetAverageUtilization` or raise pod requests.  
3. **Replica window** – ensure `minReplicas < maxReplicas`.  
4. **Label selector** – make the HPA selector match the pod labels.  
5. **Quota/PDB** – raise limits or relax the budget.  
6. **RBAC** – add missing `get/list/watch` on `metrics.k8s.io` and `update` on the scale subresource.  
7. **Controller** – restart the controller‑manager if it’s down.  
8. **External metrics** – fix the adapter or metric name.  
9. **Stabilization** – adjust `behavior` fields if you need faster scaling.  

Address the items in the order above; in most clusters the first two (metrics availability and target threshold) resolve the majority of “HPA does nothing” cases. If those are fine, move down the list until the offending condition is identified. Good luck!
