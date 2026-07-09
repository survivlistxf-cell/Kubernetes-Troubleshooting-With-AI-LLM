# s04 | mode=dynamic | run=2 | 7.1s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

**Root cause**

The container is running a tight infinite loop (`while true; do :; done`).  
Because the pod has a **CPU limit of only 25 mCPU** (≈ 2.5 % of a single core) the kubelet’s cgroup throttles the process aggressively. The loop constantly tries to consume CPU, but the limit forces the kernel to pause it many times per second, so the “application” appears extremely slow and unresponsive.

The pod’s QoS class is **Burstable** (request = 10 m, limit = 25 m). With such a low limit the kernel will hit the *cpu.cfs_quota* throttle almost immediately, which is exactly what the “cpu‑throttle” scenario is meant to illustrate.

**How to fix it**

1. **Raise the CPU limit (and optionally the request) to a value that matches the workload’s needs.**  
   For a busy‑loop test you can give it a full core or more, e.g.:

   ```yaml
   resources:
     requests:
       cpu: "200m"      # 0.2 core – guarantees this amount
     limits:
       cpu: "500m"      # 0.5 core – maximum the container may use
   ```

   Or, if you want the container to run unrestricted, remove the `limits.cpu` field entirely (the pod will then be in the **BestEffort** QoS class and will not be throttled by a cgroup quota).

2. **Apply the change** – edit the manifest (or the owning Deployment/Pod spec) and re‑apply:

   ```bash
   kubectl edit pod s04-cpu-throttle -n kubexplain-eval
   # or, if it’s managed by a Deployment:
   kubectl edit deployment <name> -n kubexplain-eval
   ```

   After the pod is recreated with the new limits, the loop will be able to consume the allotted CPU and the container will run at full speed.

3. **Verify the fix** – once the pod is running again, check its CPU usage:

   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   ```

   You should see a CPU usage close to the limit you set (e.g., ~400‑500 mCPU) and the loop will no longer be throttled.

**Why the limit matters**

Kubernetes enforces CPU limits via the Linux CFS scheduler. When a container’s CPU consumption exceeds its `cpu` limit, the scheduler inserts *throttle* periods, during which the process is paused. A limit of 25 mCPU means the container can only run for 25 ms out of every 100 ms; a busy loop that tries to run continuously will be paused ~75 % of the time, which makes it appear “slow”.

**Summary**

- The pod is throttled because its CPU limit (25 m) is far too low for a continuously running loop.  
- Increase the `cpu` request/limit (or remove the limit) and redeploy the pod.  
- Verify with `kubectl top pod` that the container now receives enough CPU and the application becomes responsive.
