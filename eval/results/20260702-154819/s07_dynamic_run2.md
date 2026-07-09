# s07 | mode=dynamic | run=2 | 6.4s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What’s happening

- The pod **s07‑missing‑cm** is stuck in `CreateContainerConfigError`.
- In the container spec you can see:

```
Environment Variables from:
  configmap-care-nu-exista  ConfigMap  Optional: false
```

- The kubelet event confirms the problem:

```
Warning  Failed  …  kubelet  Error: configmap "configmap-care-nu-exista" not found
```

Because the ConfigMap is **required** (`Optional: false`) and does not exist in the namespace `kubexplain‑eval`, the kubelet cannot build the container’s environment. The pod therefore never reaches the `Running` state.

### How to fix it

You have three options, pick the one that matches the intended behavior of the workload.

#### 1. Create the missing ConfigMap (the usual fix)

Create a ConfigMap named `configmap-care-nu-exista` in the same namespace, with the keys/values the pod expects.

```bash
kubectl create configmap configmap-care-nu-exista \
  --from-literal=MY_KEY=MY_VALUE \
  -n kubexplain-eval
```

Replace `--from-literal` (or `--from-file`) with the actual data the pod needs.

#### 2. Point the pod to an existing ConfigMap

If the pod should be using a different ConfigMap that already exists, edit the pod (or its owning controller, e.g. Deployment, Job, etc.) to reference the correct name:

```bash
kubectl edit pod s07-missing-cm -n kubexplain-eval
```

Change the `configMapRef.name` under `envFrom` to the correct ConfigMap name, then save.

#### 3. Make the reference optional

If the environment variables are truly optional and the pod can start without them, mark the reference as optional:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true
```

Apply the updated manifest (or edit the pod/controller). With `optional: true` the pod will start even when the ConfigMap is absent.

### After the fix

1. **Delete the stuck pod** (the controller will recreate it, or you can recreate it manually):

   ```bash
   kubectl delete pod s07-missing-cm -n kubexplain-eval
   ```

2. **Verify the new pod** comes up cleanly:

   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -w
   ```

   You should see the status transition to `Running` with no `CreateContainerConfigError`.

### TL;DR

- **Root cause:** The required ConfigMap `configmap-care-nu-exista` does not exist in namespace `kubexplain-eval`.
- **Fix:** Create the ConfigMap, point the pod to an existing one, or mark the reference as optional, then restart/recreate the pod.
