# s04 | mode=none | run=3 | 18.2s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons a “Running” pod is still painfully slow  

Below the causes are ordered from **most common** to **least common** for a pod that appears healthy but the workload inside is unresponsive.  For each cause I give:

* **What to look for** (the clues that point to the problem)  
* **How to verify it** (kubectl commands you can run when you regain access)  
* **Typical fix** (what you would change in the manifest or cluster)

---

### 1. CPU throttling because of tight **CPU limits**  

**Why it happens** – The pod is scheduled on a node with enough CPU, but the container’s `cpu` request/limit forces the Linux CFS scheduler to throttle the process. The pod stays “Running” but gets only a fraction of a core, making the app crawl.

**How to verify**  

```bash
# Show actual CPU usage vs. limit
kubectl top pod s04-cpu-throttle -n kubexplain-eval

# Inspect the pod’s resource spec
kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.spec.containers[*].resources}'
```

*If the usage is near the limit and the `cpu` limit is low (e.g., 100m), throttling is likely.*  

You can also look at the cgroup stats:

```bash
kubectl exec -n kubexplain-eval s04-cpu-throttle -- cat /sys/fs/cgroup/cpu/cpu.stat
```

The `throttled_time` field will be non‑zero if throttling is happening.

**Fix**  

* Raise the **CPU request** (to give the pod a higher QoS class) and/or the **CPU limit**.  
* If you want burstable behavior, set a request lower than the limit, or remove the limit entirely for a “Best‑Effort” pod (only if you can tolerate noisy neighbors).  
* Consider adding a **Horizontal Pod Autoscaler** so the workload can scale out when CPU demand spikes.

---

### 2. Memory pressure / **OOM** or excessive GC  

**Why it happens** – The container hits its memory limit, the kernel starts swapping (if swap is enabled) or the pod is repeatedly OOM‑killed and restarted. Even if the pod shows “Running”, the process may be constantly thrashing.

**How to verify**  

```bash
# Current memory usage vs. limit
kubectl top pod s04-cpu-throttle -n kubexplain-eval

# Look for OOM events
kubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -i OOM
```

Inside the container:

```bash
kubectl exec -n kubexplain-eval s04-cpu-throttle -- cat /sys/fs/cgroup/memory/memory.stat
```

High `pgfault`/`pgmajfault` counts or a non‑zero `oom_kill` counter indicate memory pressure.

**Fix**  

* Increase the **memory request/limit**.  
* Tune the application’s heap size or GC parameters (e.g., `-Xmx` for Java).  
* Add a **memory‑based HPA** or enable **vertical pod autoscaling**.

---

### 3. Node‑level resource saturation (CPU, memory, disk I/O)  

**Why it happens** – The pod may be on a node that is already heavily loaded. Even with generous pod limits, the node cannot deliver the requested cycles or I/O bandwidth.

**How to verify**  

```bash
kubectl top node $(kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
```

*Look for high CPU% or memory% on the node.*  

For disk I/O you can inspect node metrics (if you have metrics‑server or Prometheus) or SSH to the node and run `iostat`.

**Fix**  

* Add a **node selector**, **affinity**, or **taints/tolerations** to move the pod to a less‑loaded node.  
* Scale the node pool (add more nodes) or enable **cluster autoscaler**.  

---

### 4. Network latency or DNS resolution problems  

**Why it happens** – The application talks to external services (databases, APIs). Slow DNS lookups, blocked egress, or a congested network path can make the app appear hung.

**How to verify**  

```bash
# Test DNS resolution from inside the pod
kubectl exec -n kubexplain-eval s04-cpu-throttle -- nslookup <service-name>

# Test connectivity to a known endpoint
kubectl exec -n kubexplain-eval s04-cpu-throttle -- curl -s -o /dev/null -w "%{time_total}\n" http://<endpoint>
```

Check the pod’s events for `FailedMount` or `NetworkPolicy` denials.

**Fix**  

* Verify the **Cluster DNS** (`coredns`) pods are healthy.  
* Adjust **NetworkPolicy** rules if they are overly restrictive.  
* Use a **Service** (ClusterIP) instead of direct IPs to benefit from kube‑proxy load‑balancing.  

---

### 5. Mis‑configured **readiness/liveness probes** causing constant restarts  

