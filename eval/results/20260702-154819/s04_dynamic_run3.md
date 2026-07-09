# s04 | mode=dynamic | run=3 | 8.0s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What’s happening

| Field | Value in the pod description |
|-------|------------------------------|
| **Container image** | `busybox:1.36` |
| **Command** | `while true; do :; done` (tight infinite loop) |
| **CPU request** | `10 m` |
| **CPU limit**   | `25 m` |
| **QoS class**   | `Burstable` |

The container runs a **busy‑wait loop** that constantly tries to consume CPU cycles.  
Because a **CPU limit of only 25 millicores** is set, the kubelet’s cgroup throttles the process whenever it exceeds that quota. The result is:

* The process is constantly being **throttled** → it can only use a tiny fraction of a core.  
* From the application’s point of view the loop (and any work it does) becomes **extremely slow or appears “hung.”**

So the root cause is **CPU throttling caused by an overly‑low CPU limit** on a CPU‑intensive workload.

---

### How to fix it

1. **Raise the CPU limit (and request) to a value that matches the workload’s needs.**  
   For a simple busy‑wait you might give it a full core, e.g.:

   ```bash
   cpu request: 200m
   cpu limit:   500m   # or 1 (1000m) if you want a whole core
   ```

2. **If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), update the controller instead of editing the pod directly.**  
   Example for a Deployment named `s04-cpu-throttle` in the same namespace:

   ```bash
   kubectl set resources deployment s04-cpu-throttle \
       --requests=cpu=200m \
       --limits=cpu=500m \
       -n kubexplain-eval
   ```

   *If the pod is a plain Pod object, you can edit it in‑place:*

   ```bash
   kubectl edit pod s04-cpu-throttle -n kubexplain-eval
   ```

   Then modify the `resources:` block under the container spec.

3. **Apply the change and let the pod restart (or delete it so the controller recreates it).**  
   After the new limits are in place, the container will no longer be throttled and the loop will run at the expected speed.

4. **Verify the fix**  

   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   ```

   You should see CPU usage close to the limit you set, and the container should no longer be reported as “Throttled” in `kubectl describe pod`.

---

### Quick checklist

- [ ] **Identify the controller** (Deployment, StatefulSet, etc.) that created the pod.  
- [ ] **Increase CPU request & limit** to a realistic value for the workload.  
- [ ] **Apply the change** (edit the controller or use `kubectl set resources`).  
- [ ] **Confirm the pod restarts** and that CPU throttling disappears (`kubectl top pod`, `kubectl describe pod`).  

Once the CPU limit is raised, the infinite loop will have enough CPU time and the application will respond normally.
