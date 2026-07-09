# s09 | mode=dynamic | run=2 | 10.1s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most Likely Reasons the Pod `s09-bad-readiness` Has No Endpoints for Service `s09-web`

| # | Reason (probability high → low) | How to verify it | How to fix it |
|---|--------------------------------|------------------|---------------|
| **1** | **Readiness probe is failing** – the pod is **Running** but never becomes **Ready**, so the Service does not include it in its EndpointSlice. | ```bash\nkubectl -n kubexplain-eval get pod s09-bad-readiness -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'\n``` should return `False`.  <br>Also inspect the pod’s events and the `readinessProbe` status: <br>```bash\nkubectl -n kubexplain-eval describe pod s09-bad-readiness | grep -A5 \"Readiness\"```.  <br>If you see messages like *Readiness probe failed* or *Readiness probe timed out*, the probe is the blocker. | • Edit the pod (or its Deployment/StatefulSet) to correct the probe configuration (e.g., correct `httpGet` path, port, initialDelaySeconds, timeoutSeconds, or successThreshold). <br>• If the probe is unnecessary, remove it or change it to `readinessProbe: {}`. <br>After applying the change, wait for the pod to become Ready; the endpoint will appear automatically. |
| **2** | **Service selector does not match the pod’s labels** – the Service’s `spec.selector` points at a label key/value that the pod does not have (or has a typo). | ```bash\nkubectl -n kubexplain-eval get svc s09-web -o jsonpath='{.spec.selector}'\nkubectl -n kubexplain-eval get pod s09-bad-readiness --show-labels\n``` <br>Compare the two outputs. If any label key/value is missing or mismatched, the pod will not be selected. | • Add the missing label to the pod (or its controller) with `kubectl label pod s09-bad-readiness <key>=<value> --overwrite`. <br>• Or adjust the Service selector to match the existing pod labels. <br>After the label/selector is corrected, the EndpointSlice will be populated. |
| **3** | **TargetPort mismatch** – the Service’s `spec.ports[].targetPort` does not correspond to a container port that the pod actually exposes. The Service can still create an endpoint, but traffic will be dropped, and you may see the endpoint list empty if the pod is not considered a match because of a mis‑typed `port` name. | ```bash\nkubectl -n kubexplain-eval get svc s09-web -o yaml | grep targetPort\nkubectl -n kubexplain-eval get pod s09-bad-readiness -o yaml | grep containerPort\n``` <br>Check that the numeric port or named port matches exactly. | • Update the Service to use the correct `targetPort` (numeric or name) that the container actually listens on. <br>• Or modify the container’s `containerPort` (or expose the correct port) in the pod spec. |
| **4** | **Pod is in a different namespace** – the Service and pod must be in the same namespace. If the Service is in `kubexplain-eval` but the pod is actually in another namespace (e.g., default), the selector will never match. | ```bash\nkubectl get pod s09-bad-readiness -o jsonpath='{.metadata.namespace}'\n``` <br>Confirm it is `kubexplain-eval`. | • Move the pod (or its controller) into the correct namespace, or recreate the Service in the pod’s namespace. |
| **5** | **EndpointSlice controller is not running or is lagging** – a cluster‑wide issue where the controller that creates EndpointSlices is down, causing all services to have empty endpoints. | ```bash\nkubectl get pods -n kube-system -l k8s-app=endpoint-controller\n``` <br>Check that the controller pod is Running and has no errors. Also look at `kubectl get endpointslices -l kubernetes.io/service-name=s09-web`. | • Restart the controller manager or kube‑proxy if they are unhealthy. <br>• Ensure the API server and controller manager have the correct RBAC permissions to create EndpointSlices. |
| **6** | **NetworkPolicy blocks traffic to the pod** – a `NetworkPolicy` may allow the Service’s selector but deny traffic from the Service’s cluster IP, making it appear as “no traffic”. The endpoints may still exist, but the pod never receives packets. | ```bash\nkubectl -n kubexplain-eval get networkpolicy -o yaml\n``` <br>Look for policies that select the pod and restrict ingress from the Service’s IP range. | • Adjust or remove the NetworkPolicy to permit traffic from the Service’s cluster IP (or from all pods in the same namespace). |
| **7** | **Pod is stuck in a terminating or evicted state** – a pod that is still listed but not actually running (e.g., `Terminating` with finalizers) will not be added to endpoints. | ```bash\nkubectl -n kubexplain-eval get pod s09-bad-readiness -o wide\n``` <br>Check the `STATUS` column. | • Delete the pod (`kubectl delete pod s09-bad-readiness`) and let the controller recreate it, or clear the finalizer if it is safe. |

---

### Quick Verification Checklist (run when you regain cluster access)

1. **Readiness** – `kubectl describe pod …` → look for *Ready* condition and probe failures.  
2. **Labels vs. Selector** – `kubectl get svc s09-web -o yaml` and `kubectl get pod s09-bad-readiness --show-labels`.  
3. **Port Alignment** – confirm `targetPort` matches a `containerPort` (or named port).  
4. **Namespace** – ensure both objects share the same namespace.  
5. **EndpointSlice health** – `kubectl get endpointslices -l kubernetes.io/service-name=s09-web`.  
6. **NetworkPolicy** – list policies in the namespace and verify ingress rules.  
7. **Pod lifecycle** – check `STATUS` for `Terminating`, `Evicted`, or `CrashLoopBackOff`.

Fix the first issue you discover; after the correction the EndpointSlice controller will automatically populate the service’s endpoints, and traffic will start flowing to `s09-bad-readiness`.
