# s12 | mode=static | run=1 | 17.9s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

## Most likely reasons why **s12‑hpa** is not scaling (ordered by probability)

| # | Reason | Why it stops scaling | How to verify (when you can run `kubectl`) | How to fix |
|---|--------|----------------------|--------------------------------------------|------------|
| 1 | **Metrics server (or custom‑metrics API) is missing or unhealthy** | HPA relies on the `metrics.k8s.io` API to read CPU/Memory (or custom) usage. If the API returns “no metrics” the controller will never trigger a scale‑out. | ```bash\nkubectl get apiservice v1beta1.metrics.k8s.io -o yaml\nkubectl get pods -n kube-system -l k8s-app=metrics-server\nkubectl logs -n kube-system <metrics‑server‑pod>\n```<br>Look for `Available: True` and for pod logs without errors like “failed to get node metrics”. | • Install or upgrade the metrics‑server (`kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`).<br>• Ensure the APIService is **Available**; if not, fix the service’s ServiceAccount, RBAC, or network (e.g., enable `--kubelet-insecure-tls` if using self‑signed certs). |
| 2 | **Target pods have no resource **requests** for the metric the HPA watches** (most common for CPU/Memory autoscaling) | HPA calculates utilization as `currentUsage / requested`. If the request is `0` the controller treats the metric as undefined and skips scaling. | ```bash\nkubectl describe deployment <target‑deployment> -n kubexplain-eval | grep -A5 \"Resources\"\n```<br>Check that each container has a `requests.cpu` (or `requests.memory`) defined. | • Add appropriate `resources.requests` (and optionally `limits`) to the pod spec.<br>• Re‑apply the deployment. |
| 3 | **HPA minReplicas == maxReplicas (or maxReplicas too low)** | Even if the metric exceeds the target, the controller cannot increase replicas beyond `maxReplicas`. | ```bash\nkubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E \"minReplicas|maxReplicas\"\n``` | • Raise `maxReplicas` to a value that can accommodate expected load.<br>• Ensure `minReplicas` is set to the desired baseline (usually ≥1). |
| 4 | **Current load never reaches the target utilization** | If the workload is light, the observed CPU/Memory stays below the threshold, so no scaling is needed. | ```bash\nkubectl top pods -n kubexplain-eval\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep -A3 \"Metrics\"\n``` | • Temporarily lower the target (e.g., `targetCPUUtilizationPercentage: 30`) to see if scaling triggers.<br>• Generate load (e.g., `kubectl run -i --rm load-generator --image=busybox -- /bin/sh -c "while true; do wget -q -O- http://<service>; done"`). |
| 5 | **HPA points at the wrong workload** (e.g., wrong `scaleTargetRef` name or kind) | The controller watches a Deployment/ReplicaSet/StatefulSet that either does not exist or is not the one serving traffic, so scaling has no effect. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep scaleTargetRef -A2\n``` | • Correct the `scaleTargetRef` to the actual workload name and kind.<br>• Re‑apply the HPA definition. |
| 6 | **Custom metric name or selector is wrong / metric not exposed** | For HPAs that use `external` or `object` metrics, a typo or missing metric source means the HPA never receives a value. | ```bash\nkubectl describe hpa s12-hpa -n kubexplain-eval | grep -A5 \"Metrics\"\nkubectl get --raw \"/apis/custom.metrics.k8s.io/v1beta1\" | jq .\n``` | • Verify the metric exists (e.g., via `kubectl get --raw /apis/custom.metrics.k8s.io/...`).<br>• Fix the metric name, selector, or install the custom‑metrics adapter. |
| 7 | **HPA controller manager is not running or is mis‑configured** | The control loop that reconciles HPA objects is absent, so no scaling actions are performed. | ```bash\nkubectl get pods -n kube-system -l component=horizontal-pod-autoscaler\nkubectl logs -n kube-system <hpa‑controller‑pod>\n``` | • Ensure the `cluster-autoscaler`/`horizontal-pod-autoscaler` deployment (or the `kube-controller-manager` if using the built‑in controller) is healthy.<br>• Restart or upgrade the controller component. |
| 8 | **RBAC prevents the HPA from reading metrics or scaling the target** | The HPA service account may lack `get`/`list` permissions on `metrics.k8s.io` or on the target workload. | ```bash\nkubectl auth can-i get --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler metrics.k8s.io\nkubectl auth can-i update deployment/<target> -n kubexplain-eval\n``` | • Grant the missing permissions via a `ClusterRole`/`ClusterRoleBinding` (or `RoleBinding` in the namespace). |
| 9 | **API server or etcd latency / errors** | If the API server is unhealthy, the HPA may fail to read metrics or write the new replica count, silently throttling scaling. | ```bash\nkubectl get --raw /healthz\nkubectl get events -n kubexplain-eval --field-selector reason=FailedGetMetrics\n``` | • Investigate API server health, check etcd logs, and resolve underlying performance issues. |

