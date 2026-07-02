# s07 | mode=none | run=2 | 6.4s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What is happening?

- The pod **s07‑missing‑cm** reaches the *Pending* → *Running* transition, the image is pulled successfully, but the container never starts.
- The container’s state is **CreateContainerConfigError**.
- The event log shows the exact reason:

```
Warning  Failed  …  kubelet  Error: configmap "configmap-care-nu-exista" not found
```

The pod spec contains an **envFrom** entry:

```yaml
envFrom:
  - configMapRef:
      name: configmap-care-nu-exista   # <- required ConfigMap
      optional: false                 # default
```

Because the ConfigMap **configmap-care-nu-exista** does not exist in the namespace **kubexplain-eval**, the kubelet cannot build the container’s environment and aborts container creation, resulting in the `CreateContainerConfigError`.

### How to fix it

You have three practical options:

1. **Create the missing ConfigMap** (the intended solution).  
   Provide the key‑value data the pod expects, then the pod will start normally.

2. **Change the pod to reference an existing ConfigMap** (if the name was a typo or the ConfigMap is defined elsewhere).  
   Update the pod (or its owning controller, e.g., Deployment, Job, etc.) to use the correct ConfigMap name.

3. **Make the reference optional** (if the environment variables are truly optional).  
   Setting `optional: true` tells the kubelet to ignore a missing ConfigMap, allowing the pod to start without those variables.

### Example commands (choose the approach you need)

#### 1. Create the ConfigMap

```bash
kubectl create configmap configmap-care-nu-exista \
  --from-literal=KEY1=value1 \
  --from-literal=KEY2=value2 \
  -n kubexplain-eval
```

*(Replace the literals with the actual data your application requires.)*

#### 2. Patch the pod (or its controller) to use a different ConfigMap

```bash
kubectl patch pod s07-missing-cm -n kubexplain-eval \
  --type='json' -p='[{"op":"replace","path":"/spec/containers/0/envFrom/0/configMapRef/name","value":"existing-configmap"}]'
```

*(If the pod is managed by a Deployment, StatefulSet, etc., patch that resource instead.)*

#### 3. Make the reference optional

```bash
kubectl patch pod s07-missing-cm -n kubexplain-eval \
  --type='json' -p='[{"op":"add","path":"/spec/containers/0/envFrom/0/configMapRef/optional","value":true}]'
```

*(Again, patch the owning controller if the pod is not standalone.)*

### After fixing

- Delete the failing pod (or let the controller recreate it) so a fresh pod is scheduled with the corrected configuration:

```bash
kubectl delete pod s07-missing-cm -n kubexplain-eval
```

- Verify that the new pod reaches **Running** and that the container’s `READY` state becomes `True`.

### Summary

- **Root cause:** The pod references a non‑existent ConfigMap (`configmap-care-nu-exista`) via `envFrom`, causing a `CreateContainerConfigError`.
- **Fix:** Create the ConfigMap, correct the reference, or mark it optional, then restart the pod. Once the ConfigMap is available (or the reference is optional), the container will start successfully.
