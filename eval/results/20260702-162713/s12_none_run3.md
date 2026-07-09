# s12 | mode=none | run=3 | 17.6s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** does not scale (ordered by probability)

| # | Reason (why scaling fails) | How to verify (when you have cluster access) | How to fix |
|---|----------------------------|----------------------------------------------|------------|
| 1 | **Metrics API unavailable** – the HPA cannot read CPU/Memory (or custom) metrics because the *metrics‑server* (or custom‑metrics API) is not running, not reachable, or not authorized. | ```bash\nkubectl get pods -n kube-system -l k8s-app=metrics-server\nkubectl logs -n kube-system <metrics‑server‑pod>\nkubectl get --raw /apis/metrics.k8s.io/v1beta1/nodes\n```<br>Check the HPA’s `status.conditions` for a `Ready` condition that says *“the HPA controller could not get the metrics”*. | • Install or restart `metrics-server` (or the appropriate custom‑metrics adapter).<br>• Ensure the APIService `v1beta1.metrics.k8s.io` is `Available` (`kubectl get apiservice`).<br>• Verify the API server can reach the metrics service (no network policies blocking it). |
| 2 | **Target pods have no resource requests/limits** – without a CPU/Memory request the HPA cannot compute utilization percentages. | ```bash\nkubectl get deployment <target‑name> -n kubexplain-eval -o yaml | grep -A3 resources:\n```<br>Look for `resources.requests.cpu` / `resources.limits.cpu` in the pod template. | Add appropriate `resources.requests` (and optionally `limits`) to the container spec of the workload the HPA targets, then apply the change. |
| 3 | **HPA minReplicas equals maxReplicas (or both set to the current replica count)** – the controller has nowhere to scale. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E \"minReplicas|maxReplicas\"\n``` | Adjust `spec.minReplicas` and/or `spec.maxReplicas` to allow a scaling range that includes the desired higher (or lower) replica count. |
| 4 | **TargetRef points to the wrong controller or a non‑existent object** – the HPA is trying to scale a Deployment/StatefulSet that does not exist or is not the one you expect. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep targetRef -A3\n```<br>Confirm the referenced `kind`, `name`, and `apiVersion` exist. | Edit the HPA (`kubectl edit hpa s12-hpa -n kubexplain-eval`) and correct the `scaleTargetRef` to the proper workload. |
| 5 | **Current replica count already at the configured limits** – the workload is at `minReplicas` (for down‑scale) or `maxReplicas` (for up‑scale). | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep currentReplicas\nkubectl get deployment <target‑name> -n kubexplain-eval -o yaml | grep replicas\n``` | Increase `spec.maxReplicas` (or decrease `spec.minReplicas` if you expect down‑scaling). |
| 6 | **RBAC prevents the HPA controller from reading metrics or scaling the target** – the `system:controller:hpa` service account lacks required permissions. | ```bash\nkubectl auth can-i get --raw /apis/metrics.k8s.io/v1beta1/pods -n kubexplain-eval --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler\nkubectl auth can-i update deployment/<target‑name> -n kubexplain-eval --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler\n``` | Grant the missing permissions via a ClusterRole/ClusterRoleBinding for the HPA controller service account. |
| 7 | **Another controller (e.g., a second HPA, a GitOps tool, or manual `kubectl scale`) is constantly resetting the replica count** – the HPA’s desired replica number is overwritten. | ```bash\nkubectl get events -n kubexplain-eval --field-selector involvedObject.kind=Deployment,involvedObject.name=<target‑name>\n```<br>Look for frequent `ScalingReplicaSet` or `ScaledObject` events from other controllers. | Identify the conflicting controller and either remove it, adjust its configuration, or coordinate the scaling logic (e.g., use a single HPA). |
| 8 | **Custom metric used but the custom‑metrics adapter is missing or mis‑configured** – the HPA references a metric that cannot be fetched. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -A5 metrics\nkubectl get --raw /apis/custom.metrics.k8s.io/v1beta1\n``` | Deploy or fix the custom‑metrics adapter (Prometheus Adapter, Stackdriver, etc.) and ensure the metric name and labels match what the HPA expects. |
| 9 | **HPA controller itself is not running** – the `horizontal-pod-autoscaler` controller manager is disabled or crashed. | ```bash\nkubectl get pods -n kube-system -l component=kube-controller-manager\nkubectl logs -n kube-system <controller‑manager‑pod>\n``` | Re‑enable the HPA controller in the kube‑controller‑manager arguments (`--horizontal-pod-autoscaler-sync-period`, `--horizontal-pod-autoscaler-use-rest-clients`) and restart the controller manager. |
|10| **Version mismatch / deprecated API** – the HPA is defined with an older API version that the current cluster no longer serves (e.g., `autoscaling/v2beta2` removed). | ```bash\nkubectl api-versions | grep autoscaling\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep apiVersion\n``` | Convert the HPA manifest to a supported version (`autoscaling/v2` as of v1.23+) and re‑apply it. |

---

## How to verify the root cause (once you regain access)

1. **Inspect the HPA object**  
   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```
   *Key things to look for*: `Conditions` (especially `AbleToScale` and `ScalingActive`), `Current/Desired Replicas`, any warning events.

