# s03 | mode=none | run=3 | 7.8s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What is happening?

| Observation | Explanation |
|-------------|-------------|
| **Container command** `head -c 512m /dev/zero | tail` | The command tries to read **512 MiB** of data from `/dev/zero`. Even though the data is just zeros, the process must allocate a buffer large enough to hold that amount (or at least enough to keep the pipe full). |
| **Memory limits** `requests: 32Mi` / `limits: 64Mi` | The pod is allowed to use at most **64 MiB** of RAM. |
| **Last state** `OOMKilled` (exit code 137) | The container exceeded its memory limit, the kubelet’s OOM‑killer terminated it. |
| **Current state** `CrashLoopBackOff` | After being killed, the pod is restarted, hits the same limit again, and goes back into back‑off. |

**Root cause:** The workload needs **> 64 MiB** of RAM (it tries to allocate ~512 MiB), but the pod’s memory **limit** is only 64 MiB. The kernel OOM‑killer kills the container, leading to the CrashLoopBackOff.

---

### How to fix it

1. **Raise the memory limit** (and optionally the request) so that it exceeds the amount the command needs.  
   A safe starting point is to give the container at least the size it tries to allocate plus a margin, e.g. **600 MiB**.

2. **Or change the workload** so it uses less memory (e.g., reduce the `head -c` size).

Below are two typical ways to apply the fix.

---

#### Option A – Edit the pod (or the controller that creates it)

If the pod is created directly (no Deployment/Job), you can patch it:

```bash
kubectl -n kubexplain-eval edit pod s03-oomkilled
```

In the editor, locate the `resources:` block and change it to something like:

```yaml
resources:
  requests:
    memory: "600Mi"
  limits:
    memory: "600Mi"
```

Save and exit. The pod will be terminated and recreated with the new limits.

---

#### Option B – Update the manifest (Deployment, Job, etc.)

If the pod is managed by a higher‑level controller, edit that resource instead. Example for a Deployment:

```bash
kubectl -n kubexplain-eval edit deployment <deployment-name>
```

Change the container spec:

```yaml
spec:
  template:
    spec:
      containers:
      - name: app
        image: busybox:1.36
        command: ["sh","-c","head -c 512m /dev/zero | tail"]
        resources:
          requests:
            memory: "600Mi"
          limits:
            memory: "600Mi"
```

Apply the change (the controller will roll out a new replica set, terminating the old pod).

---

#### Option C – Remove the limit entirely (not recommended for production)

If you simply want to let the container use whatever memory the node has, you can omit the `limits` field:

```yaml
resources:
  requests:
    memory: "512Mi"
```

Kubernetes will then give the pod **Best‑Effort** QoS, and the container will only be killed if the node itself runs out of memory.

---

### Verify the fix

After applying the change, watch the pod:

```bash
kubectl -n kubexplain-eval get pod s03-oomkilled -w
```

You should see the pod transition to **Running** and stay there, with `Ready` = `True` and no `OOMKilled` events.

---

### TL;DR

*The pod is OOM‑killed because it tries to allocate ~512 MiB while its memory limit is only 64 MiB.*  
**Fix:** increase the container’s memory `requests`/`limits` (e.g., to 600 MiB) or reduce the workload’s memory consumption. Apply the change to the pod or its owning controller, then confirm the pod stays healthy.
