# s09 | mode=dynamic | run=1 | 11.5s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why **`s09-bad-readiness`** shows up in the Service’s endpoint list as empty  

| # | Probable cause | Why it matters | How to verify | How to fix |
|---|----------------|----------------|---------------|------------|
| 1 | **Service selector does not match the pod’s labels** | The EndpointSlice controller only creates endpoints for Pods whose labels satisfy the Service’s `spec.selector`. If the selector is wrong, the Service will never see any Pods, even though the Pods are Running. | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o jsonpath='{.spec.selector}'\nkubectl get pod s09‑bad‑readiness -n kubexplain‑eval --show-labels\n```<br>Compare the selector map with the pod’s actual labels. | Edit the Service (or the Pod) so that the selector keys/values match exactly. Example: <br>```bash\nkubectl edit svc s09‑web -n kubexplain‑eval   # adjust .spec.selector\n# or\nkubectl label pod s09‑bad‑readiness -n kubexplain‑eval app=web   # add the missing label\n``` |
| 2 | **Readiness probe is failing** | A Pod that is Running but not Ready is excluded from a Service’s endpoints. The Service will show an empty endpoint list if **all** matching Pods are not Ready. | ```bash\nkubectl describe pod s09‑bad‑readiness -n kubexplain‑eval | grep -A4 \"Readiness\" \n```<br>Look for `Readiness probe` status → `Failed` and the associated message. | Fix the probe definition (path, port, initialDelaySeconds, timeoutSeconds, etc.) or the application so that it returns a successful response. Then redeploy or edit the pod spec: <br>```bash\nkubectl edit pod s09‑bad‑readiness -n kubexplain‑eval   # adjust .spec.containers[].readinessProbe\n``` |
| 3 | **TargetPort does not match any containerPort** | The Service forwards traffic to the `targetPort`. If no container in the Pod listens on that port, the endpoint is considered unhealthy and may be omitted. | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o jsonpath='{.spec.ports[*].targetPort}'\nkubectl get pod s09‑bad‑readiness -n kubexplain‑eval -o jsonpath='{.spec.containers[*].ports[*].containerPort}'\n``` | Ensure the container actually exposes the port the Service expects. Add or correct the `containerPort` in the pod/deployment spec, or change the Service’s `targetPort` to the correct value. |
| 4 | **Pod is in a different namespace** (or Service is looking in the wrong namespace) | Services only select Pods **in the same namespace**. If the Service lives in `kubexplain‑eval` but the pod was created in another namespace, the selector will never match. | ```bash\nkubectl get pod s09‑bad‑readiness --all-namespaces | grep s09‑bad‑readiness\n``` | Re‑create the pod in the same namespace as the Service, or move the Service to the pod’s namespace. |
| 5 | **NetworkPolicy blocks traffic to the pod** | Even when the endpoint exists, a restrictive `NetworkPolicy` can prevent the Service’s traffic from reaching the pod, and some clusters hide the endpoint as “not ready”. | ```bash\nkubectl get networkpolicy -n kubexplain‑eval\n```<br>Check if any policy selects the pod and denies ingress from the Service’s namespace. | Adjust the NetworkPolicy to allow traffic from the Service (e.g., add an `ingress` rule with `podSelector` matching the pod and `namespaceSelector` matching the Service’s namespace). |
| 6 | **kube‑proxy / iptables issue on the node** | If kube‑proxy is mis‑configured or iptables rules are missing, the Service’s virtual IP may not be programmed, leading to empty endpoints in some diagnostics. | ```bash\nkubectl get endpointslice -l kubernetes.io/service-name=s09‑web -n kubexplain‑eval\nkubectl logs -n kube-system -l k8s-app=kube-proxy --tail=20\n``` | Restart kube‑proxy on the affected node(s) or reinstall the kube‑proxy DaemonSet. Verify that the node’s iptables contain the expected `KUBE-SERVICES` chains. |
| 7 | **Pod is being evicted / has a finalizer that prevents it from becoming Ready** | A pod stuck in `Terminating` or with a pending finalizer can appear Running but never become Ready, so the Service sees no endpoints. | ```bash\nkubectl get pod s09‑bad‑readiness -n kubexplain‑eval -o yaml | grep -i finalizers\n``` | Remove the problematic finalizer (if safe) or let the underlying controller finish its cleanup, then delete and recreate the pod. |
| 8 | **Service type mismatch (e.g., ExternalName) or missing `ClusterIP`** | An `ExternalName` Service does not create endpoints; a `ClusterIP` Service with `spec.clusterIP: None` (headless) relies on DNS rather than endpoints. | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o yaml | grep type\n``` | If the Service is not of type `ClusterIP`/`NodePort`/`LoadBalancer`, change it to the appropriate type for pod traffic. |

### Quick verification checklist (run when you regain cluster access)

1. **Selector vs. Labels** – ensure they match exactly.  
2. **Readiness** – `kubectl describe pod …` → look for `Ready` condition and probe status.  
3. **Port alignment** – confirm Service `targetPort` equals a container’s `containerPort`.  
4. **Namespace consistency** – both Service and Pod must be in `kubexplain‑eval`.  
5. **NetworkPolicy** – verify no deny‑all policy is applied.  
6. **EndpointSlices** – `kubectl get endpointslices -l kubernetes.io/service-name=s09‑web -n kubexplain‑eval` should list the pod IPs.  
7. **kube‑proxy health** – check daemonset logs and node iptables if other causes are ruled out.  

Address the first cause that fails the check; in most clusters the **selector‑label mismatch** is the dominant reason for an empty endpoint list. Fixing that alone usually restores traffic to the pod.
