# s07 | mode=static | run=3 | 8.8s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What the pod description tells us

| Field | Value | Meaning |
|-------|-------|---------|
| **State** | `Waiting` – Reason: `CreateContainerConfigError` | The kubelet could not build the container’s runtime configuration. |
| **Environment Variables from** | `configmap-care-nu-exista` (Optional: **false**) | The container is trying to import all key‑value pairs from a ConfigMap named **configmap-care-nu-exista**. |
| **Events** | `Error: configmap "configmap-care-nu-exista" not found` | The kubelet looked for that ConfigMap in the pod’s namespace (`kubexplain-eval`) and did not find it. |

**Root cause** – The pod references a ConfigMap that does **not exist** in the `kubexplain-eval` namespace, and the reference is marked as required (`Optional: false`). Because the ConfigMap cannot be loaded, the kubelet aborts container creation with `CreateContainerConfigError`.

### How to fix it

You have three typical options:

1. **Create the missing ConfigMap** (if it is supposed to be there).  
   Provide the key/value data the pod expects, then the pod will start normally.

2. **Edit the pod (or its controller – Deployment, Job, etc.) to reference an existing ConfigMap**.  
   Change the `envFrom` entry to a ConfigMap that already exists, or remove the entry if the environment variables are not needed.

3. **Make the reference optional** (only if the pod can run without those variables).  
   Set `optional: true` in the `envFrom` block so the pod will start even when the ConfigMap is absent.

Below are the concrete steps for each approach.

---

#### 1. Create the missing ConfigMap

```bash
# Example: create a ConfigMap with a couple of dummy keys.
kubectl create configmap configmap-care-nu-exista \
  --from-literal=FOO=bar \
  --from-literal=BAZ=qux \
  -n kubexplain-eval
```

*If you know the exact keys the application expects, replace the `--from-literal` arguments with the appropriate data (or use `--from-file` / `--from-env-file`).*

After the ConfigMap exists, the pod will be retried automatically and should transition to `Running`.

---

#### 2. Change the pod spec to use an existing ConfigMap

If you have a ConfigMap that already contains the needed data (e.g., `configmap-care`), edit the pod’s manifest (or the Deployment/ReplicaSet that creates it):

```yaml
envFrom:
- configMapRef:
    name: configmap-care   # <-- replace with a ConfigMap that exists
    optional: false
```

Apply the updated manifest:

```bash
kubectl apply -f <updated‑manifest>.yaml
```

If the pod is managed by a higher‑level controller, delete the failing pod so the controller recreates it with the new spec:

```bash
kubectl delete pod s07-missing-cm -n kubexplain-eval
```

---

#### 3. Make the ConfigMap reference optional

If the container can run without those environment variables, modify the pod spec:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true   # <-- make it optional
```

Apply the change (or delete & let the controller recreate) as shown above. With `optional: true`, the kubelet will ignore the missing ConfigMap and continue to start the container.

---

### Quick verification checklist

1. **Confirm the ConfigMap exists (or not)**  

   ```bash
   kubectl get configmap configmap-care-nu-exista -n kubexplain-eval
   ```

2. **Check the pod’s environment configuration**  

   ```bash
   kubectl describe pod s07-missing-cm -n kubexplain-eval | grep -A2 "Environment Variables from"
   ```

3. **Watch the pod after fixing**  

   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -w
   ```

When the ConfigMap issue is resolved, the pod’s status should move from `Pending` → `Running` and the container will show `State: Running`.

---

### TL;DR

- **Root cause:** The pod tries to import environment variables from a non‑existent ConfigMap (`configmap-care-nu-exista`).  
- **Fix:** Create that ConfigMap, point the pod to a ConfigMap that does exist, or mark the reference as optional. After correcting the ConfigMap reference, the pod will start successfully.
