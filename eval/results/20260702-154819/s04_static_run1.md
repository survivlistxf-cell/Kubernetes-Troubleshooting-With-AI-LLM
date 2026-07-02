# s04 | mode=static | run=1 | 6.7s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What’s happening

* The container runs a **CPU‑bound busy‑loop** (`while true; do :; done`).  
* In the pod spec you set a **CPU limit of 25 m** (25 millicores) and a request of 10 m.  
* With such a low limit the kubelet’s cgroup will **throttle the container** whenever it tries to use more than 25 mCPU.  
* Because the loop constantly wants CPU, it is constantly throttled, so the process runs at a fraction of a normal speed – the application appears “extremely slow and unresponsive”.

The pod’s QoS class is **Burstable**, which means the limit is enforced and the request is only used for scheduling. The limit is the real ceiling that is causing the throttling.

### How to fix it

1. **Raise the CPU limit (and request) to a value that matches the workload’s needs.**  
   For a simple busy‑loop you can start with something like 100 m (0.1 CPU) or higher.

2. **Update the pod definition** (or the Deployment/ReplicaSet that creates it) and re‑apply it, or delete the pod so it is recreated with the new limits.

#### Example manifest change

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
        cpu: "100m"      # or whatever you need
      limits:
        cpu: "200m"
```

Apply the change:

```bash
kubectl apply -f <updated‑manifest>.yaml
```

If the pod is created directly (no controller), you can simply delete it and recreate it with the new limits:

```bash
kubectl delete pod s04-cpu-throttle -n kubexplain-eval
kubectl apply -f <updated‑manifest>.yaml
```

3. **Verify the fix**  

   After the pod is running again, check that it is no longer being throttled:

   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   ```

   The CPU usage should now be close to the limit you set, and the loop will run at normal speed.

### TL;DR

The pod is throttled because its CPU **limit is only 25 m**. Increase the CPU request/limit (e.g., to 100 m – 200 m) and recreate the pod. The application will stop being slow.
