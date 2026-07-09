# s04 | mode=static | run=1 | 12.5s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons why a running pod is “slow” or unresponsive  
*(ordered by how often they cause this symptom)*  

### 1. CPU throttling / CPU limits too low  
**Why it matters** – If the container’s CPU request/limit is far below what the application needs, the kubelet will throttle the cgroup and the process will run at a reduced speed, showing up as “slow”.  

**How to verify**  
- `kubectl top pod s04-cpu-throttle -n kubexplain-eval` – check the **CPU %** and **CPU (cores)** used vs. the limit.  
- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – look for events such as *“CPUThrottlingHigh”* or *“Container was throttled”*.  
- Inside the container, run `cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us` and `cpu.cfs_period_us` to see the effective limit.  

**Fix**  
- Raise the CPU **request** (for scheduling) and **limit** (for execution) in the pod spec, e.g.:

```yaml
resources:
  requests:
    cpu: "500m"
  limits:
    cpu: "1500m"
```

- Re‑apply the manifest or edit the deployment (`kubectl edit deployment …`).  
- If the workload is bursty, consider using the **Burstable** QoS class (set a request lower than the limit) or enable **CPU manager** static policy on the node.

---

### 2. Memory pressure / OOM‑related throttling  
**Why it matters** – When a container approaches its memory limit, the kernel may start swapping (if enabled) or the pod may be evicted/restarted. Even before OOM, the kernel can slow down memory allocation, making the app appear sluggish.  

**How to verify**  
- `kubectl top pod s04-cpu-throttle -n kubexplain-eval` – check **MEM %** usage.  
- `kubectl describe pod …` – look for *“MemoryPressure”* events or *“OOMKilled”* in previous restarts.  
- Inside the container, inspect `/sys/fs/cgroup/memory/memory.usage_in_bytes` vs. `memory.limit_in_bytes`.  

**Fix**  
- Increase the memory **request** and **limit** in the pod spec.  
- If the node itself is under memory pressure, add more nodes or move the pod to a less‑loaded node (e.g., via pod anti‑affinity or node selector).  

---

### 3. Network latency / mis‑configured Service/Ingress  
**Why it matters** – The pod may be able to start, but if it spends most of its time waiting on network calls (to databases, APIs, or other services), the overall response time will be high.  

**How to verify**  
- Exec into the pod (`kubectl exec -it s04-cpu-throttle -n kubexplain-eval -- sh`) and run `curl -v <internal‑service>` or `nc -zv <host> <port>` to test connectivity and latency.  
- Check the pod’s **events** for *“Failed to resolve”* or *“NetworkPolicy denied”*.  
- Look at the **Service** and **EndpointSlice** objects for the pod’s backend (`kubectl get svc,ep -n kubexplain-eval`).  

**Fix**  
- Ensure the Service/Endpoints are correct and that any NetworkPolicy allows the required traffic.  
- Verify DNS (`/etc/resolv.conf`) inside the container; fix any mis‑configured `clusterDomain` or `dnsPolicy`.  
- If the external dependency is slow, consider caching, connection pooling, or moving the dependency closer (e.g., same zone).  

---

### 4. Application‑level resource limits (e.g., thread pool, connection pool)  
**Why it matters** – The container may have enough CPU/memory, but the application itself could be configured with too‑small limits (e.g., Java heap, Go GOMAXPROCS, database connection pool).  

**How to verify**  
- Look at the container logs (`kubectl logs s04-cpu-throttle -n kubexplain-eval`) for warnings like *“pool exhausted”* or *“GC overhead limit exceeded”*.  
- If the app exposes metrics (Prometheus, /metrics endpoint), check for high latency or queue lengths.  

**Fix**  
- Adjust the application’s configuration (environment variables, config files, command‑line flags) to increase thread/connection pools, heap size, etc.  
- Redeploy with the updated settings.  

---

### 5. Disk I/O bottleneck (slow storage, high latency)  
**Why it matters** – Heavy read/write to a slow PersistentVolume (e.g., standard HDD, NFS) can block the process, making the whole pod feel unresponsive.  

**How to verify**  
- Inside the pod, run `iostat -x 1 3` or `dd if=/dev/zero of=/data/test bs=1M count=1024 oflag=direct` to measure write speed.  
- Check node‑level metrics (`kubectl top node`) for high **disk IO** usage.  

**Fix**  
- Move the workload to faster storage (SSD, gp2, etc.).  
- Add a sidecar cache or use in‑memory data structures to reduce disk access.  

---

### 6. Node‑level issues (CPU pressure, noisy neighbor, kernel throttling)  
**Why it matters** – Even if the pod’s limits are generous, the underlying node may be overloaded, causing all pods on it to suffer.  

**How to verify**  
- `kubectl top node` – look for high **CPU%**, **Memory%**, or **Pod count** on the node where the pod is scheduled (`kubectl get pod s04-cpu-throttle -n kubexplain-eval -o wide`).  
- Check node events (`kubectl describe node <node-name>`) for *“NodeHasSufficientMemory”* or *“NodeHasSufficientPID”* warnings.  

**Fix**  
- Drain the node and let the pod be rescheduled on a healthier node (`kubectl drain <node> --ignore-daemonsets`).  
- Add more nodes or rebalance workloads with pod anti‑affinity or taints/tolerations.  

---

## Quick verification checklist (run when you regain access)

1. **Resource usage** – `kubectl top pod …` and `kubectl describe pod …` for throttling events.  
2. **Logs** – `kubectl logs …` for application‑level warnings.  
3. **Network** – exec into the pod and test internal/external endpoints.  
4. **Node health** – `kubectl top node` and `kubectl describe node …`.  
5. **Storage** – run I/O tests inside the container if a volume is mounted.  

Address the first cause that shows a clear problem; most “slow but running” cases are resolved by increasing CPU limits or fixing throttling. If none of the above surface an issue, dive deeper into application‑specific profiling (e.g., Java Flight Recorder, pprof) once you have cluster access.
