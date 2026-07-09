# s03 | mode=dynamic | run=2 | 16.6s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

**Most likely reasons why a pod ends up in `OOMKilled` (in order of probability)**  

| # | Possible cause | Why it is common | How you can verify it (when you regain access) | Typical fix |
|---|----------------|------------------|-----------------------------------------------|-------------|
| 1 | **Container exceeded its memory *limit*** (the limit set in the pod spec is lower than the amount the process actually needs) | The kubelet enforces the `limits.memory` value; when the container’s RSS goes above that value the kernel OOM killer terminates the process and the pod status is reported as `OOMKilled`. | ```bash\nkubectl describe pod s03-oomkilled -n kubexplain-eval\n``` Look for a line like `State: Terminated` → `Reason: OOMKilled`.  In the same output, under **Containers** you will see the `Limits:` and `Requests:` that were applied.  You can also view the container’s memory usage just before the kill with `kubectl logs --previous …` (if the app logs memory usage) or by checking metrics (Prometheus, `container_memory_working_set_bytes`). | Raise the memory **limit** (and usually the **request**) to a value that comfortably covers the peak usage, or modify the application to use less memory.  Example: change `resources.limits.memory: "256Mi"` → `"512Mi"` and re‑apply the manifest. |
| 2 | **Node‑level memory pressure** – the node itself ran out of RAM and the kernel OOM killer chose the pod’s container to kill | Even if the pod’s limit is high enough, the node’s total memory may be exhausted by other workloads, causing the kernel to kill any cgroup that exceeds its share. The pod will still show `OOMKilled`. | ```bash\nkubectl describe node <node‑name>\n``` Check the **Events** section for messages like `Node <node> memory pressure` or `Node <node> evicted pod ... due to memory`.  You can also look at the node’s `/var/log/kubelet.log` or `journalctl -u kubelet` for `memory pressure` entries. | Reduce overall memory consumption on the node (delete or scale down other pods, lower their limits), add more nodes to the cluster, or enable **cluster autoscaling** so the scheduler can place memory‑heavy pods on a fresh node. |
| 3 | **Missing or too‑low *request* value** – the pod was scheduled onto a node that could not actually satisfy its real memory needs | If `requests.memory` is omitted or set far below the real usage, the scheduler may place the pod on a node that later becomes over‑committed. When the container spikes, the node runs out of memory and the pod is killed. | In the pod description (`kubectl describe pod …`) compare `Requests:` vs `Limits:`.  If `Requests` is `0` or far smaller than the observed usage (from logs/metrics), that’s a red flag. | Set a realistic **request** that reflects the typical memory consumption.  The request should be ≤ limit but high enough to keep the pod on a node with sufficient free memory. |
| 4 | **Application memory leak** – the process gradually consumes more RAM until it hits the limit | A leak can make a pod that initially runs fine eventually hit the limit on every restart, leading to a rapid crash‑loop. | Look at the container’s logs (`kubectl logs s03-oomkilled -n kubexplain-eval --previous`) for patterns of increasing memory usage, or query a monitoring system for a rising `container_memory_working_set_bytes` over time. | Fix the leak in the application code, or add a **sidecar** that restarts the container periodically (not ideal).  After fixing, redeploy with the same or higher limits. |
| 5 | **Incorrect `limitRange` or `resourceQuota` enforcement** – the namespace imposes a hard ceiling lower than what the pod spec requests | If a `LimitRange` caps memory at, say, `128Mi`, but the pod spec asks for `256Mi`, the API server will silently truncate the request/limit, causing the pod to run with a lower limit than you expect. | ```bash\nkubectl get limitrange -n kubexplain-eval -o yaml\nkubectl get resourcequota -n kubexplain-eval -o yaml\n``` See whether a `max.memory` or `hard.memory` value is lower than the pod’s spec. | Adjust or remove the offending `LimitRange`/`ResourceQuota`, or modify the pod spec to stay within the allowed ceiling. |
| 6 | **Pod is part of a DaemonSet/StatefulSet that uses `hostPort` or `hostNetwork`** – limiting the number of nodes it can run on, causing contention | When many pods compete for the same hostPort or share the host network, the scheduler may place them on a node that is already memory‑tight, increasing the chance of OOM. | In the pod spec (`kubectl get pod s03-oomkilled -o yaml`) look for `hostPort:` or `hostNetwork: true`.  Then check how many other pods use the same port (`kubectl get pods -A -o wide | grep <port>`). | Remove the `hostPort`/`hostNetwork` usage if possible, or spread the workload across more nodes (increase replica count, use anti‑affinity rules). |
| 7 | **Kernel OOM killer on the node due to non‑Kubernetes processes** (e.g., system daemons, logging agents) | Anything running on the node consumes RAM; if a non‑container process spikes, the kernel may kill a container even if its own limit isn’t reached. | SSH to the node (or view `journalctl -k`) and search for `Out of memory: Kill process`.  The log will list the PID and the cgroup (often the container ID). | Clean up or limit the memory usage of host‑level processes, or move them to separate nodes (e.g., dedicated logging nodes). |

---

### How to verify each cause (once you can run `kubectl` again)

1. **Check the pod’s termination reason**  
   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state.terminated.reason}'
   ```
   If it returns `OOMKilled`, you know the kernel killed the container.

2. **Inspect the pod description for limits, requests, and events**  
   ```bash
   kubectl describe pod s03-oomkilled -n kubexplain-eval
   ```
   - Look at **Limits/Requests** under *Containers*.  
   - Look at the **Events** section for messages like “Killing container … OOMKilled”.

3. **Look at node health**  
   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -o jsonpath='{.spec.nodeName}'
   kubectl describe node <node-name>
   ```
   - Events such as `MemoryPressure` indicate node‑level OOM.

4. **Check recent logs (previous instance) for memory‑related messages**  
   ```bash
   kubectl logs s03-oomkilled -n kubexplain-eval --previous
   ```

5. **Query metrics (if you have Prometheus, Metrics Server, etc.)**  
   - `container_memory_working_set_bytes{pod="s03-oomkilled"}` – see the peak usage.  
   - `node_memory_MemAvailable_bytes{node="<node-name>"}` – see node‑wide free memory.

6. **Validate namespace policies**  
   ```bash
   kubectl get limitrange -n kubexplain-eval -o yaml
   kubectl get resourcequota -n kubexplain-eval -o yaml
   ```

7. **Identify host‑level OOM events** (requires node SSH)  
   ```bash
   journalctl -k | grep -i "out of memory"
   ```

---

### General remediation checklist

1. **Raise the container’s memory limit** (and request) to a safe margin above the observed peak.  
2. **Add or adjust node capacity** – scale the cluster, enable autoscaling, or move other memory‑heavy workloads.  
3. **Set realistic requests** so the scheduler places the pod on a node with enough free RAM.  
4. **Fix any application memory leaks**; add monitoring to catch them early.  
5. **Review and adjust LimitRanges / ResourceQuotas** that may be unintentionally capping memory.  
6. **Remove unnecessary `hostPort`/`hostNetwork` usage** that forces placement on a limited set of nodes.  
7. **Clean up host‑level processes** that consume large amounts of RAM, or isolate them on dedicated nodes.

By walking through the verification steps in the order above, you’ll quickly pinpoint whether the OOM kills are caused by the pod’s own limits, node‑wide pressure, mis‑configured requests, or external factors, and you can apply the corresponding fix. Good luck when you regain access to the cluster!
