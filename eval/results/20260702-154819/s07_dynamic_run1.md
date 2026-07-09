# s07 | mode=dynamic | run=1 | 5.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it?

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

**Root cause**

The pod `s07-missing-cm` is trying to populate environment variables from a ConfigMap named **`configmap-care-nu-exista`**:

```
Environment Variables from:
  configmap-care-nu-exista  ConfigMap  Optional: false
```

Because the ConfigMap does **not exist** in the `kubexplain-eval` namespace, the kubelet cannot build the container’s configuration and reports:

```
Reason: CreateContainerConfigError
Message: configmap "configmap-care-nu-exista" not found
```

When a required ConfigMap is missing, the pod stays in *Pending* with the `CreateContainerConfigError` state, and the container never starts.

---

**How to fix it**

You have three practical options:

1. **Create the missing ConfigMap** (the simplest fix if the pod really needs those variables).

   ```bash
   kubectl create configmap configmap-care-nu-exista \
     --from-literal=KEY1=value1 \
     --from-literal=KEY2=value2 \
     -n kubexplain-eval
   ```

   Adjust the `--from-*` flags to contain the actual key/value pairs the pod expects.

2. **Update the pod (or its owning controller) to reference an existing ConfigMap**  
   If you have a ConfigMap with the correct data under a different name, edit the pod spec (or the Deployment/Job/ReplicaSet that creates it) and change the `configMapRef.name` to the correct name, then redeploy.

   ```bash
   kubectl edit pod s07-missing-cm -n kubexplain-eval
   # locate the envFrom or env section and replace
   #   name: configmap-care-nu-exista
   # with the correct ConfigMap name, then save.
   ```

3. **Make the reference optional**  
   If the environment variables are not essential, you can mark the ConfigMap as optional. The pod will start even when the ConfigMap is absent.

   ```yaml
   envFrom:
   - configMapRef:
       name: configmap-care-nu-exista
       optional: true
   ```

   Apply the updated manifest (or edit the pod/controller) and the pod will transition to `Running`.

---

**Verification**

After applying one of the fixes, watch the pod:

```bash
kubectl get pod s07-missing-cm -n kubexplain-eval -w
```

You should see the `STATUS` change from `Pending` to `Running`, and the container’s `STATE` become `Running` (or `Completed` if the `sleep 3600` finishes). No further `CreateContainerConfigError` events should appear.
