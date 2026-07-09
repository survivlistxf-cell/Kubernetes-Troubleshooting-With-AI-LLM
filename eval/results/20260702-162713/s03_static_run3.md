# s03 | mode=static | run=3 | 15.3s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod ends up in **`OOMKilled`** and restarts  

| # | Probable cause (most → least likely) | Why it produces an OOM kill | How you can verify it (when you regain console access) | How to fix it |
|---|--------------------------------------|-----------------------------|--------------------------------------------------------|---------------|
| 1 | **Container memory limit is lower than the amount the workload actually needs** | The kubelet enforces the `memory` limit you set in the pod spec. When the process tries to allocate more, the kernel kills it with `OOMKilled`. | • Look at the pod’s **status** → **container state** → `reason: OOMKilled`. <br>• Inspect the pod spec (`spec.containers[].resources.limits.memory`). <br>• Compare the limit with the memory usage reported by metrics (e.g., `kubectl top pod …` or the metrics server). | • Raise the `memory` limit (and usually the `requests` to match). <br>• If you use a `LimitRange` or `ResourceQuota`, make sure the new values are allowed. |
| 2 | **Application has a memory leak or spikes beyond the limit under normal load** | Even with a reasonable limit, a buggy app can gradually consume more RAM until it hits the ceiling, triggering the kill. | • Review the container logs for signs of increasing memory usage or out‑of‑memory errors. <br>• Pull historical metrics (Prometheus, CloudWatch, etc.) to see a rising trend. <br>• Re‑run the workload locally with a memory‑profile tool (e.g., `go tool pprof`, `jmap`, `valgrind`). | • Fix the leak or reduce the workload’s memory footprint. <br>• If the leak cannot be eliminated quickly, temporarily increase the limit to give you time to patch. |
| 3 | **Node‑level memory pressure (node out of memory)** | When the node itself runs low on free RAM, the kernel’s OOM killer may select any pod (often the one with the highest RSS) and kill it, even if the pod’s own limit has not been exceeded. | • Check the **node events** for messages like “Node memory pressure” or “OutOfDisk”. <br>• Look at the node’s memory usage (`kubectl top node …`). <br>• Examine the kubelet logs for lines mentioning “eviction” or “memory pressure”. | • Add more worker nodes or increase the node size. <br>• Reduce overall memory consumption on the node (e.g., lower limits of other pods, delete unused workloads). <br>• Enable the **Node Autoscaler** if you are on a cloud provider. |
| 4 | **Requests are too low, causing the scheduler to place the pod on a node that cannot satisfy its real memory needs** | The scheduler only guarantees that the sum of *requests* fits on a node. If the request is far below the actual usage, the pod may be scheduled onto a node that later runs out of memory, leading to OOM kills. | • Verify the pod’s `resources.requests.memory`. <br>• Compare the request with the observed usage (metrics). <br>• Look at the node’s **allocatable** memory vs. the sum of all pod requests. | • Raise the `requests.memory` to a realistic value (usually equal to or slightly below the limit). <br>• Consider using **Vertical Pod Autoscaler** to keep requests in sync with usage. |
| 5 | **Other pods on the same node are causing memory pressure** | Even if your pod’s own limit is fine, a “noisy neighbor” that exceeds its own limit can push the node into OOM, and the kernel may kill any pod, often the one with the highest RSS. | • List all pods on the node and check which ones have high memory usage or recent OOM kills. <br>• Review node events for “eviction” messages that name the victim pod. | • Adjust limits/requests of the offending pods. <br>• Use **Pod Priority & Preemption** to protect critical workloads. |
| 6 | **Mis‑configured `oomScoreAdj` or `oomKillDisable` in the container runtime** | Advanced users can tweak the Linux OOM score; a low (more negative) score makes a container less likely to be killed, while a high score makes it a prime target. If set incorrectly, your pod may be chosen even when it isn’t the biggest consumer. | • Inspect the pod spec for `securityContext.oomScoreAdj`. <br>• Check the container runtime logs for any custom OOM‑adjust settings. | • Remove or set a more appropriate `oomScoreAdj` value (default is 0). |
| 7 | **Use of `hostPort` or `hostIPC` that limits scheduling options** | Binding to a specific host port reduces the number of nodes the pod can land on, increasing the chance it ends up on a memory‑constrained node. | • Look for `hostPort` or `hostIPC` in the pod spec. <br>• Verify that the node(s) that can host the pod have sufficient memory. | • Remove the host‑port binding if possible, or increase node capacity. |
| 8 | **System‑level OOM (kernel) triggered by non‑Kubernetes processes on the node** | Daemons, logging agents, or other host processes can consume RAM, pushing the node into OOM and causing the kernel to kill a container. | • Examine the node’s system logs (`/var/log/syslog`, `journalctl`) for generic OOM messages that do not mention a pod. <br>• Identify any non‑K8s processes with high memory usage. | • Tune or relocate those host processes, or increase node memory. |

---

### General verification workflow (when you can run commands)

1. **Confirm the OOM reason** – Look at the pod’s container status; the `reason` field will be `OOMKilled`.  
2. **Inspect resource limits/requests** – Review the pod spec for `resources.limits.memory` and `resources.requests.memory`.  
3. **Check recent events** – Events on the pod and on the node often contain messages like “Container was killed due to OOM” or “Node memory pressure”.  
4. **Gather memory usage data** – Use the metrics server (`kubectl top pod` / `kubectl top node`) or your monitoring stack (Prometheus, CloudWatch, etc.) to see actual consumption versus the limits.  
5. **Look at node health** – Node conditions (`Ready`, `MemoryPressure`) and node‑level events reveal whether the whole node is under pressure.  
6. **Review logs** – Container logs may show the application’s own out‑of‑memory messages before the kernel kills it.  
7. **Identify noisy neighbors** – List all pods on the same node and compare their memory usage; high‑usage pods are prime suspects for causing node‑wide pressure.  

---

### Typical remediation steps

| Situation | Action |
|-----------|--------|
| **Limit too low** | Increase `memory` limit (and usually `requests`) in the pod spec; redeploy. |
| **Memory leak / spikes** | Fix the application code, add proper garbage collection, or add a side‑car that restarts the container on high memory usage. |
| **Node memory pressure** | Scale the node pool, add larger nodes, or enable cluster autoscaler. |
| **Requests undersized** | Raise `resources.requests.memory` to reflect realistic usage; this also improves scheduling. |
| **Noisy neighbor** | Adjust that pod’s limits, move it to a different node, or give it a higher priority. |
| **Host‑level processes** | Reduce or relocate those processes, or increase node RAM. |
| **OOM score mis‑set** | Remove custom `oomScoreAdj` or set it to a neutral value. |
| **HostPort limiting placement** | Remove the hostPort if not required, or ensure nodes with that port have enough memory. |

---

### Quick “what to do next” checklist (once you have console access)

1. Pull the pod’s description and note the **OOMKilled** reason.  
2. Verify the **memory limit** and **request** values.  
3. Check **node conditions** for `MemoryPressure`.  
4. Review **node‑wide memory usage** and identify any other high‑memory pods.  
5. Correlate with **application logs** to see if the process itself reported an OOM before being killed.  
6. Apply the appropriate fix from the table above, then redeploy the pod (or update the deployment/daemonset).  

Following this systematic approach will usually pinpoint whether the problem is a mis‑sized limit, a genuine leak, or a broader node‑level resource shortage, and guide you to the right remediation. Good luck!