**Why it happens** – The pod may be “Running” but the container is being killed and restarted repeatedly because a probe fails. The brief up‑time can make the app seem unresponsive.

**How to verify**  

```bash
kubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -i "Readiness" -A2
kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].restartCount}'
```

A high restart count or frequent `ProbeFailed` events point to this issue.

**Fix**  

* Tune the **initialDelaySeconds**, **periodSeconds**, **failureThreshold**, or the probe command itself.  
* If the probe is too strict, consider removing it temporarily to confirm the root cause.

---

### 6. Application‑level bottlenecks (thread deadlock, GC spikes, bad queries)  

**Why it happens** – The code itself may be stuck in a long GC pause, a deadlock, or a database query that is now slow (e.g., after a schema change).

**How to verify**  

* Pull the logs and look for GC logs, stack traces, or long‑running query messages.  

```bash
kubectl logs s04-cpu-throttle -n kubexplain-eval --tail=200
```

* If you can exec, dump a thread dump (Java: `jstack`, Python: `pstack`, Go: `kill -SIGQUIT`).

**Fix**  

* Optimize the offending code path, add indexes, or adjust GC flags.  
* Deploy a newer version of the container image with the fix.  

---

### 7. Persistent‑volume (PV) I/O bottlenecks  

**Why it happens** – The app reads/writes a lot of data and the underlying storage class is slow (e.g., standard HDD vs. SSD) or the volume is throttled.

**How to verify**  

```bash
kubectl exec -n kubexplain-eval s04-cpu-throttle -- iostat -dx 1 3
```

High `await` or `util` percentages indicate I/O saturation.

**Fix**  

* Switch to a faster **StorageClass** (e.g., `gp2` → `gp3` on AWS).  
* Add a **ReadWriteMany** volume if multiple pods need concurrent access.  

---

### 8. Sidecar or init‑container interfering  

**Why it happens** – A sidecar that proxies traffic, logs, or does security scanning may be mis‑behaving, adding latency.

**How to verify**  

* Check the sidecar container’s logs and resource usage.  

```bash
kubectl logs s04-cpu-throttle -c <sidecar-name> -n kubexplain-eval
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```

**Fix**  

* Update or restart the sidecar image.  
* Temporarily remove the sidecar to see if performance improves.  

---

### 9. Security policies (PodSecurityPolicy, AppArmor, seccomp) limiting syscalls  

**Why it happens** – The container may be blocked from performing needed syscalls (e.g., `ptrace`, `setuid`), causing the app to fall back to slower code paths.

**How to verify**  

* Look for `Forbidden` events in `kubectl describe pod`.  
* Check the pod’s `securityContext` and any associated PSP/OPA policies.

**Fix**  

* Adjust the **PodSecurityPolicy** or **SecurityContext** to allow the required capabilities.  

---

### 10. ConfigMap/Secret not mounted or outdated  

**Why it happens** – The app reads configuration at start‑up; a missing or stale ConfigMap can make it fall back to default (often a very slow mode).

**How to verify**  

```bash
kubectl exec -n kubexplain-eval s04-cpu-throttle -- cat /etc/config/<file>
kubectl describe configmap <name> -n kubexplain-eval
```

**Fix**  

* Update the ConfigMap/Secret and **roll the pod** (`kubectl rollout restart deployment …`).  

---

## Quick checklist you can run when you get back to the cluster  

1. `kubectl top pod …` – CPU & memory usage.  
2. `kubectl describe pod …` – look for events (OOM, throttling, probe failures).  
3. `kubectl logs …` – application logs for errors or GC pauses.  
4. `kubectl exec … -- cat /sys/fs/cgroup/*/cpu.stat` – throttling counters.  
5. `kubectl exec … -- iostat` – disk I/O health.  
6. `kubectl exec … -- nslookup / curl` – network/DNS sanity.  

Address the first item that shows an abnormal value; in most cases the root cause will be **CPU throttling** or **memory pressure**. Adjust the resource requests/limits, redeploy, and monitor again. If those look fine, move down the list.  

---  

**Remember:** the pod’s “Running” status only guarantees that the container process is alive, not that it has the resources it needs to serve traffic efficiently. Checking the metrics and events is the fastest way to pinpoint the bottleneck. Good luck!
