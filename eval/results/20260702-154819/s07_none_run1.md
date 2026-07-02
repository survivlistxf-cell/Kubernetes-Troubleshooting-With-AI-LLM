# s07 | mode=none | run=1 | 6.1s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What the pod description tells us  

* **State:** `CreateContainerConfigError` – the kubelet could not build the container’s runtime configuration.  
* **Event:** `Error: configmap "configmap-care-nu-exista" not found` – the pod’s container tries to import environment variables from a ConfigMap that does not exist in the pod’s namespace (`kubexplain-eval`).  

Because the ConfigMap is missing, the kubelet aborts container creation, so the pod never moves past the *Pending* phase.

### Root cause  

The pod spec contains:

```yaml
envFrom:
  - configMapRef:
      name: configmap-care-nu-exista   # <-- required ConfigMap
      optional: false
```

Kubernetes looks for a ConfigMap named **configmap-care-nu-exista** in the same namespace. Since it cannot be found, the pod fails with `CreateContainerConfigError`.

### How to fix it  

You have three practical options:

1. **Create the missing ConfigMap** (if the data it should contain is known).  
2. **Change the pod spec to reference an existing ConfigMap** (or remove the reference if it isn’t needed).  
3. **Mark the reference as optional** so the pod can start even when the ConfigMap is absent.

Below are the typical commands for each approach.

#### 1. Create the ConfigMap  

If you know the key‑value pairs that should be in the ConfigMap, create it in the same namespace:

```bash
kubectl create configmap configmap-care-nu-exista \
  --from-literal=MY_VAR=somevalue \
  -n kubexplain-eval
```

*(Replace `--from-literal` or `--from-file` arguments with the actual data you need.)*

#### 2. Edit the pod (or its controller) to use a different ConfigMap  

If the pod is managed by a Deployment, StatefulSet, etc., edit that controller:

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

* Locate the `envFrom` / `env` block that references `configmap-care-nu-exista` and either:  
  * change `name:` to an existing ConfigMap name, or  
  * remove the block entirely if the environment variables are not required.

After saving, the controller will recreate the pod with the corrected spec.

#### 3. Make the reference optional  

If the environment variables are truly optional, set `optional: true` in the pod spec:

```yaml
envFrom:
  - configMapRef:
      name: configmap-care-nu-exista
      optional: true
```

You can apply the change with:

```bash
kubectl patch pod s07-missing-cm \
  -n kubexplain-eval \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/containers/0/envFrom/0/configMapRef/optional","value":true}]'
```

*(If the pod is managed by a higher‑level controller, patch the controller instead.)*

### Verify the fix  

After applying one of the fixes, watch the pod:

```bash
kubectl get pod s07-missing-cm -n kubexplain-eval -w
```

The pod should transition to `Running` once the container is created successfully.

---

**Summary:** The pod fails because it references a non‑existent ConfigMap (`configmap-care-nu-exista`). Create that ConfigMap, point the pod to an existing one, or mark the reference as optional to resolve the `CreateContainerConfigError`. Once the ConfigMap issue is addressed, the container will start normally.
