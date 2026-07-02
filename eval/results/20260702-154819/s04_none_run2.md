# s04 | mode=none | run=2 | 7.9s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What the pod description tells us

| Field | Value | Why it matters |
|------|-------|----------------|
| **Container image** | `busybox:1.36` | Very small image, nothing heavy inside. |
| **Command** | `sh -c "while true; do :; done"` | An infinite busy‑loop that continuously consumes CPU. |
| **Limits → cpu** | `25m` (25 millicores) | The container is **capped at 2.5 % of a single CPU core**. |
| **Requests → cpu** | `10m` | Scheduler reserves only 1 % of a core for this pod. |
| **QoS class** | `Burstable` | Because a limit is set, the pod can be throttled when it exceeds the limit. |
| **Events** | No errors, just normal start‑up. | The pod is healthy from the Kubernetes point of view. |

The pod is **running**, but the application (the busy‑loop) is *extremely slow* because the CPU limit is far too low. The kernel’s cgroup controller will **throttle** the container whenever it tries to use more than 25 mCPU. Since the loop tries to run as fast as possible, it is constantly throttled, which looks like “unresponsive” or “slow”.

### Root cause

**CPU throttling caused by an overly restrictive `cpu` limit (25 m).**  
The pod’s workload needs more CPU than the limit allows, so the kernel repeatedly pauses the container, dramatically reducing its effective throughput.

### How to fix it

1. **Raise the CPU limit (and request) to a value that matches the workload.**  
   For a simple busy‑loop you can start with something like `200m` (20 % of a core) or `500m` (50 % of a core). Adjust upward until the pod’s CPU usage stabilises below the limit.

2. **Update the manifest** (or the owning Deployment/ReplicaSet, etc.) and re‑apply.

   ```yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: s04-cpu-throttle
     namespace: kubexplain-eval
   spec:
     containers:
     - name: app
       image: busybox:1.36
       command: ["sh", "-c", "while true; do :; done"]
       resources:
         requests:
           cpu: "100m"      # reserve 10 % of a core
         limits:
           cpu: "200m"      # cap at 20 % of a core (adjust as needed)
   ```

   Apply the change:

   ```bash
   kubectl apply -f pod.yaml
   ```

   *If the pod is managed by a higher‑level controller (Deployment, StatefulSet, etc.), edit that resource instead (`kubectl edit deployment <name>`).*

3. **Verify the fix**  

   After the pod restarts, run:

   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   ```

   You should see CPU usage staying **below** the new limit (e.g., ~150 m) and no throttling messages in `kubectl describe pod …` under the **Events** or **ContainerStatuses** sections.

4. **Optional: remove the limit** if you don’t need to constrain this pod. Without a limit the pod will run with whatever CPU the node can provide (still respecting the request for scheduling).

   ```yaml
   resources:
     requests:
       cpu: "100m"
   # no limits section
   ```

### Quick checklist

- [ ] Increase `resources.limits.cpu` (and usually `resources.requests.cpu` to the same or a slightly lower value).  
- [ ] Re‑apply the manifest / rollout the updated controller.  
- [ ] Confirm the pod restarts and that `kubectl top pod` shows healthy CPU usage.  
- [ ] If the pod is still slow, repeat the step with a higher limit until performance is acceptable.

Once the CPU limit is raised, the busy‑loop (or any real application you replace it with) will no longer be throttled, and the pod will respond at normal speed.
