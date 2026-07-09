# s12 | mode=static | run=2 | 5.8s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### What the HPA output is telling you  

| Field | What it means |
|-------|---------------|
| **Reference** | The HPA is attached to `Deployment/deployment-care-nu-exista`. |
| **Conditions – AbleToScale = False / Reason = FailedGetScale** | The controller tried to read the current replica count of the target object, but the API returned *“deployments/scale.apps "deployment‑care‑nu‑exista" not found”*. |
| **Metrics – cpu <unknown> / 50%** | Because the target object cannot be fetched, the HPA cannot read the pods’ CPU requests, so the current metric is shown as `<unknown>`. |
| **Deployment pods – 0 current / 0 desired** | No Deployment exists, therefore there are no pods for the HPA to monitor. |

**Root cause:**  
The HPA points to a Deployment that does **not exist** in the `kubexplain-eval` namespace (`deployment-care-nu-exista`). Because the target cannot be retrieved, the HPA cannot obtain replica information or metric data, so it never scales.

### How to fix it

1. **Create the missing Deployment** (or rename an existing one) so that the name matches the HPA’s `scaleTargetRef`.  
   - If the Deployment should already exist, verify its name and namespace and correct any typo.  
   - If the Deployment is supposed to be created later, you can temporarily disable the HPA or set `minReplicas: 0` until the Deployment appears.

2. **Or update the HPA to point to the correct target**:  
   - Edit the HPA (`kubectl edit hpa s12-hpa -n kubexplain-eval`) and change the `scaleTargetRef` fields (`apiVersion`, `kind`, `name`) to the actual Deployment name.  
   - Save the change; the HPA will immediately start watching the correct object.

3. **Verify that the target Deployment defines CPU requests** (or the metric you intend to use).  
   - The HPA can only compute a percentage if each pod has a `resources.requests.cpu` value.  
   - If you plan to use a custom metric, make sure the appropriate metric provider is installed.

4. **Confirm the HPA works**:  

   ```bash
   # After fixing the target, watch the HPA status
   kubectl get hpa s12-hpa -n kubexplain-eval -w
   ```

   You should see the `AbleToScale` condition become `True` and the current/target metrics populated.

### Quick checklist

- [ ] Does a Deployment named `deployment-care-nu-exista` exist in `kubexplain-eval`?  
- [ ] If not, create it **or** change the HPA’s `scaleTargetRef` to an existing Deployment.  
- [ ] Ensure the Deployment’s pod spec includes `resources.requests.cpu` (or the metric you want).  
- [ ] Re‑apply the HPA (or let the edit command apply it) and watch the status.

Once the HPA can successfully fetch the target’s scale, it will start evaluating the CPU usage and will scale the replicas between 1 and 5 as defined.
