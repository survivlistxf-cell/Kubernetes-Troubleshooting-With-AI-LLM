# s04 | mode=none | run=1 | 18.3s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons a “Running” pod is still painfully slow  

Below the causes are ordered from the problems that most often produce the symptom you described to the ones that are less common.  For each cause I note **how to confirm it** (what you would look at once you have cluster access) and **what to do to fix it**.

---

### 1. CPU throttling / insufficient CPU request‑limit balance  
**Why it matters** – If the pod’s containers are hitting their CPU *limit* (or the node is over‑committed), the kernel will throttle the CFS quota and the application will run at a fraction of its normal speed.  

**How to verify**  

- `kubectl top pod s04-cpu-throttle -n kubexplain-eval` – check the **CPU %** column. Values near 100 % while the limit is low indicate throttling.  
- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – look for the **“CPUThrottlingHigh”** event (kubelet may emit it) or a line in the *Containers* section that shows *CPU Requests / Limits*.  
- Node‑level: `kubectl top node <node>` – if the node’s CPU usage is > 80 % the pod may be competing with many others.  

**Fix**  

- Raise the **CPU request** so the scheduler places the pod on a node with enough capacity.  
- Raise the **CPU limit** (or remove it) if the app legitimately needs more CPU bursts.  
- If the node is saturated, consider moving the pod to a less‑loaded node (e.g., by adding a node selector, taint/toleration, or using a higher‑performance node pool).  

---

### 2. Memory pressure / OOM‑related slowdowns  
**Why it matters** – When a container is close to its memory *limit*, the kernel may start swapping (if the node allows it) or the kubelet may trigger OOM‑kill throttling, both of which dramatically degrade latency.  

**How to verify**  

- `kubectl top pod s04-cpu-throttle -n kubexplain-eval` – check the **MEM %** column. Values > 80 % are a red flag.  
- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – look for **“OOMKilled”** events or *MemoryPressure* conditions on the node.  
- Examine the container logs for “Out of memory” messages or GC pauses.  

**Fix**  

- Increase the **memory request** (guarantees allocation) and/or **memory limit**.  
- Tune the application’s heap / cache size to stay comfortably below the limit.  
- If the node itself is under memory pressure, add more nodes or use larger instance types.  

---

### 3. Network latency or DNS resolution problems  
**Why it matters** – Many apps spend a lot of time calling external services, databases, or internal APIs. A broken Service, mis‑routed traffic, or DNS failures can make the pod appear “slow” even though the container is otherwise healthy.  

**How to verify**  

- `kubectl exec -it s04-cpu-throttle -n kubexplain-eval -- curl -s -o /dev/null -w "%{time_total}\n" http://<service>` – measure round‑trip time to the target service.  
- `kubectl exec ... -- nslookup <service>` – see if DNS resolves quickly.  
- Look at the pod’s **Events** for “Failed to resolve” or “NetworkPolicy denies”.  
- Check the **NetworkPolicy** objects that select the pod’s namespace; a restrictive policy could be dropping traffic.  

**Fix**  

- Ensure the Service/Endpoint exists and points to healthy pods.  
- Adjust or add a **NetworkPolicy** to allow required traffic.  
- If DNS is slow, verify the CoreDNS pods are healthy (`kubectl get pods -n kube-system -l k8s-app=core-dns`).  
- Consider adding a **hostNetwork** or **dnsPolicy: ClusterFirstWithHostNet** as a temporary test.  

---

### 4. Disk I/O bottlenecks (logs, volume mounts, database files)  
**Why it matters** – Heavy write‑heavy workloads on a slow PersistentVolume (e.g., standard HDD, NFS) can block the process, especially if the app flushes logs or writes checkpoints frequently.  

**How to verify**  

- `kubectl exec ... -- iostat -dx 1 5` (or `cat /proc/diskstats`) to see high **await** or **%util** on the device.  
- Check the PVC’s **StorageClass** – is it a low‑performance tier?  
- Look at the pod’s **Events** for “VolumeMountFailed” or “FileSystemFull”.  

**Fix**  

- Move the workload to a faster storage class (SSD, provisioned IOPS).  
- Reduce log verbosity or rotate logs more aggressively.  
- If possible, move the write‑heavy component to a sidecar with its own volume.  

---

### 5. Application‑level issues (GC pauses, thread deadlocks, misconfiguration)  
**Why it matters** – Even with ample resources, a bug in the code (e.g., Java GC thrashing, Go runtime deadlock, mis‑configured thread pool) can make the service appear hung.  

**How to verify**  

