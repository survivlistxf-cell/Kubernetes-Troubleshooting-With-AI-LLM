# s12 | mode=static | run=3 | 5.7s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### Root cause
The HPA **s12‑hpa** is configured to scale the Deployment **deployment‑care‑nu‑exista**:

```yaml
spec:
  scaleTargetRef:
    kind: Deployment
    name: deployment-care-nu-exista
```

The controller cannot find that Deployment:

```
FailedGetScale … deployments/scale.apps "deployment-care-nu-exista" not found
```

Because the target object does not exist, the HPA cannot:

* read the current replica count,
* collect the CPU metric (the metric line shows `<unknown>`), or
* decide whether to increase or decrease replicas.

Therefore the HPA never performs any scaling action.

### How to fix it

1. **Create the missing Deployment** (if it is the intended target) **or**  
   **Update the HPA to point to an existing Deployment**.

   - *Option A – create the Deployment*  
     Deploy the workload with the exact name `deployment-care-nu-exista` in the same namespace (`kubexplain-eval`). Ensure the pods define a CPU request, because the HPA uses the `resource.cpu` metric.

   - *Option B – change the HPA target*  
     Edit the HPA so that `spec.scaleTargetRef.name` matches the name of a Deployment that already exists (or another scalable resource such as a StatefulSet).

2. **Verify the target has CPU requests**  
   The HPA is using `resource cpu` as a percentage of request. If the pods do not declare a `resources.requests.cpu`, the metric will stay `<unknown>` even after the target exists. Add a request, e.g.:

   ```yaml
   resources:
     requests:
       cpu: "100m"
   ```

3. **Re‑apply the corrected objects**  

   ```bash
   # If you edited the HPA
   kubectl apply -f s12-hpa.yaml -n kubexplain-eval

   # If you created/updated the Deployment
   kubectl apply -f deployment-care-nu-exista.yaml -n kubexplain-eval
   ```

4. **Confirm the HPA works**

   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

   You should now see a valid `current / target` CPU value and the `AbleToScale` condition set to `True`. When the workload generates CPU load above the 50 % target, the replica count will increase up to the defined `maxReplicas`.

### Summary
The HPA cannot scale because its `scaleTargetRef` points to a non‑existent Deployment. Create that Deployment or point the HPA at an existing one (and ensure the pods have CPU requests). Once the target exists, the HPA will be able to read the scale, compute metrics, and perform scaling as expected.
