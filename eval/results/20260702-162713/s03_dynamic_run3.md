# s03 | mode=dynamic | run=3 | 11.5s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod is being **OOMKilled** (in order of probability)

1. **Container memory limit is lower than the workload’s actual memory demand**  
   *Why it happens* – The pod spec defines a `resources.limits.memory` value. When the container’s RSS exceeds that limit the kernel’s OOM‑killer terminates the process, and the pod is restarted.  
   *How to verify* –  
   - Look at the pod’s status (`kubectl describe pod s03-oomkilled -n kubexplain-eval`) and find the `State: Terminated` entry for the container; the `Reason` field will be `OOMKilled`.  
   - Check the `Limits` section of the pod spec to see the configured memory limit.  
   - If you have metrics (e.g., from `kubectl top pod` or a monitoring system), compare the peak memory usage to the limit.  
   *Fix* – Raise the memory limit (and usually the request) to a value comfortably above the observed peak, or optimise the application to use less memory. If the workload is highly variable, consider enabling the Vertical Pod Autoscaler.

2. **Node is under memory pressure and evicts pods**  
   *Why it happens* – Even if the pod’s own limit is not exceeded, the kubelet may kill containers when the node’s available memory falls below a safety threshold. The eviction controller records the event as `OOMKilled`.  
   *How to verify* –  
   - Examine the node’s conditions (`kubectl describe node <node-name>`) for `MemoryPressure=True`.  
   - Look at the node’s system logs (`/var/log/kubelet.log` or `journalctl -u kubelet`) for eviction messages mentioning the pod.  
   *Fix* – Add more memory to the node (scale the node pool, add new nodes) or reduce overall memory consumption on that node (e.g., lower limits of other pods, move non‑critical workloads).  

3. **Memory leak or sudden spike in the application**  
   *Why it happens* – The container may be correctly sized for normal operation but a bug causes it to allocate more memory over time, eventually hitting the limit.  
   *How to verify* –  
   - Review the container logs for signs of increasing memory usage or error messages before termination.  
   - If you have a time‑series of memory metrics, look for a monotonic upward trend leading up to the kill.  
   *Fix* – Debug the application code to locate the leak, add proper garbage collection, or add defensive limits/restarts (e.g., liveness probe) to mitigate the impact.  

4. **Incorrect or missing `resources.requests.memory` causing the scheduler to place the pod on a node that cannot satisfy its real needs**  
   *Why it happens* – Without a request, the scheduler may put the pod on a node that is already heavily loaded. The pod then competes for memory and gets killed.  
   *How to verify* –  
   - Check the pod spec for a `requests.memory` entry; if it’s absent, the pod was scheduled without a guaranteed amount of memory.  
   - Look at the node’s overall memory usage; a high utilization suggests over‑commitment.  
   *Fix* – Define a realistic memory request (usually equal to or slightly lower than the limit) so the scheduler places the pod on a node with sufficient capacity.  

5. **Pod is part of a DaemonSet or Deployment that repeatedly creates new pods after OOM kills, masking the underlying issue**  
   *Why it happens* – The controller’s replica count forces a new pod to appear as soon as the previous one is terminated, giving the impression of a “restart loop”.  
   *How to verify* –  
   - Look at the controller (Deployment, ReplicaSet, DaemonSet) that owns the pod (`kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml` and note the `ownerReferences`).  
   - Check the controller’s events for repeated pod creation.  
   *Fix* – Resolve the root OOM cause (memory limit, leak, node pressure) first. If the controller is mis‑configured (e.g., too many replicas on a small cluster), adjust its replica count or spread strategy.  

6. **System‑level OOM killer on the host (outside of Kubernetes) terminating the container process**  
   *Why it happens* – The host kernel may kill a container’s process if the node’s overall memory is exhausted, even if the pod’s limit is not reached.  
   *How to verify* –  
   - Check the node’s kernel logs (`dmesg` or `/var/log/messages`) for lines mentioning “Out of memory: Kill process … in container …”.  
   - Correlate the timestamp with the pod’s termination time.  
   *Fix* – Increase node memory, reduce overall workload, or enable node‑level memory QoS (e.g., cgroup v2) to better isolate pods.  

---

### General verification checklist (to run when you regain cluster access)

1. **Pod status & events** – `kubectl describe pod s03-oomkilled -n kubexplain-eval`. Look for `Reason: OOMKilled` and any preceding warnings.  
2. **Resource spec** – `kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml` and inspect `resources.limits.memory` and `resources.requests.memory`.  
3. **Node health** – Identify the node (`kubectl get pod s03-oomkilled -n kubexplain-eval -o wide`) and then `kubectl describe node <node>` for `MemoryPressure`.  
4. **Metrics** – Use `kubectl top pod` (or your monitoring system) to see actual memory usage versus limits.  
5. **Logs** – `kubectl logs s03-oomkilled -n kubexplain-eval --previous` to capture the last container’s output before it was killed.  
6. **Node logs** – SSH to the node (or use `journalctl -u kubelet`) and search for eviction or OOM messages.  

By walking through these steps you can pinpoint which of the above causes is triggering the OOM kills and apply the corresponding fix. Once the memory pressure is resolved—either by raising limits, fixing leaks, or adding capacity—the pod will stop being terminated and will stay running.