- Pull the container logs (`kubectl logs s04-cpu-throttle -n kubexplain-eval`) and look for long GC pauses, “deadlock” stack traces, or repeated error messages.  
- If the app exposes metrics (Prometheus, /metrics endpoint), check latency histograms, GC pause metrics, thread counts.  
- Attach a debugger or dump a thread stack (`kubectl exec ... -- jstack <pid>` for Java, `go tool pprof -http=:8080 http://localhost:6060/debug/pprof/goroutine?debug=2` for Go).  

**Fix**  

- Tune the runtime (e.g., increase Java heap, adjust GC flags, raise Go GOMAXPROCS).  
- Fix the code bug (deadlock, infinite loop).  
- Apply the correct configuration (environment variables, config maps) that the app expects.  

---

### 6. Node‑level health problems (kubelet, kernel, hardware)  
**Why it matters** – If the underlying node is under heavy load, has kernel throttling, or is experiencing hardware issues, all pods on it can suffer.  

**How to verify**  

- `kubectl describe node <node>` – check **Conditions** (Ready, MemoryPressure, DiskPressure, PIDPressure).  
- `kubectl top node <node>` – high CPU or memory usage beyond capacity.  
- Look at the node’s system logs (`journalctl -u kubelet` on the node) for kernel OOM or hardware errors.  

**Fix**  

- Drain the node (`kubectl drain <node> --ignore-daemonsets`) and let the pod be rescheduled on a healthy node.  
- Replace the node if hardware is faulty.  
- Adjust cluster autoscaling to add capacity when nodes become saturated.  

---

### 7. Mis‑configured readiness/liveness probes causing frequent restarts or traffic drops  
**Why it matters** – If a probe is too aggressive, the pod may be killed and restarted repeatedly, or the Service may consider the endpoint unhealthy, sending traffic to a non‑ready pod.  

**How to verify**  

- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – look for **Readiness probe failed** or **Liveness probe failed** events.  
- Check the pod’s **restart count** (`kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].restartCount}'`).  

**Fix**  

- Adjust the probe’s `initialDelaySeconds`, `periodSeconds`, `failureThreshold`, or the command/HTTP path itself.  
- Temporarily disable the probe to confirm it’s the cause, then re‑enable with proper thresholds.  

---

### 8. Security policies (PodSecurityPolicy, Seccomp, AppArmor) restricting syscalls or resources  
**Why it matters** – A restrictive policy can block system calls the app needs (e.g., `ptrace`, `setuid`), leading to slow fallbacks or errors.  

**How to verify**  

- Review the pod’s **SecurityContext** and any **PodSecurityPolicy** that applies.  
- Look for events like **“Forbidden: unable to ...”** in the pod description.  

**Fix**  

- Relax the policy (add needed `allowedCapabilities`, `seccompProfile`, or `runAsUser`).  
- Update the pod spec to request the required privileges.  

---

### 9. Incorrect ConfigMap / Secret data (wrong URLs, credentials)  
**Why it matters** – If the app receives a bad endpoint or expired credential, it may keep retrying and appear sluggish.  

**How to verify**  

- `kubectl get configmap <name> -n kubexplain-eval -o yaml` and `kubectl get secret <name> -n kubexplain-eval -o yaml` – confirm values.  
- Check the application logs for authentication failures or “cannot resolve host” messages.  

**Fix**  

- Update the ConfigMap/Secret with the correct data and roll the pod (e.g., `kubectl rollout restart deployment/<name>`).  

---

### 10. Sidecar or init‑container contention  
**Why it matters** – A sidecar that performs heavy logging, metrics collection, or data sync can consume CPU/IO, starving the main container.  

**How to verify**  

- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – see the list of containers and their resource usage (`kubectl top pod` shows per‑container breakdown).  
- Look at sidecar logs for errors or high activity.  

**Fix**  

- Reduce the sidecar’s resource requests/limits, or move its workload to a separate pod.  
- Disable the sidecar temporarily to see if performance improves.  

---

## How to proceed once you regain cluster access  

1. **Collect baseline metrics** – `kubectl top pod` and `kubectl top node`.  
2. **Inspect events & description** – `kubectl describe pod …` for any warnings.  
3. **Check logs** – `kubectl logs …` (both main container and sidecars).  
4. **Validate configuration** – ConfigMaps, Secrets, Service definitions, NetworkPolicies.  
5. **Iterate** – Fix the highest‑probability cause first (usually CPU throttling or memory pressure), then re‑measure.  

By following the verification steps in order, you’ll quickly pinpoint which of the above factors is the root cause and apply the appropriate remediation. Good luck!
