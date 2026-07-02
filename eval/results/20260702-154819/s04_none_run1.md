# s04 | mode=none | run=1 | 6.2s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Root cause  

The container is a **CPU‑bound busy‑loop** (`while true; do :; done`).  
Its **CPU limit is only 25 m** (25 millicores) with a request of 10 m.  

Kubernetes enforces this limit via cgroup throttling. When the process tries to use more than 25 m, the kernel repeatedly pauses it, so the loop runs at a fraction of a normal core. The result is an application that appears “extremely slow and unresponsive”.

The pod is in **Burstable** QoS because request ≠ limit, so it gets only the guaranteed 10 m and can burst up to 25 m – far below what a busy‑loop needs to run at a reasonable speed.

## How to fix it  

1. **Raise the CPU resources** (or remove the limit) so the container can get enough CPU cycles.  
   - If you want a guaranteed amount, set **request = limit** (Guaranteed QoS).  
   - Choose a value that matches the workload; for a simple test, 200 m–500 m is typical.

2. Apply the updated manifest (or edit the pod if it’s a one‑off test).

### Example manifest change  

```yaml
containers:
- name: app
  image: busybox:1.36
  command: ["sh", "-c", "while true; do :; done"]
  resources:
    requests:
      cpu: "200m"
    limits:
      cpu: "200m"   # equal to request → Guaranteed QoS
```

### Apply the change  

If the pod is created directly (no controller):

```bash
kubectl delete pod s04-cpu-throttle -n kubexplain-eval
kubectl apply -f <updated‑manifest>.yaml
```

If it’s managed by a Deployment/StatefulSet, edit that controller:

```bash
kubectl edit deployment <name> -n kubexplain-eval
```

Modify the `resources.requests.cpu` and `resources.limits.cpu` as shown, then save. The controller will roll out a new pod with the higher CPU allocation.

3. **Verify** the pod is no longer throttled:

```bash
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```

You should see CPU usage close to the requested/limited value (e.g., ~200 m) without the “%CPU throttled” metric.

---

**Summary:** The pod is throttled because its CPU limit (25 m) is far too low for a CPU‑intensive loop. Increase the CPU request/limit (preferably making them equal) to eliminate throttling and restore normal performance.
