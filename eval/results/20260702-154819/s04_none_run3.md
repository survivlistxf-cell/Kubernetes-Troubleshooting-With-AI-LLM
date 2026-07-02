# s04 | mode=none | run=3 | 6.5s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. The manifest and describe output are attached. What is the root cause and how do I fix it?

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

### What’s happening

* The container runs a tight **busy‑loop** (`while true; do :; done`).  
* Its **CPU limit** is set to **25 m** (25 millicores ≈ 2.5 % of a single core).  
* The loop tries to consume 100 % of a core, so the kubelet’s cgroup **throttles** the process to stay under the 25 m limit.  
* Because the pod is limited to a tiny slice of CPU, the work it does (even a trivial loop) proceeds extremely slowly, which is why the application appears “unresponsive”.

The pod’s QoS class is **Burstable** (request = 10 m, limit = 25 m). With such a low limit the node will constantly enforce the throttle, and the container will never get enough CPU cycles to run at a usable speed.

### How to fix it

1. **Raise the CPU limit (and request) to a realistic value** for the workload.  
   - For a simple demo you can give it a full core: `cpu: "500m"` (0.5 core) or `cpu: "1"` (1 core).  
   - Keep the request ≤ limit; a common pattern is `request: "250m", limit: "500m"`.

2. **Apply the change** – if the pod is created directly (no controller), delete and recreate it with the updated manifest; if it’s managed by a Deployment/ReplicaSet, patch or edit the controller so new pods are created with the new resources.

#### Example – editing a raw pod manifest

```bash
kubectl edit pod s04-cpu-throttle -n kubexplain-eval
```

In the editor change the `resources` block, e.g.:

```yaml
resources:
  requests:
    cpu: "250m"
  limits:
    cpu: "500m"
```

Save and exit. The pod will be terminated and a new one started with the new limits.

#### Example – patching a Deployment (if you have one)

```bash
kubectl patch deployment <deployment-name> -n kubexplain-eval \
  --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/resources","value":{"requests":{"cpu":"250m"},"limits":{"cpu":"500m"}}}]'
```

After the rollout completes, the pods will run with the higher CPU allocation and the loop (or your real application) will no longer be throttled.

### Quick sanity check

Once the pod is running with the new limits, you can verify that throttling has stopped:

```bash
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```

You should see CPU usage close to the limit you set (e.g., ~400‑500 m) rather than a tiny fraction.

---

**Bottom line:** the pod is slow because its CPU limit (`25m`) is far too low for a CPU‑intensive workload. Increase the request/limit to give the container enough CPU, and the application will respond normally.
