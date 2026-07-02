# s12 | mode=none | run=2 | 6.6s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

### What the HPA is telling you

| Field | Value | Meaning |
|------|-------|---------|
| **scaleTargetRef** | `Deployment/deployment-care-nu-exista` | The HPA is trying to control a Deployment named **deployment‑care‑nu‑exista** in the same namespace (`kubexplain-eval`). |
| **Conditions – AbleToScale** | `False` – *FailedGetScale* | The controller could not read the current replica count because the target object does not exist. |
| **Events** | `FailedGetScale … "deployment-care-nu-exista" not found` | Same problem – the API server returned *NotFound* when the HPA asked for the Deployment’s scale sub‑resource. |
| **Metrics** | `<unknown> / 50%` | No metric data can be collected because the HPA never reaches the step where it looks at pod metrics (the target is missing). |
| **Deployment pods** | `0 current / 0 desired` | The HPA reports zero pods because it cannot locate the Deployment at all. |

**Root cause:** The HPA points to a Deployment that does not exist (or exists in a different namespace). Because the target cannot be found, the HPA never evaluates metrics and therefore never creates or removes replicas.

---

### How to fix it

1. **Confirm the intended target**  
   - List the Deployments in the namespace and verify the correct name:
   ```bash
   kubectl get deployments -n kubexplain-eval
   ```
   - If the Deployment you actually want to autoscale is named something else (e.g., `deployment-care-nu-exista-prod`), note that name.

2. **Either create the missing Deployment**  
   - If the Deployment truly should be called `deployment-care-nu-exista`, create it (or apply the manifest you intended). Once the Deployment exists, the HPA will start working automatically.

3. **Or update the HPA to point to the correct Deployment**  
   - Edit the HPA and change the `scaleTargetRef.name` field to the existing Deployment’s name:
   ```bash
   kubectl edit hpa s12-hpa -n kubexplain-eval
   ```
   - In the editor, locate:
   ```yaml
   spec:
     scaleTargetRef:
       apiVersion: apps/v1
       kind: Deployment
       name: deployment-care-nu-exista   # <-- change this
   ```
   - Replace the name with the correct Deployment name, save and exit.

4. **Verify the fix**  
   - After the target exists and the HPA references it correctly, you should see the HPA’s `AbleToScale` condition become `True` and the `Metrics` line show actual values (e.g., `30% / 50%`).  
   - You can watch the HPA status:
   ```bash
   kubectl get hpa s12-hpa -n kubexplain-eval -w
   ```

5. **Optional: Ensure metric collection works**  
   - The HPA is using the built‑in CPU utilization metric, which requires each pod in the target Deployment to have a **CPU request** defined. If the Deployment’s pod spec lacks a request, the metric will stay `<unknown>`. Add a request, e.g.:
   ```yaml
   resources:
     requests:
       cpu: "100m"
   ```
   - Apply the updated Deployment manifest and let the HPA recalculate.

---

### TL;DR

- **Problem:** HPA `s12-hpa` references a non‑existent Deployment (`deployment-care-nu-exista`), so it cannot get the current replica count and never scales.
- **Fix:** Either create the missing Deployment or edit the HPA to point to an existing Deployment (and ensure the pods have CPU requests). After the reference is valid, the HPA will start scaling as expected.