---

## How to verify each cause (once you regain cluster access)

1. **Metrics availability**  
   ```bash
   kubectl get apiservice v1beta1.metrics.k8s.io -o jsonpath='{.status.conditions[?(@.type=="Available")].status}'
   kubectl top nodes
   kubectl top pods -n kubexplain-eval
   ```
   *If `kubectl top` returns “metrics not available”, the metrics server is the problem.*

2. **Pod resource requests**  
   ```bash
   kubectl get deployment <target> -n kubexplain-eval -o yaml | grep -A5 resources
   ```
   *Every container must have a non‑zero `requests.cpu` (or `requests.memory`) when the HPA uses those metrics.*

3. **HPA min/max settings**  
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o yaml | grep -E "minReplicas|maxReplicas"
   ```

4. **Current utilization vs. target**  
   ```bash
   kubectl top pods -n kubexplain-eval
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

5. **Correct target reference**  
   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval | grep -A3 scaleTargetRef
   ```

6. **Custom/external metric health**  
   ```bash
   kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/kubexplain-eval/pods/*/my_metric" | jq .
   ```

7. **HPA controller health**  
   ```bash
   kubectl get pods -n kube-system -l component=horizontal-pod-autoscaler
   kubectl logs -n kube-system <hpa‑controller‑pod>
   ```

8. **RBAC checks**  
   ```bash
   kubectl auth can-i get --as=system:serviceaccount:kube-system:horizontal-pod-autoscaler metrics.k8s.io
   kubectl auth can-i update deployment/<target> -n kubexplain-eval
   ```

9. **API server health**  
   ```bash
   kubectl get --raw /healthz
   kubectl get events -n kubexplain-eval --field-selector reason=FailedGetMetrics
   ```

---

## Quick “fix checklist” (apply after you’re back in the cluster)

1. **Restore metrics** – reinstall or upgrade metrics‑server, ensure its ServiceAccount can read kubelet metrics, and verify the APIService is `Available`.
2. **Add resource requests** – edit the deployment to include sensible `resources.requests` for CPU/Memory.
3. **Adjust HPA limits** – set `minReplicas` and `maxReplicas` to values that allow scaling.
4. **Validate target workload** – correct `scaleTargetRef` if it points to the wrong object.
5. **Check load** – generate traffic or lower the target utilization to confirm the HPA reacts.
6. **Fix custom metrics** – ensure the custom‑metrics adapter is deployed and the metric name matches.
7. **Confirm controller** – restart the HPA controller pod or the kube‑controller‑manager if it’s stuck.
8. **Patch RBAC** – add missing `get/list/watch` on metrics APIs and `update` on the target workload.
9. **Monitor API health** – resolve any underlying API server or etcd issues that could be throttling controller actions.

By walking through the list in order, you’ll usually pinpoint the root cause within a few minutes once you have cluster access again. Good luck!
