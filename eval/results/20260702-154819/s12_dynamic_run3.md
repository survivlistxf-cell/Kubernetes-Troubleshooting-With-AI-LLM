# s12 | mode=dynamic | run=3 | 5.1s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

**Root cause**

The HPA `s12-hpa` is pointing at a target that does not exist:

```
Reference: Deployment/deployment-care-nu-exista
...
AbleToScale  False   FailedGetScale  the HPA controller was unable to get the target's current scale: deployments/scale.apps "deployment-care-nu-exista" not found
```

The controller tries to read the scale sub‑resource of the Deployment **deployment‑care‑nu‑exista** in the same namespace (`kubexplain-eval`). Because that Deployment is missing (or has a different name), the HPA cannot obtain the current replica count, so it never performs any scaling.

**How to fix it**

1. **Verify the intended workload**  
   - List the Deployments that actually exist in the namespace:

   ```bash
   kubectl get deployments -n kubexplain-eval
   ```

   - Identify the correct Deployment name that should be autoscaled (e.g. `deployment-care-nu-exista‑v2` or `deployment-care-nu-exista` that was accidentally deleted).

2. **Either recreate the missing Deployment**  
   - If the Deployment was supposed to exist, apply its manifest again (or run the command that creates it). Once the Deployment appears, the HPA will immediately start reconciling and scaling.

3. **Or update the HPA to point to the correct Deployment**  
   - Edit the HPA’s `scaleTargetRef` to reference the existing Deployment name:

   ```bash
   kubectl edit hpa s12-hpa -n kubexplain-eval
   ```

   Change the block:

   ```yaml
   scaleTargetRef:
     apiVersion: apps/v1
     kind: Deployment
     name: <correct-deployment-name>
   ```

   Save the edit. The HPA will re‑evaluate the target and, assuming metrics are available, will begin scaling.

4. **Confirm the fix**  
   - After the Deployment exists (or the reference is corrected), watch the HPA status:

   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -w
   ```

   You should see the `AbleToScale` condition become `True` and the `Deployment pods` line show the current replica count.

**Summary**

The HPA cannot scale because its `scaleTargetRef` points to a non‑existent Deployment. Create the missing Deployment or correct the HPA’s reference to an existing Deployment, then the HPA will be able to fetch the scale and perform autoscaling as expected.