2. **Check the metrics pipeline**  
   - Verify `metrics-server` (or custom‑metrics adapter) pods are Running.  
   - Test the metrics API directly:  
     ```bash
     kubectl get --raw /apis/metrics.k8s.io/v1beta1/pods
     ```
   - Ensure the target pods have CPU/Memory requests.

3. **Validate replica limits**  
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}'
   kubectl get deployment <target-name> -n kubexplain-eval -o jsonpath='{.spec.replicas}'
   ```

4. **Confirm RBAC**  
   ```bash
   kubectl auth can-i get pods.metrics.k8s.io -n kubexplain-eval --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler
   ```

5. **Look for conflicting controllers**  
   ```bash
   kubectl get events -n kubexplain-eval --field-selector involvedObject.kind=Deployment,involvedObject.name=<target-name>
   ```

6. **Check controller‑manager logs** (if you have access to master nodes) for HPA‑related errors.

---

## Quick “fix checklist” (apply after you’re back in the cluster)

- **If metrics‑server is missing or unhealthy** → install/reinstall it (`kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`) and ensure the APIService is `Available`.
- **If pods lack resource requests** → add sensible `resources.requests.cpu` (e.g., `100m`) to the pod template and redeploy.
- **If min/max are too restrictive** → edit the HPA: `kubectl edit hpa s12-hpa -n kubexplain-eval` and widen the range.
- **If TargetRef is wrong** → correct the `scaleTargetRef` to the proper Deployment/StatefulSet name.
- **If RBAC is the issue** → create a ClusterRoleBinding for the HPA controller:
  ```yaml
  kind: ClusterRoleBinding
  apiVersion: rbac.authorization.k8s.io/v1
  metadata:
    name: hpa-controller
  subjects:
  - kind: ServiceAccount
    name: horizontal-pod-autoscaler
    namespace: kube-system
  roleRef:
    kind: ClusterRole
    name: system:controller:hpa
    apiGroup: rbac.authorization.k8s.io
  ```
- **If another controller is fighting the HPA** → disable or reconfigure the conflicting automation.
- **If using custom metrics** → verify the custom‑metrics adapter is deployed and the metric name matches the HPA spec.
- **If the HPA controller is disabled** → enable it in the kube‑controller‑manager flags and restart the component.
- **If API version is deprecated** → convert the manifest to `autoscaling/v2` and re‑apply.

---

### Bottom line
Start by confirming that the metrics API is reachable and that the target pods expose the required resource requests. Those two items account for the majority of “HPA does nothing” cases. From there, walk through the checklist above in order; each step will either reveal the problem or narrow the possibilities until the root cause is clear. Good luck when you get back into the cluster!
