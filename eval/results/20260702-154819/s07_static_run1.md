# s07 | mode=static | run=1 | 8.7s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What the pod description is telling you

| Section | What you see | What it means |
|---------|--------------|---------------|
| **State** | `Waiting` – Reason: **CreateContainerConfigError** | The kubelet could not build a valid container configuration before trying to start the container. |
| **Environment Variables from** | `configmap-care-nu-exista  ConfigMap  Optional: false` | The pod asks the kubelet to load environment variables from a ConfigMap named **configmap‑care‑nu‑exista**. Because `Optional: false`, the ConfigMap **must exist** in the same namespace. |
| **Events** | `Warning  Failed … Error: configmap "configmap-care-nu-exista" not found` | The kubelet looked for that ConfigMap, didn’t find it, and therefore aborted container creation. |
| **Overall status** | `Pending` → `PodScheduled: True` but `ContainersReady: False` | The pod is scheduled onto a node, but the container never starts because the required ConfigMap is missing. |

**Root cause:** The ConfigMap `configmap-care-nu-exista` does not exist in the `kubexplain-eval` namespace (or is in a different namespace). Because the pod’s `envFrom` reference is mandatory (`optional: false`), the kubelet treats the missing ConfigMap as a fatal error and returns `CreateContainerConfigError`.

---

### How to fix it

You have three practical options, depending on what the intended behavior is.

#### 1. Create the missing ConfigMap (the usual fix)

If the pod really needs that ConfigMap, create it with the expected name and data in the same namespace.

```bash
# Example: create a ConfigMap with a couple of key/value pairs
kubectl create configmap configmap-care-nu-exista \
  --from-literal=FOO=bar \
  --from-literal=BAZ=qux \
  -n kubexplain-eval
```

Or, if you already have a manifest file (`configmap.yaml`), apply it:

```bash
kubectl apply -f configmap.yaml -n kubexplain-eval
```

After the ConfigMap exists, the pod will be able to start (you may need to delete the pod so it restarts, or let the kubelet retry automatically).

#### 2. Make the reference optional

If the ConfigMap is truly optional for the container, modify the pod (or Deployment, Job, etc.) to mark the `envFrom` entry as optional:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true   # <‑‑ change from false to true
```

Apply the updated manifest, then delete the pod so a fresh one is created with the new spec.

#### 3. Remove or correct the reference

If the ConfigMap name is a typo or the pod should be using a different ConfigMap, edit the pod spec (or the higher‑level controller) to point to the correct existing ConfigMap, or simply delete the `envFrom` block if it isn’t needed.

```yaml
envFrom:
- configMapRef:
    name: correct-configmap-name   # replace with the real name
    optional: false
```

Apply the corrected manifest and let the pod restart.

---

### Quick verification steps after fixing

1. **Confirm the ConfigMap exists**

   ```bash
   kubectl get configmap configmap-care-nu-exista -n kubexplain-eval
   ```

2. **Watch the pod status**

   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -w
   ```

   You should see the pod transition from `Pending` → `Running` (or `Completed` if the container exits).

3. **Inspect the container logs (optional)**

   ```bash
   kubectl logs s07-missing-cm -n kubexplain-eval
   ```

   For the given `busybox` command (`sleep 3600`) you’ll just see no output, which is expected.

---

### TL;DR

*Root cause*: The pod references a non‑existent ConfigMap (`configmap-care-nu-exista`) with `optional: false`, causing a `CreateContainerConfigError`.  
*Fix*: Create the ConfigMap, or change the pod spec to make the reference optional or point to an existing ConfigMap, then let the pod restart. Once the ConfigMap is available (or the reference removed/optional), the container will start normally.
