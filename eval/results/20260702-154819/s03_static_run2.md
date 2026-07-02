# s03 | mode=static | run=2 | 8.8s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What the pod description tells us

| Field | Value | Why it matters |
|-------|-------|----------------|
| **Command** | `head -c 512m /dev/zero | tail` | This deliberately tries to allocate **~512 MiB** of memory in the container. |
| **Memory limit** | `64Mi` | The container is only allowed to use 64 MiB. |
| **Memory request** | `32Mi` | The scheduler only guarantees 32 MiB. |
| **Last State – Reason** | `OOMKilled` (exit code 137) | The kernel killed the process because it exceeded the cgroup memory limit. |
| **State** | `Waiting` → `CrashLoopBackOff` | After the OOM kill the kubelet keeps trying to restart the container, but each restart fails for the same reason, so it backs‑off. |
| **QoS Class** | `Burstable` | Because a limit is set, the pod can be evicted when the node is under memory pressure, but the immediate kill is already caused by the container’s own limit. |

**Root cause:** The container tries to use ~512 MiB of RAM while its cgroup limit is only 64 MiB. The kernel OOM‑killer terminates the process, the pod enters `CrashLoopBackOff`, and it keeps being restarted.

---

### How to fix it

#### 1. Increase the memory limits (and optionally the requests)

```bash
# If the pod is created by a Deployment, edit the Deployment spec:
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

In the `resources:` block for the `app` container, set limits high enough for the workload, e.g.:

```yaml
resources:
  requests:
    memory: "256Mi"   # what the scheduler should reserve
  limits:
    memory: "1Gi"     # hard cap – must be > 512Mi
```

*Why both request and limit?*  
- **Request** tells the scheduler how much memory to reserve on a node, preventing the pod from being placed on a node that cannot satisfy it.  
- **Limit** is the hard ceiling; the container will be killed if it exceeds this value.

#### 2. Reduce the memory consumption of the container

If the purpose of the pod is to demonstrate OOM behavior, you can simply lower the amount of data it tries to allocate:

```yaml
command:
- sh
- -c
- head -c 32m /dev/zero | tail   # now fits within a 64Mi limit
```

Or remove the `head …` pipeline altogether if you just need a long‑running container.

#### 3. Remove the limit (not recommended for production)

If you want the container to be unrestricted, omit the `limits.memory` field entirely. The pod will then have a **BestEffort** QoS class and will only be killed if the node itself runs out of memory.

#### 4. Verify the fix

After applying the change (e.g., `kubectl apply -f <manifest>` or editing the Deployment), watch the pod:

```bash
kubectl get pod s03-oomkilled -n kubexplain-eval -w
```

You should see the container transition to `Running` without entering `CrashLoopBackOff`. You can also confirm the memory usage:

```bash
kubectl top pod s03-oomkilled -n kubexplain-eval
```

---

### Quick checklist

- [ ] **Identify the workload’s real memory need** (here ~512 MiB).  
- [ ] **Set `requests.memory` ≥ that need** so the scheduler places the pod on a node with enough capacity.  
- [ ] **Set `limits.memory` ≥ that need** (or remove the limit if you truly want “unlimited”).  
- [ ] **Redeploy** the pod (or edit the owning controller).  
- [ ] **Monitor** the pod to ensure it stays `Running` and no OOM events appear.

---

### TL;DR

The pod is OOM‑killed because its command tries to allocate ~512 MiB while the container limit is only 64 MiB. Raise the memory `limits` (and optionally `requests`) to at least the amount the command needs, or lower the command’s memory usage. After updating the pod spec, the CrashLoopBackOff will disappear.
