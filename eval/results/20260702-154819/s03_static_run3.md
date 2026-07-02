# s03 | mode=static | run=3 | 8.8s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What the `describe` output tells us  

| Field | Value | Why it matters |
|-------|-------|----------------|
| **Command** | `head -c 512m /dev/zero | tail` | The container tries to read **512 MiB** of data into memory. |
| **Limits** | `memory: 64Mi` | The cgroup that the kubelet creates for the container is capped at **64 MiB**. |
| **Last State – Reason** | `OOMKilled` (exit code 137) | The process exceeded the memory limit, the kernel’s OOM‑killer terminated it. |
| **State** | `Waiting → CrashLoopBackOff` | After the kill the kubelet restarts the container; because it dies immediately again it goes into the back‑off loop. |
| **QoS Class** | `Burstable` | Because a request (32 Mi) is set and it is lower than the limit, the pod is placed in the *Burstable* QoS tier – it still gets killed when it exceeds the hard limit. |

**Root cause:** the container is trying to allocate ~512 MiB while the pod’s memory **limit** is only 64 MiB. The kernel OOM‑killer stops the process, the kubelet restarts it, and the pod ends up in `CrashLoopBackOff`.

---

## How to fix it

You have three typical options; pick the one that matches the scenario you want to demonstrate.

### 1. Raise the memory limit (and optionally the request)

```bash
# Edit the pod (or the Deployment/Job that created it)
kubectl edit pod s03-oomkilled -n kubexplain-eval
```

In the editor change the `resources` block, e.g.:

```yaml
resources:
  requests:
    memory: "512Mi"   # at least the amount you will actually use
  limits:
    memory: "1Gi"     # a hard ceiling higher than the request
```

Save and exit – the pod will be recreated with the new limits and the command will finish without being killed.

### 2. Reduce the memory consumption of the container

If the purpose of the pod is to *demonstrate* an OOM scenario, you can keep the limits low and change the command to something that fits, e.g.:

```yaml
command: ["sh", "-c", "head -c 32m /dev/zero | tail"]
```

or simply:

```yaml
command: ["sh", "-c", "echo hello"]
```

Apply the change with `kubectl apply -f <manifest>` or `kubectl edit pod …`.

### 3. Remove the limit altogether (not recommended for production)

```yaml
resources:
  requests:
    memory: "64Mi"
# no limits section
```

Without a hard limit the container can use as much memory as the node permits (subject to node‑level pressure). This will stop the OOM kill, but it may cause node‑level memory pressure if many pods behave similarly.

---

## Quick verification steps (optional)

1. **Check the pod’s memory usage while it runs**  

   ```bash
   kubectl top pod s03-oomkilled -n kubexplain-eval
   ```

2. **Confirm the new limits are in effect**  

   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml | grep -A4 resources
   ```

3. **Watch the pod status**  

   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -w
   ```

When the limits are high enough (or the command is small enough), the pod should move to `Running` → `Ready` and stay there without entering `CrashLoopBackOff`.

---

### TL;DR

*The pod is OOM‑killed because it tries to allocate ~512 MiB while its memory limit is only 64 MiB.*  
Fix it by **increasing the memory limit/request**, **reducing the workload’s memory demand**, or **removing the limit** (with caution). After applying the change, the pod will stop crashing and the `CrashLoopBackOff` will disappear.
