# s07 | mode=none | run=3 | 7.4s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

### What is happening?

- The pod is stuck in **`CreateContainerConfigError`** while trying to start the `app` container.  
- The only event that explains the failure is:

```
Error: configmap "configmap-care-nu-exista" not found
```

- In the pod description you can see that the container is pulling environment variables **from a ConfigMap**:

```
Environment Variables from:
  configmap-care-nu-exista  ConfigMap  Optional: false
```

Because `Optional: false`, the kubelet requires that the ConfigMap **must exist** in the same namespace (`kubexplain-eval`). Since it does not, the pod cannot build a valid container spec, and the kubelet aborts container creation with `CreateContainerConfigError`.

### Root cause

The ConfigMap named **`configmap-care-nu-exista`** is missing in the `kubexplain-eval` namespace (or it exists in a different namespace). The pod’s spec is hard‑wired to import its environment from that ConfigMap, and the `Optional: false` flag tells Kubernetes that the ConfigMap is mandatory.

### How to fix it

You have three practical options:

1. **Create the missing ConfigMap** (the simplest fix if you actually need those env vars).  
2. **Edit the pod (or its controller) to reference an existing ConfigMap** or remove the `envFrom` block if the variables are not required.  
3. **Mark the reference as optional** so the pod can start even when the ConfigMap is absent.

Below are the typical commands for each approach.

#### 1. Create the ConfigMap

If you know the key/value pairs that should be provided, create it in the same namespace:

```bash
kubectl create configmap configmap-care-nu-exista \
  --from-literal=MY_VAR=somevalue \
  -n kubexplain-eval
```

*(Replace `--from-literal` or `--from-file` arguments with the actual data you need.)*

After the ConfigMap exists, delete the failing pod so it can be recreated (or let the controller recreate it):

```bash
kubectl delete pod s07-missing-cm -n kubexplain-eval
```

The new pod should start normally.

#### 2. Change the pod spec to use a different ConfigMap or remove the reference

If the pod is managed by a higher‑level controller (Deployment, Job, etc.), edit that controller:

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

- Locate the `envFrom` section under the container spec.
- Either change `name: configmap-care-nu-exista` to a ConfigMap that actually exists, or delete the whole `envFrom` block if you don’t need those variables.

Save the edit; the controller will roll out a new replica set and the pod will be recreated with the corrected spec.

#### 3. Make the ConfigMap reference optional

If you want the pod to start even when the ConfigMap is absent, set `optional: true` in the `envFrom` entry:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true
```

You can apply the change with `kubectl patch` (if the pod is part of a controller, patch the controller instead):

```bash
kubectl patch deployment <deployment-name> -n kubexplain-eval \
  --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/envFrom/0/configMapRef/optional","value":true}]'
```

After the patch, the pod will be recreated and will start without the ConfigMap.

### Quick checklist

- [ ] Verify the ConfigMap name and namespace (`kubectl get cm -n kubexplain-eval`).
- [ ] Decide whether you need the ConfigMap:
  - **Yes** → create it with the required data.
  - **No / different name** → edit the pod/controller to point to the correct ConfigMap or remove the reference.
  - **Optional** → set `optional: true`.
- [ ] Delete the failing pod (or let the controller roll out) so a new pod is created with the corrected configuration.

Once the ConfigMap issue is resolved, the pod should transition from `Pending` → `Running` and the `app` container will execute `sleep 3600` as expected.
