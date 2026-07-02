# s12 | mode=none | run=3 | 6.7s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### What the output is telling you  

* **Target not found** – The HPA’s `scaleTargetRef` points to a Deployment called **`deployment-care-nu-exista`** in the same namespace (`kubexplain-eval`).  
* The controller tries to read the current replica count of that Deployment (`deployments/scale.apps "deployment-care-nu-exista"`), but the API returns **“not found.”**  
* Because the HPA cannot obtain the current scale, it cannot evaluate the CPU metric, cannot compute a desired replica count, and therefore never scales anything.  
* The `Deployment pods: 0 current / 0 desired` line is a consequence of the missing target – there is no Deployment for the HPA to manage.

### Root cause  

The Deployment referenced by the HPA does **not exist** (or exists under a different name/namespace). Consequently the HPA cannot fetch the scale sub‑resource, so it stays idle.

### How to fix it  

1. **Confirm the intended target**  
   * Verify the name of the Deployment you actually want to autoscale.  
   * Make sure it is in the same namespace (`kubexplain-eval`) as the HPA, or adjust the HPA to point to the correct namespace (the HPA cannot reference a resource in another namespace).

2. **Either create the missing Deployment**  
   * If the Deployment should be called `deployment-care-nu-exista`, create it (or rename an existing one).  
   * Ensure the Deployment defines **CPU requests** for its containers, because the HPA is using the `resource cpu` metric.

   Example (adjust the spec to your application):

   ```bash
   kubectl apply -f - <<EOF
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
           image: your-image:tag
           resources:
             requests:
               cpu: 100m
               memory: 128Mi
   EOF
   ```

3. **Or update the HPA to point to the correct Deployment**  
   * If the Deployment already exists under a different name (e.g., `deployment-care-nu-exista-v2`), edit the HPA’s `scaleTargetRef` to reference that name.

   ```bash
   kubectl edit hpa s12-hpa -n kubexplain-eval
   ```

   Change the block:

   ```yaml
   spec:
     scaleTargetRef:
       apiVersion: apps/v1
       kind: Deployment
       name: <correct-deployment-name>
   ```

4. **Validate the fix**  

   * After the target exists and the HPA can read its scale, you should see the condition `AbleToScale` become `True`.  
   * The HPA will start reporting current CPU usage (e.g., `resource cpu on pods: 30% / 50%`) and will adjust replicas within the `minReplicas`/`maxReplicas` range.

   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

   Look for:

   ```
   Conditions:
     Type         Status  Reason   Message
     ----         ------  ------   -------
     AbleToScale  True    ...
   ```

### Summary  

The HPA cannot scale because its target Deployment (`deployment-care-nu-exista`) does not exist. Create the Deployment with that exact name (and proper CPU requests) **or** modify the HPA to reference the correct existing Deployment. Once the target is reachable, the HPA will be able to fetch the current replica count, evaluate the CPU metric, and perform scaling as expected.
