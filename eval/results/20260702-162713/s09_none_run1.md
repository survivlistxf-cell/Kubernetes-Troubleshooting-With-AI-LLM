# s09 | mode=none | run=1 | 11.5s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why **`s09-bad-readiness`** has no endpoints in service **`s09-web`**

| # | Likely cause (most to least probable) | Why it matters | How to verify | How to fix |
|---|----------------------------------------|----------------|---------------|------------|
| 1 | **Readiness probe is failing** (pod is *Running* but never becomes *Ready*) | A pod that is not **Ready** is excluded from a Service’s endpoint list. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'\nkubectl describe pod s09-bad-readiness -n kubexplain-eval | grep -i readiness\n``` | • Inspect the probe definition in the pod spec (`readinessProbe`).<br>• Fix the probe (e.g., correct path, port, initialDelaySeconds, timeoutSeconds).<br>• If the probe is unnecessary, remove it or change it to `successThreshold: 1` and a permissive command. |
| 2 | **Pod labels do not match the Service selector** | Services select pods by label. If the pod’s labels differ, it will never be added to the endpoint list. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.labels}'\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.selector}'\n``` | • Align the pod’s labels with the Service selector (add missing label or edit the Service selector).<br>• Example: `kubectl label pod s09-bad-readiness -n kubexplain-eval app=web` (or whatever key/value the Service expects). |
| 3 | **Service selector is wrong or missing** | Even if the pod has the right labels, a typo or missing selector in the Service will produce an empty endpoint list. | ```bash\nkubectl describe svc s09-web -n kubexplain-eval | grep Selector -A5\n``` | • Correct the selector in the Service manifest (`spec.selector`).<br>• Re‑apply the Service (`kubectl apply -f <svc‑yaml>`). |
| 4 | **Port mismatch between Service and Pod** (container not exposing the port the Service targets) | The Service can still list the pod as an endpoint, but traffic will be dropped if the container isn’t listening on the advertised port. Some controllers may omit the pod if the targetPort cannot be resolved. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.spec.containers[*].ports[*].containerPort}'\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.ports[*].targetPort}'\n``` | • Ensure the container actually listens on the `targetPort` (e.g., update the container image or command).<br>• Adjust the Service `targetPort` to match the container’s exposed port. |
| 5 | **NetworkPolicy blocks traffic to the pod** (or from the Service) | A NetworkPolicy that denies ingress to the pod can prevent the Service from adding the pod as an endpoint (in some CNI implementations). | ```bash\nkubectl get networkpolicy -n kubexplain-eval -o wide\n``` | • Modify or delete the restrictive NetworkPolicy, or add an `allow` rule for the Service’s namespace/selector. |
| 6 | **Pod is in a different namespace than the Service** | Services only select pods in the same namespace. If the pod was created in another namespace (or the Service was moved), endpoints will be empty. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.namespace}'\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.metadata.namespace}'\n``` | • Re‑create the pod in the same namespace as the Service, or create a Service in the pod’s namespace. |
| 7 | **EndpointSlice controller is unhealthy** (rare) | If the controller that populates EndpointSlices is down, endpoints may stay empty even though pods are ready. | ```bash\nkubectl get endpointslice -n kubexplain-eval -l kubernetes.io/service-name=s09-web\nkubectl get pods -n kube-system -l k8s-app=endpoint-controller\n``` | • Check the controller’s logs; restart the controller manager or the kube‑proxy daemonset if needed. |
| 8 | **Pod IP not yet allocated or IP conflict** | A pod without an IP cannot be added to the endpoint list. This can happen during node networking issues. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.status.podIP}'\n``` | • If `podIP` is `<none>`, investigate node CNI health, restart the CNI plugin, or evict the pod to another node. |
| 9 | **Service type mismatch (e.g., `ExternalName`)** | An `ExternalName` service never creates endpoints; if the Service was mistakenly created as that type, endpoints will be empty. | ```bash\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.type}'\n``` | • Change the Service type to `ClusterIP`, `NodePort`, or `LoadBalancer` as appropriate. |
|10| **Pod is being terminated but still shows `Running`** (stuck termination) | A pod stuck in `Terminating` may still appear as `Running` but is excluded from endpoints. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.deletionTimestamp}'\n``` | • Delete the pod forcefully (`kubectl delete pod s09-bad-readiness -n kubexplain-eval --grace-period=0 --force`) and let the deployment recreate it. |

---

## How to proceed when you regain cluster access

1. **Check the pod’s readiness status first** – it is the single biggest reason for empty endpoints.  
2. **Validate label‑selector alignment** – a one‑character typo in a label or selector is a common oversight.  
3. **Confirm ports and Service type** – mismatched ports silently break traffic.  
4. **Inspect any NetworkPolicy** that could be restricting ingress.  
5. **If everything above looks correct**, look at the controller health (EndpointSlice, kube‑proxy) and node networking.

Fix the first issue you discover; after each change, run:

```bash
kubectl get endpoints s09-web -n kubexplain-eval
```

or

```bash
kubectl get endpointslice -n kubexplain-eval -l kubernetes.io/service-name=s09-web
```

to see the endpoint list populate again. Once the pod appears, traffic from the Service will be routed correctly.
