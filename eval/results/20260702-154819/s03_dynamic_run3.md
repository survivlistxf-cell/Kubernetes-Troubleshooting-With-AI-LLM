# s03 | mode=dynamic | run=3 | 7.3s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What’s happening

| Observation | Explanation |
|-------------|-------------|
| **Last State – Reason: OOMKilled** | The container was terminated because it exceeded its memory limit. |
| **Command** `head -c 512m /dev/zero \| tail` | This reads 512 MiB of data into memory. |
| **Memory limits**<br>‑ limit: **64 Mi**<br>‑ request: **32 Mi** | The pod is allowed to use only 64 Mi of RAM. The command tries to allocate 512 Mi, so the kernel’s OOM‑killer kills the process. |
| **QoS class**: *Burstable* | Because a limit is set, the pod is placed in the *Burstable* QoS tier; when it exceeds its limit it is killed rather than throttled. |
| **Result**: `CrashLoopBackOff` → the kubelet keeps trying to restart the container, which immediately OOM‑kills again. |

**Root cause:** The container’s memory **limit (64 Mi)** is far lower than the amount of memory the workload tries to consume (≈ 512 Mi). The OOM‑killer terminates the process, leading to the restart loop.

---

### How to fix it

1. **Increase the memory limits** (or remove the limit) so the container can allocate the required memory.  
   Example values that would comfortably cover the 512 Mi workload:
   * `requests.memory: 600Mi`  
   * `limits.memory: 800Mi` (or higher)

2. **Or change the workload** so it stays within the existing limits (e.g., reduce the amount of data read, use a streaming approach, or limit the `head` size).

3. **Apply the change** – depending on how the pod is created:

   - **If it’s a plain Pod manifest** (e.g., `kubectl apply -f pod.yaml`):
     ```bash
     # edit the manifest locally, then re‑apply
     kubectl apply -f pod.yaml
     ```

   - **If it’s managed by a Deployment/Job/ReplicaSet**:
     ```bash
     # edit the live object
     kubectl edit deployment <name> -n kubexplain-eval
     ```
     Adjust the `resources:` block under the container spec, e.g.:

     ```yaml
     resources:
       requests:
         memory: "600Mi"
       limits:
         memory: "800Mi"
     ```

   - **Or patch it directly** (useful for a quick fix):
     ```bash
     kubectl patch pod s03-oomkilled -n kubexplain-eval \
       --type='json' -p='[{"op":"replace","path":"/spec/containers/0/resources","value":{"requests":{"memory":"600Mi"},"limits":{"memory":"800Mi"}}}]'
     ```

4. **Verify the fix**:

   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -w
   ```

   The pod should transition to **Running** without entering `CrashLoopBackOff`.

---

### Quick checklist

- [ ] Confirm the container really needs ~512 Mi (the command you’re running does).  
- [ ] Set **requests** ≥ the expected steady‑state usage.  
- [ ] Set **limits** ≥ the peak usage (or omit limits if you want the pod to be *BestEffort*).  
- [ ] Re‑apply or patch the manifest.  
- [ ] Watch the pod status until it stays `Running`.

Once the memory limits accommodate the workload, the OOM killer will no longer terminate the container, and the restart loop will stop.
