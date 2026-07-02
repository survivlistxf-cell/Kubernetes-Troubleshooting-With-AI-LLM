# s04 | mode=static | run=2 | 8.7s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What the pod description tells us

| Field | Value |
|-------|-------|
| **Image** | `busybox:1.36` |
| **Command** | `while true; do :; done` (an infinite‑loop that does **nothing but spin**) |
| **CPU Requests / Limits** | `request: 10 m` `limit: 25 m` |
| **QoS Class** | `Burstable` |
| **Node** | `worker-1` |
| **Events** | Normal start‑up, no errors |

The container is **CPU‑bound** – it continuously executes a tight loop.  
Kubernetes enforces the **CPU limit** (`25 m` = 0.025 core). When a container tries to use more CPU than its quota, the kernel throttles it (cgroup‑cpu quota is hit). The result is exactly the behaviour you see: the process runs, but it is **throttled** so it makes almost no progress and appears “extremely slow and unresponsive”.

The pod name (`s04-cpu-throttle`) and the scenario label (`eval-scenario=s04`) also hint that this test is meant to demonstrate CPU throttling.

### How to confirm the throttling

1. **Check the pod’s CPU usage vs. limit**

   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   ```

   You’ll see a usage that is far below the limit (often 0 m) while the container is still running.

2. **Inspect cgroup throttling stats** (run inside the container)

   ```bash
   kubectl exec -n kubexplain-eval s04-cpu-throttle -c app -- \
     cat /sys/fs/cgroup/cpu.stat
   ```

   Look for a large `throttled_time` value – it grows while the kernel is limiting the container.

### Root cause

**CPU limit set to 25 m (0.025 core) for a CPU‑intensive workload.**  
The limit is far lower than what the busy‑loop needs, so the kernel throttles the container, making the application appear hung.

### Fixes

#### 1. Raise the CPU limit (and request) to a realistic value

Edit the pod (or the Deployment/Job that creates it) and set a higher limit, e.g. 200 m (0.2 core) or more, depending on how much CPU you want the workload to have.

```yaml
resources:
  requests:
    cpu: "100m"   # 0.1 core – what the scheduler will reserve
  limits:
    cpu: "200m"   # 0.2 core – maximum the container may use
```

Apply the change:

```bash
kubectl apply -f <your‑manifest>.yaml
```

or, if the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), patch it:

```bash
kubectl set resources deployment/<deployment-name> \
  -n kubexplain-eval \
  --containers=app \
  --requests=cpu=100m \
  --limits=cpu=200m
```

#### 2. Remove the CPU limit (optional)

If you don’t need to cap the CPU, simply omit the `limits.cpu` field. The pod will then run with **Best‑Effort** QoS (or **Burstable** if a request is still set) and will not be throttled by the kubelet.

```yaml
resources:
  requests:
    cpu: "10m"
  # no limits section
```

#### 3. Verify after the change

```bash
kubectl rollout restart deployment/<deployment-name> -n kubexplain-eval   # if using a Deployment
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```

You should now see the container using CPU up to the new limit, and the infinite loop will execute at full speed (the process will consume the CPU you allocated).

### Summary

- **Root cause:** CPU limit of 25 m is far too low for a CPU‑intensive busy‑loop, causing kernel throttling.
- **Fix:** Increase (or remove) the CPU limit/request to a value that matches the workload’s needs, then redeploy/restart the pod.
- **Verification:** `kubectl top pod` and `/sys/fs/cgroup/cpu.stat` will show the throttling disappear.
