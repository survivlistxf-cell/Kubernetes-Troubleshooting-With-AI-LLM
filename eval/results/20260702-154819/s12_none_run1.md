# s12 | mode=none | run=1 | 6.5s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### What the output tells us  

* **Target not found** – The HPA tries to control the object `Deployment/deployment-care-nu-exista`, but that Deployment does not exist in the `kubexplain-eval` namespace.  
* Because the controller cannot retrieve the current replica count (`FailedGetScale`), it cannot evaluate any scaling metric, so the HPA stays idle.  
* The metric line shows `<unknown>` for CPU because the HPA never reaches the point where it can read pod metrics – the missing target stops the whole process.

### Root cause  

The HPA’s `spec.scaleTargetRef` points to a non‑existent Deployment (or to a Deployment that has been deleted/renamed). Without a valid scale target the HPA controller cannot:

1. Read the current replica count.  
2. Collect pod metrics (CPU, memory, custom metrics).  
3. Decide whether to increase or decrease replicas.

### How to fix it  

1. **Confirm the intended workload**  
   * Decide which Deployment (or other scalable resource such as a StatefulSet or ReplicaSet) the HPA should manage.  
   * Verify its name and namespace.

2. **Either create the missing Deployment**  
   * If `deployment-care-nu-exista` is the correct name, create a Deployment with that name in the same namespace, making sure it defines CPU requests (so the HPA can compute the percentage metric).  

   ```bash
   kubectl apply -f - <<'EOF'
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: deployment-care-nu-exista
     namespace: kubexplain-eval
   spec:
     replicas: 1
     selector:
       matchLabels:
         app: care-nu-exista
     template:
       metadata:
         labels:
           app: care-nu-exista
       spec:
         containers:
         - name: app
           image: <your-image>
           resources:
             requests:
               cpu: 100m
               memory: 128Mi
   EOF
   ```

3. **Or point the HPA at the correct existing object**  
   * If the Deployment was renamed or you meant to target a different one, edit the HPA to reference the right name.

   ```bash
   kubectl edit hpa s12-hpa -n kubexplain-eval
   ```

   In the editor change the `scaleTargetRef` section, e.g.:

   ```yaml
   spec:
     scaleTargetRef:
       apiVersion: apps/v1
       kind: Deployment
       name: <correct-deployment-name>
   ```

4. **Verify the fix**  

   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -o wide
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

   * The `Conditions` section should now show `AbleToScale` = `True`.  
   * The `Metrics` line should display a concrete current CPU percentage (e.g., `30% / 50%`).  
   * If the workload generates load, the replica count will move between the `minReplicas` (1) and `maxReplicas` (5) as expected.

### Summary  

The HPA does not scale because its `scaleTargetRef` points to a Deployment that does not exist (`deployment-care-nu-exista`). Create that Deployment **or** update the HPA to reference an existing Deployment (or other scalable resource). Once the target is reachable, the HPA will be able to read pod metrics and perform scaling according to the defined CPU target.
