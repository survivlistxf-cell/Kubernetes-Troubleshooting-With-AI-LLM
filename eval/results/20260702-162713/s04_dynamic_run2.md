# s04 | mode=dynamic | run=2 | 12.4s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

**Most likely reasons why a pod that is “Running” is still painfully slow or unresponsive**  
(ordered from the cause that typically explains the problem most often to the least common)

---

### 1. **CPU throttling / insufficient CPU quota**
*Why it happens* – The pod’s containers have a **CPU limit** that is lower than the workload’s actual demand. The Linux kernel will then throttle the container’s CPU cycles, making the application appear hung even though the pod status is *Running*.

*How to verify*  
- Look at the pod’s **resource requests/limits** (CPU) in the pod spec.  
- Check the node’s **CPU usage** and the pod’s **CPU throttling metrics** (e.g., `cpu_throttled_seconds_total` from cAdvisor/metrics‑server).  
- Examine the pod’s **events** for messages such as “CPUQuotaExceeded” or “throttling”.

*How to fix*  
- Raise the **CPU limit** (or remove it) so the container can use more cores.  
- If the node itself is saturated, add more nodes or move the pod to a less‑loaded node (e.g., via a new pod spec with a different node selector or taint/toleration).  

---

### 2. **Memory pressure / OOM‑related throttling**
*Why it happens* – The pod is hitting its **memory limit** or the node is under memory pressure. When a container exceeds its limit, the kernel may start swapping (if enabled) or the kubelet may evict other pods, both of which degrade performance dramatically.

*How to verify*  
- Inspect the pod’s **memory requests/limits**.  
- Look at **memory usage** metrics (`container_memory_working_set_bytes`, `container_memory_rss`).  
- Check node‑level **memory pressure** events (`MemoryPressure` condition) and pod events for “OOMKilled” or “memory throttling”.

*How to fix*  
- Increase the pod’s **memory request/limit**.  
- If the node is out of memory, scale the cluster (add nodes) or move the pod to a node with more free RAM.  

---

### 3. **I/O bottlenecks (disk or network)**
*Why it happens* – The application may be waiting on slow persistent‑volume reads/writes, or on network calls to other services (e.g., external APIs, internal services) that are experiencing latency.

*How to verify*  
- Review **volume mount** types and check if the underlying storage class is provisioned with low IOPS or high latency.  
- Look at **network latency** metrics (e.g., `container_network_receive_bytes_total`, `container_network_transmit_bytes_total`) and any “NetworkUnavailable” node conditions.  
- Examine the application logs for timeouts or long‑running I/O calls.

*How to fix*  
- Switch to a higher‑performance storage class (more IOPS, SSD).  
- If the issue is external network latency, consider caching, retry logic, or moving the service closer (e.g., same zone).  

---

### 4. **Mis‑configured liveness/readiness probes causing constant restarts**
*Why it happens* – Even though the pod shows as *Running*, frequent liveness‑probe failures can cause the container to restart repeatedly, leading to a “warm‑up” period where the app appears sluggish.

*How to verify*  
- Check the pod’s **probe definitions** and recent **event timestamps** for “Liveness probe failed”.  
- Look at the container’s **restart count**; a high count despite “Running” status is a red flag.

*How to fix*  
- Adjust the probe **initialDelaySeconds**, **periodSeconds**, and **failureThreshold** to give the app enough time to start.  
- Ensure the probe endpoint actually reflects the app’s health (e.g., not a heavy DB query).  

---

### 5. **Application‑level issues (e.g., thread deadlock, GC pauses, mis‑configured thread pools)**
*Why it happens* – The code itself may be stuck in a long‑running loop, waiting on a lock, or suffering from excessive garbage‑collection pauses, which are invisible to Kubernetes but manifest as slowness.

*How to verify*  
- Pull the **container logs** (or ask the developer to attach a debugger) and look for stack traces, GC logs, or repeated error messages.  
- If possible, run a **profile** (e.g., `jstack` for Java, `pprof` for Go) inside a copy of the pod using `kubectl debug` (when you regain access).

*How to fix*  
- Fix the code (resolve deadlocks, tune GC, adjust thread‑pool sizes).  
- Deploy a new image with the corrected configuration.  

---

### 6. **Node‑level problems (e.g., kubelet or kernel issues, high system load)**
*Why it happens* – The node hosting the pod may be suffering from high **system load**, kernel throttling, or a misbehaving kubelet, which can affect all pods on that node.

*How to verify*  
- Check the node’s **CPU/Memory/Load** metrics and **system events** (`kubectl describe node`).  
- Look for node conditions such as **DiskPressure**, **MemoryPressure**, or **PIDPressure**.

*How to fix*  
- Drain the node and let the pod be rescheduled onto a healthy node.  
- If the node is consistently problematic, replace it or investigate the underlying OS issue.  

---

### 7. **Resource quota or limit‑range enforcement at the namespace level**
*Why it happens* – The namespace may have a **ResourceQuota** that caps total CPU or memory, causing the pod’s effective limits to be lower than intended.

*How to verify*  
- Review the namespace’s **ResourceQuota** objects and compare them with the pod’s requests/limits.  
- Look for events indicating “quota exceeded”.

*How to fix*  
- Increase or adjust the **ResourceQuota** for the namespace, or move the pod to a different namespace with higher limits.  

---

## How to verify each cause when you regain cluster access
1. **Describe the pod** – shows events, resource specs, and any throttling messages.  
2. **Get pod metrics** – via `metrics-server` or Prometheus to see CPU, memory, and throttling counters.  
3. **Inspect node status** – node conditions and resource usage.  
4. **Check logs** – both container logs and kubelet/system logs for errors or warnings.  
5. **Review events** – cluster‑wide events (`kubectl get events -A`) for clues about resource pressure or failures.  

## Quick remediation checklist (to run once you’re back in the cluster)

- **If CPU throttling is observed** → raise CPU limits or move to a less‑loaded node.  
- **If memory pressure is observed** → increase memory limits or add capacity.  
- **If I/O latency is the bottleneck** → upgrade storage class or improve network routing.  
- **If probes are mis‑configured** → adjust probe timings and thresholds.  
- **If application code is at fault** → patch the image and redeploy.  
- **If the node is unhealthy** → cordon/drain and let the pod land on a healthy node.  
- **If namespace quotas are restrictive** → request a quota increase or relocate the workload.  

By following this ordered list and the associated verification steps, you should be able to pinpoint the root cause of the slowness and apply the appropriate fix once you have access to the cluster again.
