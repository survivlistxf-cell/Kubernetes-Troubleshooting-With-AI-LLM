# s07 | mode=static | run=2 | 7.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What the pod description tells us

| Field | Value | Meaning |
|-------|-------|---------|
| **State** | `Waiting` – `Reason: CreateContainerConfigError` | The kubelet could not build the container’s runtime configuration. |
| **Events** | `Warning  Failed … Error: configmap "configmap-care-nu-exista" not found` | The only error reported is that a ConfigMap referenced by the pod does not exist. |
| **Environment Variables from** | `configmap-care-nu-exista  ConfigMap  Optional: false` | The container tries to import all key‑value pairs from a ConfigMap named **configmap-care-nu-exista**. Because `Optional: false`, the pod will not start if the ConfigMap is missing. |

#### Root cause
The pod references a **non‑existent ConfigMap** (`configmap-care-nu-exista`) in the same namespace (`kubexplain-eval`). Since the reference is mandatory, the kubelet aborts container creation with `CreateContainerConfigError`.

### How to fix it

You have three options, depending on what the intended behavior is.

---

#### 1. **Create the missing ConfigMap** (most common)

If the pod is supposed to receive environment variables from that ConfigMap, create it with the required data.

```bash
# Example: create a ConfigMap with a couple of dummy keys
kubectl create configmap configmap-care-nu-exista \
  --from-literal=FOO=bar \
  --from-literal=BAZ=qux \
  -n kubexplain-eval
```

Or apply a manifest you already have:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: configmap-care-nu-exista
  namespace: kubexplain-eval
data:
  # put the key/value pairs the pod expects here
  SOME_KEY: some-value
  OTHER_KEY: other-value
```

```bash
kubectl apply -f configmap.yaml
```

After the ConfigMap exists, the pod will transition from `Pending` → `Running` (or you may need to delete the pod so it is recreated).

---

#### 2. **Make the reference optional**

If the pod can run without those environment variables, change the pod spec to mark the ConfigMap as optional:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true
```

Apply the updated pod (or Deployment, StatefulSet, etc.):

```bash
kubectl apply -f your-pod-or-controller.yaml
```

With `optional: true`, the pod will start even when the ConfigMap is absent.

---

#### 3. **Remove the ConfigMap reference**

If the pod does not need any data from that ConfigMap, simply delete the `envFrom` entry from the container spec and redeploy.

```yaml
# remove the whole envFrom block or replace it with other env definitions
```

Apply the corrected manifest as in option 2.

---

### Verify the fix

```bash
# Check that the ConfigMap now exists (option 1)
kubectl get configmap configmap-care-nu-exista -n kubexplain-eval

# Watch the pod status until it becomes Running
kubectl get pod s07-missing-cm -n kubexplain-eval -w
```

When the pod reaches `Running` and the `Ready` condition is `True`, the issue is resolved.

--- 

**Bottom line:** The pod fails because it references a missing ConfigMap (`configmap-care-nu-exista`). Create that ConfigMap, make the reference optional, or remove the reference, then redeploy or let the pod be recreated. Once the ConfigMap issue is cleared, the container will start normally.
