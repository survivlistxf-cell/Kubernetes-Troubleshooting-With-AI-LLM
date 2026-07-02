# s03 | mode=static | run=1 | 7.5s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What the `describe` output tells us  

| Field | Value | Why it matters |
|-------|-------|----------------|
| **Command** | `head -c 512m /dev/zero | tail` | This reads **512 MiB** of data into memory (the `head` process allocates a 512 MiB buffer). |
| **Limits** | `memory: 64Mi` | The container is **capped at 64 MiB** of RAM. |
| **Last State – Reason** | `OOMKilled` (exit code 137) | The kernel killed the process because it tried to use more memory than the cgroup limit. |
| **State** | `Waiting → CrashLoopBackOff` | After each OOM kill the kubelet restarts the container, sees the same failure, backs‑off and repeats. |
| **QoS Class** | `Burstable` | Because a limit is set, the pod is placed in the *Burstable* QoS tier; when the node is under memory pressure the kubelet will preferentially kill containers that exceed their limits. |

**Root cause:**  
The container tries to allocate **≈ 512 MiB** while the pod’s memory **limit is only 64 MiB**. The cgroup enforces the limit, the kernel OOM‑killer terminates the process, and the pod ends up in a `CrashLoopBackOff`.

---

### How to fix it  

1. **Give the container enough memory** – raise the `resources.limits.memory` (and usually the `requests.memory` to the same or a lower value) to at least the amount the workload needs, e.g. `512Mi` or a bit higher.

2. **Or reduce the workload’s memory demand** – change the command so it does not allocate 512 MiB, e.g. `head -c 32m /dev/zero | tail` if the test only needs a smaller buffer.

3. **Verify node capacity** – make sure the node (`worker-1`) has enough free RAM to satisfy the new request. `kubectl top node worker-1` (or `kubectl describe node worker-1`) can show current usage.

4. **Apply the change**  
   - If the pod is created directly (no controller), delete it and recreate with the corrected spec.  
   - If it is managed by a Deployment/ReplicaSet, edit the controller so the new spec rolls out automatically.

   Example patch (adjust the values to what you need):

   ```yaml
   spec:
     containers:
     - name: app
       image: busybox:1.36
       command: ["sh","-c","head -c 512m /dev/zero | tail"]
       resources:
         requests:
           memory: "512Mi"
         limits:
           memory: "512Mi"
   ```

5. **Watch the pod** – after the change, run:

   ```bash
   kubectl get pod s03-oomkilled -n kubexplain-eval -w
   ```

   The pod should transition to **Running** without entering `CrashLoopBackOff`.

---

### Quick checklist

- [ ] **Memory limit ≥ memory the container actually uses** (≥ 512 MiB for the current command).  
- [ ] **Requests set** (same as limit or a lower value) so the scheduler knows the pod’s needs.  
- [ ] **Node has enough free RAM**; if not, either add capacity or lower the request.  
- [ ] **Remove the limit only if you accept unrestricted usage**, but this is rarely recommended in production.

Once the memory limits match the workload, the OOM kills stop, the `CrashLoopBackOff` disappears, and the pod stays healthy.
