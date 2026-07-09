# s03 | mode=dynamic | run=1 | 8.4s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What the logs are telling you  

| Field | Value | Meaning |
|-------|-------|---------|
| **Command** | `head -c 512m /dev/zero | tail` | The container tries to read **512 MiB** of data into memory. |
| **Limits** | memory = 64 Mi | The kubelet will kill the container as soon as it tries to use more than 64 MiB. |
| **Last State – Reason** | `OOMKilled` (exit code 137) | The process exceeded its memory limit, the kernel OOM‑killer terminated it. |
| **State** | `CrashLoopBackOff` | After the OOM kill the kubelet retries, backs‑off, and repeats the cycle. |
| **QoS Class** | `Burstable` | Because a memory **request** (32 Mi) is lower than the **limit** (64 Mi), the pod gets Burstable QoS – the limit is enforced and the pod is killed when exceeded. |

**Root cause:** The container’s workload needs far more memory (≈ 512 Mi) than the pod’s memory **limit** (64 Mi). When the process tries to allocate the extra memory the kernel OOM‑killer terminates it, leading to the observed `OOMKilled → CrashLoopBackOff` cycle.

---

### How to fix it  

1. **Raise the memory limit (and optionally the request) so it covers the workload.**  
   Example values that would comfortably accommodate the 512 Mi demand:  
   * `requests.memory: 600Mi`  
   * `limits.memory: 700Mi` (or higher).  

2. **Or change the workload so it stays within the existing limits.**  
   * Reduce the amount of data you generate (`head -c 50m …` instead of 512 m).  
   * Use a streaming approach that does not keep the whole payload in RAM.

3. **If you want the pod to be unrestricted, remove the memory limit entirely** (or set it to a very high value).  Without a limit the pod will run with the node’s available memory, but you lose the safety net that prevents a single pod from exhausting the node.

4. **Apply the change** (assuming the pod is managed by a Deployment, Job, etc.):

   *Edit the manifest* – increase `resources.limits.memory` (and optionally `resources.requests.memory`).  
   *Or patch the live object* if you need a quick test:

   ```bash
   kubectl set resources pod s03-oomkilled -n kubexplain-eval \
       --limits=memory=800Mi --requests=memory=600Mi
   ```

   (Replace the values with what you decided in steps 1‑3.)

5. **Verify the fix** – after the pod restarts, run:

   ```bash
   kubectl describe pod s03-oomkilled -n kubexplain-eval
   ```

   Look for the `State: Running` line and ensure there are no `OOMKilled` events in the recent `Events` section.

---

### Why the other fields don’t matter here  

* **Node health** – The node (`worker-1`) is reported as `Ready`; no node‑level issue is causing the restarts.  
* **Taints / tolerations** – The pod tolerates `node.kubernetes.io/not‑ready` and `unreachable`, but those are not triggered.  
* **QoS class** – Being `Burstable` simply means the limit is enforced; it isn’t the cause, the limit itself is.

---

### TL;DR  

Your pod is deliberately allocating ~512 MiB of memory, but you have capped it at 64 MiB. The OOM‑killer kills the container, and the kubelet puts the pod into a `CrashLoopBackOff`. Increase the memory limit (and request) or reduce the memory demand, then redeploy the pod. Once the limit exceeds the workload’s peak usage, the pod will stay `Running` without being killed.
