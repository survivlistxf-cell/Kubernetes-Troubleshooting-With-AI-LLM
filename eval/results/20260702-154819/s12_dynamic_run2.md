# s12 | mode=dynamic | run=2 | 9.4s | needs_search=0

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

**What the output is telling you**

| Field | Value | Meaning |
|-------|-------|---------|
| **Conditions → AbleToScale** | **False** – *FailedGetScale* | The HPA controller tried to read the scale sub‑resource of the target object (`deployments/scale.apps "deployment-care-nu-exista"`), but that object does not exist. |
| **Reference** | `Deployment/deployment-care-nu-exista` | The HPA is configured to control a Deployment with this exact name in the same namespace (`kubexplain-eval`). |
| **Events** | `FailedGetScale … not found` | Confirms the controller cannot locate the Deployment. |
| **Deployment pods** | `0 current / 0 desired` | Because the target cannot be found, the HPA reports zero pods. |

**Root cause**

The HPA’s `scaleTargetRef` points to a Deployment that does **not exist** (or exists under a different name/namespace). Consequently the controller cannot read or update the replica count, so no scaling ever occurs.

**How to fix it**

1. **Verify the target Deployment**  
   ```bash
   kubectl get deployment deployment-care-nu-exista -n kubexplain-eval
   ```
   *If the command returns “NotFound”, the Deployment really is missing or the name is wrong.*

2. **Either create the missing Deployment** (if the scenario expects it) or **correct the HPA to point at the right object**.

   *a. Create the Deployment (example stub)*  
   ```bash
   cat <<EOF | kubectl apply -f -
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: deployment-care-nu-exista
     namespace: kubexplain-eval
   spec:
     replicas: 1
     selector:
       matchLabels:
         app: care-nu
     template:
       metadata:
         labels:
           app: care-nu
       spec:
         containers:
         - name: app
           image: nginx   # replace with the real image
           resources:
             requests:
               cpu: "200m"
   EOF
   ```

   *b. Or edit the HPA to reference the correct Deployment*  
   ```bash
   # Find the correct Deployment name first
   kubectl get deployments -n kubexplain-eval

   # Then patch the HPA
   kubectl patch hpa s12-hpa -n kubexplain-eval \
     -p '{"spec":{"scaleTargetRef":{"name":"<correct-deployment-name>"}}}' \
     --type=merge
   ```

   *Alternatively, delete and recreate the HPA with the proper `scaleTargetRef`*  
   ```bash
   kubectl delete hpa s12-hpa -n kubexplain-eval
   kubectl apply -f - <<EOF
   apiVersion: autoscaling/v2
   kind: HorizontalPodAutoscaler
   metadata:
     name: s12-hpa
     namespace: kubexplain-eval
   spec:
     scaleTargetRef:
       apiVersion: apps/v1
       kind: Deployment
       name: <correct-deployment-name>
     minReplicas: 1
     maxReplicas: 5
     metrics:
     - type: Resource
       resource:
         name: cpu
         target:
           type: Utilization
           averageUtilization: 50
   EOF
   ```

3. **Confirm the fix**  
   After the target Deployment exists and the HPA points to it, the condition should turn **True** and the HPA will start calculating replica counts.

   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

   You should see something like:

   ```
   Conditions:
     Type         Status  Reason          Message
     ----         ------  ------          -------
     AbleToScale  True    ScalingActive   the HPA was able to fetch and update scales
   ```

   And the “Deployment pods” line will show the actual current/desired replica numbers.

**Summary**

The HPA isn’t scaling because its `scaleTargetRef` points to a non‑existent Deployment (`deployment-care-nu-exista`). Create that Deployment or correct the HPA’s reference, then the HPA will be able to read the scale sub‑resource and perform autoscaling as expected.
