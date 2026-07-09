# s03 | mode=dynamic | run=2 | 6.9s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

**Root cause**

The container is being killed by the kubelet because it exceeds its memory limit.

* The command `head -c 512m /dev/zero | tail` tries to allocate **~512 MiB** of memory (it reads 512 MiB of zeros into a pipe).  
* The pod spec sets a **memory limit of 64 MiB** (and a request of 32 MiB).  
* When the process tries to use more than the 64 MiB cgroup limit, the kernel OOM‑killer terminates it.  
* The termination reason appears in the pod description as `OOMKilled` with exit code 137, which then triggers the `CrashLoopBackOff` restart behavior.

**How to fix it**

You have three practical options:

1. **Raise the memory limit** (and optionally the request) so the container can allocate the needed 512 MiB.  
2. **Reduce the memory consumption** of the workload – e.g., change the command to use a smaller size or a different method that doesn’t need that much RAM.  
3. **Add a memory‑swap or limit‑range** if you want to enforce a hard cap but still allow occasional spikes (swap is rarely used in production clusters).

Below are the most common fix‑paths.

---

### 1. Increase the memory limit (quick fix)

If the pod is created from a Deployment, StatefulSet, or directly via `kubectl run`, edit the pod spec:

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

In the editor, locate the container `app` and change:

```yaml
resources:
  limits:
    memory: 64Mi      # <-- change to at least 600Mi
  requests:
    memory: 32Mi      # <-- optionally raise to 300Mi
```

Save and exit; the Deployment will roll out a new ReplicaSet with the updated limits. The pod will be recreated and should stay running.

---

### 2. Change the command to use less memory (if the large buffer isn’t needed)

Edit the pod (or the manifest that creates it) and replace the command with a smaller size, e.g.:

```yaml
command:
- sh
- -c
- head -c 64m /dev/zero | tail
```

or any other logic that fits within the existing 64 MiB limit.

Apply the change:

```bash
kubectl apply -f <updated-manifest>.yaml
```

---

### 3. Use a `ResourceQuota`/`LimitRange` to enforce sensible defaults (prevent future OOMs)

Create a `LimitRange` in the namespace that sets a minimum memory limit for containers:

```yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: mem-limits
  namespace: kubexplain-eval
spec:
  limits:
  - default:
      memory: 128Mi
    defaultRequest:
      memory: 64Mi
    type: Container
```

Apply it:

```bash
kubectl apply -f limitrange.yaml
```

Now any new pod that omits explicit limits will get at least 128 MiB, avoiding accidental OOM kills.

---

### Verify the fix

After applying the chosen change, watch the pod:

```bash
kubectl get pod s03-oomkilled -n kubexplain-eval -w
```

You should see the pod transition to **Running** without entering `CrashLoopBackOff`. You can also confirm the container’s memory usage with:

```bash
kubectl top pod s03-oomkilled -n kubexplain-eval
```

If the memory usage stays below the new limit, the issue is resolved.
