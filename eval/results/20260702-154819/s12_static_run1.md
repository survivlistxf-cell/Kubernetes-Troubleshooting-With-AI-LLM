# s12 | mode=static | run=1 | 4.5s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### Root cause  
The HPA **s12‑hpa** is configured to scale the object **Deployment/deployment‑care‑nu‑exista**, but that Deployment does not exist in the `kubexplain-eval` namespace:

```
AbleToScale  False   FailedGetScale  the HPA controller was unable to get the target's current scale:
deployments/scale.apps "deployment-care-nu-exista" not found
```

Because the HPA cannot retrieve the current replica count (or the pod metrics) from a non‑existent target, it never evaluates the CPU usage and therefore never creates or removes replicas.

### How to fix it  

1. **Create the missing Deployment** (or rename an existing one) so that the name matches exactly what the HPA expects.  
   - Ensure the Deployment is in the same namespace (`kubexplain-eval`).  
   - Give the pods a **CPU request** (the HPA is using `resource cpu` as a percentage of request). Without a request the metric would be `<unknown>` even after the Deployment exists.

2. **Or, update the HPA to point to the correct target** if the intended Deployment has a different name.  
   - Edit the HPA’s `spec.scaleTargetRef.name` to the actual Deployment name.  
   - Verify that the target Deployment has the required `spec.replicas` field (the HPA will manage it).

3. **Validate after the change**  
   - Run `kubectl get hpa s12-hpa -n kubexplain-eval -o yaml` and confirm that `AbleToScale` becomes `True`.  
   - Check that the `Metrics` line now shows a concrete current value (e.g., `30% / 50%`).  
   - Trigger load (or wait for normal traffic) and watch the replica count change with `kubectl get deployment <name> -n kubexplain-eval -w`.

### Summary  
The HPA cannot scale because its target Deployment (`deployment-care-nu-exista`) is missing. Create that Deployment (or correct the HPA’s reference) and ensure the pods define CPU requests. Once the target exists, the HPA will be able to read the current scale, compute the metric, and perform scaling as expected.
