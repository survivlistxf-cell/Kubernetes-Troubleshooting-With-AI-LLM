# s03 | mode=static | run=2 | 12.2s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod ends up **OOMKilled** (in order of probability)

| # | Likely cause | Why it’s common | How to verify when you can get back to the cluster | How to fix it |
|---|--------------|----------------|---------------------------------------------------|---------------|
| 1 | **Container memory limit is too low for the workload** | The default limit is often left at a small value (e.g., 128 Mi) while the application needs more. When the container exceeds its cgroup limit the kernel kills it and the pod is restarted. | • Look at the pod’s `spec.containers[].resources.limits.memory` in the pod manifest (`kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml`).<br>• Check the pod’s status (`kubectl describe pod …`) – you’ll see `State: Terminated` with `Reason: OOMKilled` and the amount of memory that was requested/limited. | Raise the memory **limit** (and usually the **request**) to a value that comfortably covers the observed peak usage. If you use a Deployment, edit the manifest and apply it again. |
| 2 | **Application has a memory leak or spikes under load** | Even with a reasonable limit, a buggy process can keep allocating memory until it hits the cap. | • Pull the container logs (`kubectl logs …`) around the time of the kill – you may see warnings or out‑of‑memory errors from the app.<br>• If you have metrics (Prometheus, CloudWatch, etc.), chart the container’s memory usage over time to see a steady upward trend. | Fix the leak in the application code, or add defensive logic (e.g., limit cache size). If a leak cannot be fixed quickly, temporarily raise the limit to give you more time while you work on the code. |
| 3 | **Resource requests are too low, causing the pod to be scheduled on a node that cannot satisfy the limit** | The scheduler only looks at *requests* for placement. If the request is tiny but the limit is high, the pod may land on a node that later runs out of free memory, triggering the node‑level OOM killer. | • Inspect the pod spec for `resources.requests.memory`. <br>• Check the node’s memory pressure events (`kubectl describe node <node>`). Look for `MemoryPressure` conditions or `Eviction` events. | Increase the **request** to match the limit (or at least a realistic baseline). This forces the scheduler to place the pod on a node with enough allocatable memory. |
| 4 | **Node‑level memory pressure** (other pods on the same node consume most of the RAM) | When the node runs low on memory, the kubelet may invoke the kernel OOM killer on the highest‑priority container, which is often a pod with a low memory request. | • Examine node metrics (e.g., `kubectl top node` or node exporter) to see overall memory usage.<br>• Look at the node’s events for `MemoryPressure` or `Eviction` messages. | Add more nodes to the cluster or rebalance workloads (e.g., use pod anti‑affinity, adjust other pods’ requests). Consider enabling Cluster Autoscaler if you’re on a cloud provider. |
| 5 | **Kernel OOM killer on the node (system processes consume memory)** | System daemons (e.g., kube‑proxy, kubelet, OS services) can also exhaust RAM, causing the kernel to kill containers even if the pod’s own limit isn’t reached. | • Check the node’s system logs (`/var/log/syslog`, `journalctl -k`) for lines like “Out of memory: Kill process …”.<br>• Verify that the node’s OS has enough swap (if enabled) or that system services are properly sized. | Tune the node’s OS memory settings, reduce memory usage of system daemons, or increase node size. |
| 6 | **Side‑car or init‑container consumes unexpected memory** | A side‑car that runs a logging agent, monitoring daemon, or a heavy init‑container can push the pod over its limit even if the main container is fine. | • Review the full pod spec for any additional containers.<br>• Look at each container’s memory usage in metrics (if available) or logs that indicate high consumption. | Adjust the side‑car’s resource limits, move it to a separate pod, or replace it with a lighter alternative. |
| 7 | **Mis‑configured cgroup or runtime settings** (rare) | Occasionally the container runtime or the underlying cgroup driver can mis‑report memory, leading to premature OOM kills. | • Check the node’s kubelet configuration (`kubelet --help` or the config file) for the cgroup driver.<br>• Look for known bugs in the runtime version you’re using. | Upgrade the container runtime/kubelet to a version without the bug, or switch to a supported cgroup driver (e.g., `systemd`). |

---

### General verification checklist (run when you regain access)

1. **Pod status & events** – `kubectl describe pod s03-oomkilled -n kubexplain-eval`  
   *Look for `Reason: OOMKilled` and any preceding warnings.*

2. **Resource spec** – `kubectl get pod … -o yaml`  
   *Confirm `requests.memory` and `limits.memory` values.*

3. **Memory usage metrics** – `kubectl top pod …` or Prometheus dashboards.  
   *Identify whether the container consistently hits the limit.*

4. **Node health** – `kubectl describe node <node>` and `kubectl top node`.  
   *Check for `MemoryPressure` or eviction events.*

5. **System logs on the node** – `journalctl -k` or `/var/log/syslog`.  
   *Search for kernel OOM messages.*

6. **Side‑car / init‑container inspection** – review the full pod spec.  

---

### Typical remediation flow

1. **Raise limits & requests** to a safe margin (e.g., 1.5 × observed peak).  
2. **Fix any application memory leaks** identified in logs or profiling.  
3. **Adjust scheduling** by increasing requests so the pod lands on a node with enough free RAM.  
4. **Scale the cluster** (add nodes or enable autoscaling) if node‑level pressure is the bottleneck.  
5. **Tune or replace heavy side‑cars** if they are the culprit.  
6. **Upgrade node OS / runtime** if you discover a known bug.

Following this ordered checklist should let you pinpoint the root cause of the `s03-oomkilled` pod and apply the appropriate fix once you can reconnect to the cluster.
