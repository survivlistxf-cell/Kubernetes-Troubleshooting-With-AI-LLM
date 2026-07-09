# s07 | mode=dynamic | run=3 | 5.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

**Root cause**

The pod is trying to populate environment variables from a ConfigMap named **`configmap-care-nu-exista`**:

```
Environment Variables from:
  configmap-care-nu-exista  ConfigMap  Optional: false
```

The kubelet reports:

```
Error: configmap "configmap-care-nu-exista" not found
```

Because the ConfigMap does not exist in the `kubexplain-eval` namespace, the pod cannot build a valid container spec, so the container stays in **`CreateContainerConfigError`**.

**How to fix it**

1. **Create the missing ConfigMap** (or correct the name if it was mistyped).  
   Example – create a simple ConfigMap with a key/value you need:

```bash
kubectl create configmap configmap-care-nu-exista \
  --from-literal=MY_VAR=somevalue \
  -n kubexplain-eval
```

   Or apply a manifest you already have:

```bash
cat <<'EOF' > cm.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: configmap-care-nu-exista
  namespace: kubexplain-eval
data:
  MY_VAR: somevalue
EOF

kubectl apply -f cm.yaml
```

2. **If the ConfigMap is optional**, change the pod spec to mark it optional so the pod can start even when the ConfigMap is absent:

```yaml
envFrom:
- configMapRef:
    name: configmap-care-nu-exista
    optional: true
```

   Then redeploy the pod.

3. **Verify** that the ConfigMap now exists:

```bash
kubectl get configmap configmap-care-nu-exista -n kubexplain-eval
```

4. **Delete the failing pod** (or let it be recreated) so that a new pod is scheduled with the corrected configuration:

```bash
kubectl delete pod s07-missing-cm -n kubexplain-eval
```

   The controller (e.g., a Deployment) will create a fresh pod that should reach the **Running** state.

**Summary**

The pod fails because the required ConfigMap `configmap-care-nu-exista` is missing. Create the ConfigMap (or adjust the pod to reference an existing one or make it optional) and then restart/recreate the pod. Once the ConfigMap is present, the container will start normally.
