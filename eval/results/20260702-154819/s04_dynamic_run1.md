# s04 | mode=dynamic | run=1 | 6.9s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## What’s happening

- The container runs an infinite busy‑loop (`while true; do :; done`).  
- A **CPU limit of 25 m** (25 millicores) is set, with a request of only **10 m**.  
- With such a tiny limit the kubelet’s cgroup throttles the process heavily.  
- The pod is therefore **CPU‑throttled**, which makes the loop run extremely slowly and the application appear unresponsive.  
- The QoS class is *Burstable* (limits ≠ requests), so the pod is subject to throttling as soon as it exceeds the 25 m limit.

## How to fix it

1. **Raise the CPU limit (and optionally the request) to a value that matches the workload.**  
   - For a simple busy‑loop you can start with `100m` (0.1 CPU) or higher.  
   - If you set `limits` = `requests`, the pod will become *Guaranteed* and won’t be throttled unless the node itself is oversubscribed.

2. **Update the manifest** (or the owning Deployment/Job/ReplicaSet) and re‑apply.

### Example patch (one‑off edit)

```bash
kubectl -n kubexplain-eval edit pod s04-cpu-throttle
```

Change the `resources` block to:

```yaml
resources:
  limits:
    cpu: "100m"
  requests:
    cpu: "100m"
```

### If the pod is managed by a higher‑level controller

```bash
# Edit the controller (e.g., Deployment) that created the pod
kubectl -n kubexplain-eval edit deployment <deployment-name>
```

Update the same `resources` section, then let the controller roll out the new pod.

### Apply a new manifest directly

```bash
cat <<EOF > pod-fixed.yaml
apiVersion: v1
kind: Pod
metadata:
  name: s04-cpu-throttle
  namespace: kubexplain-eval
spec:
  containers:
  - name: app
    image: busybox:1.36
    command: ["sh", "-c", "while true; do :; done"]
    resources:
      limits:
        cpu: "100m"
      requests:
        cpu: "100m"
EOF

kubectl apply -f pod-fixed.yaml
```

## Verify the fix

```bash
kubectl -n kubexplain-eval top pod s04-cpu-throttle
```

- The **CPU%** should now be close to the limit (e.g., ~10 % of a core) and **no throttling** messages should appear in `kubectl describe pod …` under the *Events* or *Containers* section.

---

**Root cause:** CPU limit of 25 m caused the container to be throttled, making the busy‑loop (and any real workload) extremely slow.  
**Fix:** Increase the CPU request/limit (or remove the limit) so the pod has enough CPU capacity.
