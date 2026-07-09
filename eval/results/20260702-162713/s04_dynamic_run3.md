# s04 | mode=dynamic | run=3 | 11.5s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons why a running pod is slow or unresponsive  
*(ordered from highest to lowest probability)*  

### 1. CPU throttling / resource limits  
**Why it happens** – The pod may have a CPU **request** that is satisfied but a **limit** that is lower than what the application needs. When the container tries to use more CPU than its limit, the kernel throttles it, causing the app to run very slowly.  

**How to verify (when you can reach the cluster)**  
- Look at the pod’s `resources.limits.cpu` and `resources.requests.cpu`.  
- Check the node’s CPU usage and the pod’s cgroup statistics (`kubectl top pod …` or the node’s `/sys/fs/cgroup` files).  
- In the pod’s logs or metrics you may see messages like “CPU throttling” or “CPU quota exceeded”.  

**Fix**  
- Raise the CPU limit (or remove it) so the limit matches or exceeds the request.  
- If the workload is bursty, consider using the **Burstable** QoS class (set a request lower than the limit).  
- Optionally enable the **CPUManager** static policy for guaranteed workloads if you need strict isolation.  

---

### 2. Memory pressure / OOM‑kill or swapping  
**Why it happens** – The pod may be close to its memory limit, causing the kernel to reclaim pages or the container runtime to OOM‑kill processes. Even before a kill occurs, frequent page reclamation can make the app sluggish.  

**How to verify**  
- Inspect `resources.limits.memory` and `resources.requests.memory`.  
- Look at the pod’s `status.containerStatuses[].state.waiting.reason` for `OOMKilled`.  
- Check node‑level memory pressure events (`kubectl describe node …` → “MemoryPressure”).  

**Fix**  
- Increase the memory limit or request.  
- Tune the application’s heap/GC settings if it’s a Java process, etc.  
- If the node is under‑provisioned, add more nodes or larger instance types.  

---

### 3. Excessive I/O latency (disk or network)  
**Why it happens** – The container may be reading/writing to a slow PersistentVolume, or it may be waiting on a remote service (database, API) that is experiencing latency.  

**How to verify**  
- Check the pod’s volume type (e.g., `awsElasticBlockStore`, `gcePersistentDisk`, `hostPath`).  
- Look at metrics for disk I/O (`iostat`, `kubectl top pod` if enabled) or network latency to dependent services.  
- Review application logs for timeouts or long‑running I/O calls.  

**Fix**  
- Move to a faster storage class (SSD, provisioned IOPS).  
- Add caching layers or increase connection pool sizes.  
- Verify network policies or service endpoints are correct and not causing extra hops.  

---

### 4. Mis‑configured liveness/readiness probes causing restarts or throttling  
**Why it happens** – Aggressive probe intervals can cause the kubelet to repeatedly kill and restart containers, leading to a “warm‑up” period where the app appears slow.  

**How to verify**  
- Examine the pod spec for `livenessProbe` and `readinessProbe` settings (periodSeconds, timeoutSeconds, failureThreshold).  
- Look at the pod’s event list for frequent `Killing` or `Unhealthy` messages.  

**Fix**  
- Relax probe intervals and thresholds.  
- Ensure the probe endpoints are lightweight and return quickly.  

---

### 5. DNS resolution problems  
**Why it happens** – If the pod relies on DNS to reach internal services and the CoreDNS pods are overloaded or mis‑configured, each lookup adds seconds of latency, making the app feel unresponsive.  

**How to verify**  
- From inside the pod (when you can exec), run `nslookup` or `dig` for a known service and note the response time.  
- Check CoreDNS pod logs for errors or high CPU usage.  

**Fix**  
- Scale CoreDNS deployment.  
- Increase the `cache` size or adjust `forward` upstream settings.  
- Verify the `kube-dns` service IP is reachable from the pod’s network namespace.  

---

### 6. Application‑level bugs (e.g., deadlocks, inefficient algorithms)  
**Why it happens** – The container may be running correctly from Kubernetes’s perspective, but the code itself could be stuck in a loop, waiting on a lock, or performing expensive computations.  

**How to verify**  
- Collect a thread dump or profiling data (e.g., `jstack` for Java, `pstack` for Go).  
- Look at application logs for repeated error messages or long‑running request timestamps.  

**Fix**  
- Fix the code issue, add proper timeout handling, or optimize the algorithm.  
- Deploy a new image with the fix and monitor performance.  

---

### 7. Node‑level issues (e.g., kernel throttling, noisy neighbor)  
**Why it happens** – The underlying VM or physical host may be under heavy load from other workloads, causing contention for CPU, memory, or network bandwidth.  

**How to verify**  
- Inspect node metrics (`kubectl top node …`).  
- Check node events for `DiskPressure`, `MemoryPressure`, or `PIDPressure`.  

**Fix**  
- Evict other heavy pods, add more nodes, or move the pod to a less‑loaded node (using node selectors or taints/tolerations).  

---

## How to proceed when you regain cluster access  

1. **Gather basic pod status** – `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` and `kubectl get pod s04-cpu-throttle -n kubexplain-eval -o yaml`.  
2. **Check resource usage** – `kubectl top pod …` and node metrics.  
3. **Review events** – look for throttling, OOM, probe failures, or scheduling warnings.  
4. **Inspect logs** – both application logs and kubelet/container runtime logs for latency clues.  
5. **Run a temporary debug pod** (e.g., `kubectl debug …`) to exec into the node and run `iostat`, `netstat`, or DNS lookups.  
6. **Apply the most appropriate fix** from the list above, then redeploy the pod or adjust its spec.  

By following these verification steps in order of the likely causes, you should be able to pinpoint why the pod appears slow and remediate the issue efficiently.
