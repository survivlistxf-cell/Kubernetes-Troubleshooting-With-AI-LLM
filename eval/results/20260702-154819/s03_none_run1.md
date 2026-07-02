# s03 | mode=none | run=1 | 7.1s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## What is happening?

- The container runs the command  

  ```bash
  head -c 512m /dev/zero | tail
  ```

- `head -c 512m` tries to read **512 MiB** of data.  
- The pod’s **memory limit** is set to **64 MiB** (see `Limits: memory: 64Mi`).  

When the process tries to use more than the 64 MiB cgroup limit, the kernel kills it with **OOMKilled** (exit code 137). Kubernetes then restarts the container, sees the failure again, and puts the pod into **CrashLoopBackOff**.

So the root cause is **the container exceeds its memory limit**.

## How to fix it

You have two main options:

### 1. Raise the memory limits (or remove them)

Give the container enough memory for the workload.

```bash
kubectl edit pod s03-oomkilled -n kubexplain-eval
```

In the editor change the `resources` section, e.g.:

```yaml
resources:
  limits:
    memory: 1Gi          # or any value > 512Mi
  requests:
    memory: 512Mi        # optional, but keep request ≤ limit
```

If the pod is managed by a higher‑level controller (Deployment, Job, etc.), edit that controller instead:

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

and adjust the same `resources` block in the container spec.

After saving, the controller will recreate the pod with the new limits and the command will run without being OOM‑killed.

### 2. Reduce the memory demand of the workload

If the 512 MiB read is just for a test, lower it to fit within the existing limit, e.g.:

```yaml
command:
- sh
- -c
- head -c 32m /dev/zero | tail   # 32MiB fits into the 64MiB limit
```

Update the pod (or its controller) with the new command and apply.

## Quick checklist

- **Confirm the controller**: `kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml | grep ownerReferences` – if you see a Deployment/Job, edit that object, not the pod directly.
- **Apply the change**: `kubectl apply -f <updated‑manifest>.yaml` or let `kubectl edit` trigger a rollout.
- **Watch the result**:  

  ```bash
  kubectl get pod s03-oomkilled -n kubexplain-eval -w
  ```

  The pod should transition to `Running` and stay there.

---

**Bottom line:** the pod is OOM‑killed because it tries to allocate ~512 MiB while its limit is only 64 MiB. Increase the memory limit (or lower the workload’s memory usage) and the CrashLoopBackOff will disappear.
