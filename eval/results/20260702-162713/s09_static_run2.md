# s09 | mode=static | run=2 | 14.1s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

**Why a running pod can have an empty `Endpoints` list**

When a Service’s endpoint list is empty the control‑plane has decided that **no pod currently satisfies the Service’s selector + readiness requirements**.  
Below are the most common reasons, ordered by how often they cause the symptom you described, together with the quick checks you can run (once you have cluster access) and the corrective actions.

---

## 1. Label‑selector mismatch (most frequent)

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| The Service’s `spec.selector` does **not** match the labels on `s09-bad-readiness`. The pod is therefore invisible to the Service. | ```bash<br>kubectl get svc s09‑web -n kubexplain‑eval -o jsonpath='{.spec.selector}'<br>kubectl get pod s09‑bad‑readiness -n kubexplain‑eval --show-labels```<br>Compare the two sets of key/value pairs. | Edit either the Service selector or the pod’s labels so they line up. Example: <br>```bash<br>kubectl label pod s09‑bad‑readiness -n kubexplain‑eval app=s09‑web --overwrite```<br>or <br>```bash<br>kubectl edit svc s09‑web -n kubexplain‑eval``` and adjust `spec.selector`. |

---

## 2. Readiness probe failing (next most common)

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| The pod is **Running** but its containers are not **Ready** because the readiness probe returns failure (or is missing). Services only include pods that are Ready. | ```bash<br>kubectl describe pod s09‑bad‑readiness -n kubexplain‑eval```<br>Look for `Readiness probe` status and any `Readiness probe failed` events. | • Fix the probe command/HTTP path/port so it succeeds.<br>• If you don’t need a probe, remove it from the pod spec.<br>• Temporarily set `readinessProbe: { failureThreshold: 1, periodSeconds: 10 }` to speed up debugging, then apply the corrected spec. |

---

## 3. Pod not yet Ready for another reason (e.g., container still starting, crash‑loop, Init container)

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| The pod’s containers are still `Waiting` or `Terminating`, or an Init container has not completed, so the pod is not marked Ready. | ```bash<br>kubectl get pod s09‑bad‑readiness -n kubexplain‑eval -o wide```<br>Check the `READY` column (e.g., `0/1`). Also inspect `kubectl describe pod …` for events such as `ImagePullBackOff`, `CrashLoopBackOff`, or Init‑container failures. | Resolve the underlying issue (image name, pull secret, resource limits, Init‑container command, etc.) and let the pod reach `READY 1/1`. Once Ready, the Service will automatically add the endpoint. |

---

## 4. Service and pod in different namespaces

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| Services only select pods **in the same namespace**. If the pod lives in `kubexplain‑eval` but the Service was created in another namespace (or vice‑versa), the selector will never match. | ```bash<br>kubectl get svc s09‑web -o yaml | grep namespace<br>kubectl get pod s09‑bad‑readiness -o yaml | grep namespace``` | Re‑create the Service in the correct namespace or move the pod (e.g., delete and re‑apply it in the same namespace). |

---

## 5. TargetPort / containerPort mismatch

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| The Service’s `spec.ports[].targetPort` points to a port that the pod does **not expose** (or the containerPort is different). The endpoint is still created, but traffic cannot reach the container, which sometimes leads to the pod being considered not Ready if a readiness probe uses the Service port. | ```bash<br>kubectl get svc s09‑web -n kubexplain‑eval -o jsonpath='{.spec.ports[*].targetPort}'<br>kubectl get pod s09‑bad‑readiness -n kubexplain‑eval -o jsonpath='{.spec.containers[*].ports[*].containerPort}'``` | Align the numbers: either change the Service `targetPort` to the container’s port, or add the missing `containerPort` to the pod spec. |

---

## 6. EndpointSlice controller disabled or API‑server issue

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| The control‑plane component that creates `EndpointSlice` objects is not running (rare in managed clusters) or the API server is rejecting the creation. The Service appears fine but no EndpointSlice objects are generated. | ```bash<br>kubectl get endpointslice -n kubexplain‑eval -l kubernetes.io/service-name=s09‑web -o yaml```<br>If the list is empty and the Service selector is correct, check the controller manager logs for `endpointslice` errors. | Ensure the `EndpointSlice` controller is enabled (`--feature-gates=EndpointSlice=true`) and that the controller manager is healthy. In most clusters this is a platform issue; contact the cluster admin or provider. |

---

## 7. NetworkPolicy blocking traffic (does **not** affect endpoint creation but can make it look like “no traffic”)

| What happens | How to verify | How to fix |
|--------------|---------------|------------|
| Pods are listed as endpoints, but a `NetworkPolicy` denies traffic from the Service’s source IPs, so you observe “no traffic”. | ```bash<br>kubectl get networkpolicy -n kubexplain‑eval<br>kubectl describe networkpolicy <policy> -n kubexplain‑eval``` | Adjust the policy to allow traffic from the Service’s pod selector or from the namespace, or delete the restrictive policy. |

*Note: This is less likely to cause an **empty** endpoint list, but it’s worth checking if you see endpoints but still get no response.*

---

### Quick “one‑shot” verification checklist (run when you regain access)

```bash
# 1. Confirm selector vs pod labels
kubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.selector}'
kubectl get pod s09-bad-readiness -n kubexplain-eval --show-labels

# 2. Look at pod readiness state and probes
kubectl describe pod s09-bad-readiness -n kubexplain-eval

# 3. Verify EndpointSlice objects
kubectl get endpointslice -n kubexplain-eval -l kubernetes.io/service-name=s09-web

# 4. Check namespace consistency
kubectl get svc s09-web -n kubexplain-eval
kubectl get pod s09-bad-readiness -n kubexplain-eval

# 5. Compare ports
kubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.ports[*].targetPort}'
kubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.spec.containers[*].ports[*].containerPort}'
```

Running those commands will pinpoint which of the above categories is responsible, after which you can apply the corresponding fix.

---

### TL;DR – most probable root cause

1. **Label selector mismatch** – fix labels or selector.  
2. **Readiness probe failing** – correct or remove the probe.  
3. **Pod not Ready for another reason** – resolve container/start‑up issues.  

Address those first; the other items are progressively less common but easy to verify if the problem persists. Good luck!
