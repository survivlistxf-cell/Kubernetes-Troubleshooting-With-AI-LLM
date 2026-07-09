# s04 | mode=static | run=3 | 8.5s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What is happening?

| Field | Value |
|-------|-------|
| **Container image** | `busybox:1.36` |
| **Command** | `while true; do :; done` (an infinite busy‑loop) |
| **CPU request** | `10m` (10 millicores) |
| **CPU limit**   | `25m` (25 millicores) |
| **QoS class**   | **Burstable** |

The container is a pure CPU‑bound workload (the loop does nothing but consume CPU cycles).  
Kubernetes enforces the **CPU limit** by configuring a cgroup quota: the container may use at most **25 millicores** (≈ 2.5 % of a single core). When the process tries to run faster than that, the kernel throttles it. The result is a **very slow / unresponsive** application – exactly what you are observing.

So the root cause is **CPU throttling caused by an overly‑low CPU limit**.

---

### How to fix it

1. **Raise the CPU limit (and optionally the request) to a value that matches the workload’s needs.**  
   For a busy‑loop you’ll typically want at least a full core (`1000m`) or whatever performance you require.

2. **If you don’t need a hard limit, remove the `limits.cpu` field** – the pod will then run with the request value (or with no limit, i.e., “best‑effort” for CPU).

3. **Re‑apply the manifest** (or edit the live pod if it’s a one‑off test).

#### Example manifest change

```yaml
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
      requests:
        cpu: "200m"      # 20 % of a core – enough to schedule the pod
      limits:
        cpu: "1000m"     # 1 full core – prevents throttling
```

Apply the updated manifest:

```bash
kubectl apply -f s04-cpu-throttle.yaml
```

Or, for a quick fix on the existing pod (only works while the pod is still alive; a new pod will be created from the manifest anyway):

```bash
kubectl delete pod s04-cpu-throttle -n kubexplain-eval
# then recreate with the corrected manifest
kubectl apply -f s04-cpu-throttle.yaml
```

---

### Why the limit matters

* **CPU request** → used by the scheduler to decide where to place the pod.  
* **CPU limit** → enforced by the kubelet via cgroup quotas. When a container exceeds its quota, the kernel throttles it, causing the “slow” behavior you see.

Because the pod’s QoS class is **Burstable**, the request (10 m) is far lower than the limit (25 m). The scheduler placed the pod on a node that could satisfy the request, but the limit is still too restrictive for a CPU‑intensive loop.

---

### TL;DR

*Root cause*: The pod is limited to **25 millicores**, which is far below what a busy‑loop needs, so the kernel throttles the container.  
*Fix*: Increase (or remove) the `cpu` limit – e.g., set `limits.cpu: "1000m"` and a matching request, then redeploy the pod. After the change the loop will run at full speed and the application will become responsive again.
