# s03 | mode=none | run=1 | 15.6s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons why **`s03-oomkilled`** keeps being killed (ordered by probability)

| # | Likely cause | Why it’s common | How to verify (when you have cluster access) | Typical fix |
|---|--------------|----------------|---------------------------------------------|-------------|
| 1 | **Container exceeds its memory **`limit`**  (direct OOMKill)** | The kernel kills the cgroup as soon as the process tries to allocate more memory than the hard limit you set. This is the classic “OOMKilled” reason. | ```bash\nkubectl -n kubexplain-eval get pod s03-oomkilled -o jsonpath='{.status.containerStatuses[0].state.terminated.reason}'\nkubectl -n kubexplain-eval describe pod s03-oomkilled | grep -i oom\n``` | Raise the memory **limit** (and usually the **request**) to a value that matches the workload, or reduce the workload’s memory consumption. |
| 2 | **Application has a memory leak / spikes** | Even with a generous limit, a buggy app can keep allocating until it hits the cap. The symptom looks identical to #1, but the underlying problem is the code. | ```bash\nkubectl -n kubexplain-eval top pod s03-oomkilled --containers\n# or, if metrics‑server is not installed, inspect cgroup stats:\nkubectl -n kubexplain-eval exec s03-oomkilled -- cat /sys/fs/cgroup/memory/memory.usage_in_bytes\n``` | Fix the leak in the application code, or add periodic restarts (e.g., a `restartPolicy: OnFailure` with a low `backoffLimit`), or increase the limit enough to accommodate the peak while you work on the code. |
| 3 | **Memory **`request`** is too low, causing the pod to be scheduled on a node that cannot satisfy the limit** | The scheduler may place the pod on a node that appears to have enough free memory (based on requests) but actually cannot provide the limit when the container spikes, leading to OOMKill. | ```bash\nkubectl -n kubexplain-eval get pod s03-oomkilled -o yaml | grep -A3 resources:\nkubectl -n kubexplain-eval describe node <node‑name> | grep -i memory\n``` | Raise the **request** to a realistic baseline (usually equal to or slightly below the limit). This steers the scheduler to a node with enough capacity. |
| 4 | **Node‑level memory pressure / kernel OOM** | If the node itself is out of memory, the kernel may kill containers even if they are below their limits. The pod status still shows `OOMKilled`. | ```bash\nkubectl -n kubexplain-eval describe node <node‑name> | grep -i 'MemoryPressure'\nkubectl -n kubexplain-eval get events --field-selector involvedObject.kind=Node,involvedObject.name=<node‑name>\n``` | Add more nodes or increase node memory, enable cluster‑level autoscaling, or move the pod to a less‑loaded node (e.g., by deleting it so it is rescheduled). |
| 5 | **Side‑car or init container OOM‑killing the main container** | If a side‑car shares the same pod memory limit, its own OOM can bring down the whole pod. | ```bash\nkubectl -n kubexplain-eval get pod s03-oomkilled -o jsonpath='{.status.containerStatuses[*].name}:{.status.containerStatuses[*].state.terminated.reason}'\n``` | Give the side‑car its own memory limits, or move it to a separate pod if it has different resource needs. |
| 6 | **Incorrect limit range / policy enforcement** | A `LimitRange` or `ResourceQuota` may be capping the limit you think you set, causing the pod to run with a lower limit than intended. | ```bash\nkubectl -n kubexplain-eval get limitrange\nkubectl -n kubexplain-eval describe limitrange <name>\n``` | Adjust the `LimitRange` or explicitly set higher limits in the pod spec (within quota). |
| 7 | **HugePages or other special memory allocations exceeding limits** | Using `hugePages` or `memory`‑mapped files can consume memory outside the normal cgroup accounting, leading to unexpected OOM. | ```bash\nkubectl -n kubexplain-eval get pod s03-oomkilled -o yaml | grep -i hugepages\n``` | Remove or reduce hugepage usage, or increase the pod’s memory limit to cover the extra allocation. |
| 8 | **Kernel OOM on the node triggered by other workloads** | A different pod on the same node may have been killed, and the kernel chose your pod as the victim. | ```bash\nkubectl -n kubexplain-eval get events --field-selector reason=OOMKilled\n``` | Same as #4 – relieve node pressure, add capacity, or use pod‑disruption‑budget / QoS classes to protect critical pods. |

---

## How to verify each cause (once you regain access)

1. **Check the termination reason** – `kubectl describe pod …` will show `State: Terminated` with `Reason: OOMKilled`.  
2. **Inspect memory limits/requests** – look at the pod spec (`resources.limits.memory` and `resources.requests.memory`).  
3. **Observe runtime memory usage** – `kubectl top pod … --containers` (requires metrics‑server) or `kubectl exec … cat /sys/fs/cgroup/memory/memory.usage_in_bytes`.  
4. **Review node health** – `kubectl describe node <node>` for `MemoryPressure` condition and recent OOM events.  
5. **Examine events** – `kubectl get events -n kubexplain-eval --field-selector involvedObject.name=s03-oomkilled` to see timestamps and any node‑level OOM messages.  
6. **Check side‑car containers** – the same `describe pod` output lists each container’s termination reason.  
7. **Look for LimitRange/Quota overrides** – `kubectl get limitrange -n kubexplain-eval` and `kubectl get resourcequota -n kubexplain-eval`.  
8. **Search for hugepage usage** – grep the pod YAML for `hugepages-` entries.

---

## Typical remediation steps (apply the one that matches the verified cause)

1. **Raise memory limits** – edit the Deployment/StatefulSet/Pod spec:  
   ```yaml
   resources:
     requests:
       memory: "512Mi"
     limits:
       memory: "1Gi"
   ```  
   Then roll out a new replica set (`kubectl rollout restart deployment/<name>`).

2. **Fix the application** – profile the code (e.g., using `pprof`, `jmap`, or language‑specific memory profilers) to locate leaks, then patch/rebuild.

3. **Adjust requests** – set requests ≈ limit (or a realistic baseline) so the scheduler places the pod on a node with enough capacity.

4. **Add node capacity** – scale the node pool, enable cluster autoscaler, or move the pod to a less‑loaded node (delete the pod to let it be rescheduled).

5. **Separate side‑cars** – give each container its own `resources` block, or split the side‑car into its own pod if it has divergent memory needs.

6. **Update LimitRanges/Quotas** – increase the max allowed memory in the namespace or create a dedicated namespace with higher limits for this workload.

7. **Remove or reduce hugepage usage** – if not essential, disable hugepages; otherwise, increase the pod’s memory limit to cover the hugepage allocation.

8. **Improve QoS protection** – give the pod higher QoS (e.g., `requests == limits` → **Guaranteed**) so the kubelet prefers other pods when the node is under pressure.

---

### Quick checklist you can run when you’re back in the cluster

1. `kubectl describe pod s03-oomkilled -n kubexplain-eval` → look for `OOMKilled` and the timestamp.  
2. `kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml | grep -A5 resources:` → verify limits/requests.  
3. `kubectl top pod s03-oomkilled -n kubexplain-eval --containers` → see actual usage vs. limits.  
4. `kubectl describe node <node>` → check `MemoryPressure` and allocatable memory.  
5. `kubectl get events -n kubexplain-eval --field-selector involvedObject.name=s03-oomkilled` → recent OOM events.  

Address the first item that shows a mismatch (e.g., usage consistently hitting the limit) and iterate through the list until the pod stabilises.
