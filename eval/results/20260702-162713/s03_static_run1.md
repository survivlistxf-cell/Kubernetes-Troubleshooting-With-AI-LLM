# s03 | mode=static | run=1 | 11.8s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

**Most likely reasons why a pod named `s03‑oomkilled` is being killed and restarted (OOMKilled) – ordered by probability**

1. **Container memory limit is lower than the amount the process actually needs**  
   *Why it happens* – The pod’s spec defines a `resources.limits.memory` value that is too small for the workload. When the process tries to allocate more memory than the limit, the kernel’s OOM killer terminates the container and the pod is recreated.  
   *How to verify* –  
   - Look at the pod’s status (`kubectl describe pod s03‑oomkilled …`). The **State** of the container will show `Reason: OOMKilled`.  
   - In the same description you will see the **memory limit** that was set for the container.  
   - Compare that limit with the memory usage that the application normally requires (e.g., from logs, metrics, or a local test run).  
   *Fix* – Raise the memory limit (and optionally the request) to a value that comfortably exceeds the observed peak usage, or tune the application to use less memory. If you have a Horizontal/Vertical Pod Autoscaler, let it adjust the limit automatically.

2. **Memory request is too low, causing the scheduler to place the pod on a node that is already memory‑pressure‑strained**  
   *Why it happens* – The pod requests only a small amount of memory, so the scheduler may put it on a node that is already near its capacity. Even if the container’s limit is high enough, the node can run out of free memory and the kubelet will evict pods, often killing the one that exceeds its limit first.  
   *How to verify* –  
   - Check the pod’s `resources.requests.memory`.  
   - Inspect the node’s memory pressure condition (`kubectl describe node <node>` → look for `MemoryPressure=True`).  
   - Review events on the node for eviction messages.  
   *Fix* – Increase the memory request to reflect realistic needs, or add more nodes / larger nodes to the cluster so that the scheduler has healthier placement options.

3. **Application memory leak or sudden spike in usage**  
   *Why it happens* – The code may allocate memory over time without releasing it, or a particular request (e.g., large payload) pushes usage past the limit. The leak eventually triggers OOMKill even if the limit is generous.  
   *How to verify* –  
   - Examine the container logs for patterns that precede the kill (e.g., repeated processing of large files).  
   - If you have metrics (Prometheus, CloudWatch, etc.), plot memory usage over time for the pod; a steadily rising curve indicates a leak.  
   - Reproduce the workload locally with the same limit to see if the process crashes after a certain duration.  
   *Fix* – Fix the code to release memory, add back‑pressure, or cap the size of inputs. If a temporary spike is expected, raise the limit or add a side‑car that can off‑load heavy work.

4. **Node‑level memory pressure leading to pod eviction**  
   *Why it happens* – The node itself may be under memory pressure from other pods or system daemons. The kubelet may decide to kill pods that are using the most memory, and the OOMKill reason appears on the container.  
   *How to verify* –  
   - Look at the node’s `Conditions` for `MemoryPressure`.  
   - Review node‑wide events for `Eviction` messages that reference the pod.  
   - Check the overall memory usage on the node (e.g., `top`/`free` on the node, or metrics).  
   *Fix* – Reduce overall memory consumption on the node (scale down other workloads, adjust their limits/requests), or add more nodes / larger nodes to relieve pressure.

5. **Incorrect or missing limit range / policy enforcement**  
   *Why it happens* – A `LimitRange` or `ResourceQuota` in the namespace may be capping the maximum memory a pod can request, causing the pod to be created with a lower limit than intended.  
   *How to verify* –  
   - List the `LimitRange` objects in the `kubexplain-eval` namespace and see the `max.memory` field.  
   - Compare the pod’s actual limit with the intended value from the deployment manifest.  
   *Fix* – Adjust the `LimitRange` (or the pod spec) so that the pod can request a higher limit, or move the pod to a namespace with a more permissive policy.

6. **Mis‑typed resource specification (e.g., using `memory: "256Mi"` under `requests` but forgetting `limits`)**  
   *Why it happens* – If only a request is set and no limit, the container may be allowed to use as much memory as the node can provide, but the kubelet may still enforce a default limit (often 0 Mi) depending on the cluster’s admission controllers.  
   *How to verify* –  
   - Inspect the pod spec for the presence of both `requests.memory` and `limits.memory`.  
   - Check the effective limit shown in `kubectl describe pod`.  
   *Fix* – Explicitly set both request and limit, ensuring the limit is at least as high as the request.

---

### General verification steps you can run when you regain access

Even though you can’t run commands right now, keep the following checklist handy for when you do:

1. **Inspect the pod’s status and events** – Look for `Reason: OOMKilled` and any preceding warning events.  
2. **Check the container’s resource spec** – Verify both `requests` and `limits`.  
3. **Examine node health** – Confirm the node isn’t under `MemoryPressure`.  
4. **Review recent logs** – Identify any workload patterns that cause spikes.  
5. **Consult cluster‑wide metrics** – Memory usage per pod, per node, and any trends.  
6. **Validate namespace policies** – `LimitRange` and `ResourceQuota` that might be capping memory.  

By following the order above (starting with the most common cause—an insufficient memory limit) and confirming each hypothesis, you should be able to pinpoint the root cause and apply the appropriate fix. Good luck!
