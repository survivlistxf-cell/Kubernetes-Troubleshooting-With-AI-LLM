# s12 | mode=dynamic | run=1 | 118.9s | needs_search=1

**Q:** My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not scale anything. Its describe output is attached. What is the root cause and how do I fix it?

**Expected:** HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef

---

**Root cause**

The HPA cannot find the workload it is supposed to scale:

```
AbleToScale  False  FailedGetScale  the HPA controller was unable to get the target's current scale:
deployments/scale.apps "deployment-care-nu-exista" not found
```

`scaleTargetRef` in the HPA points to a **Deployment named `deployment-care-nu-exista`** in the same namespace, but that Deployment does not exist (or is named differently, or is in another namespace). Because the controller cannot retrieve the target’s scale, it never calculates a replica count, so the HPA stays at 0 replicas.

**How to fix it**

1. **Confirm whether the Deployment exists**  

   ```bash
   kubectl get deployment deployment-care-nu-exista -n kubexplain-eval
   ```

   *If the command returns “NotFound”, the Deployment is missing or the name is wrong.*

2. **If the Deployment is missing** – create it (or rename the existing one) so that its name matches the HPA’s `scaleTargetRef`. Example:

   ```bash
   # Example manifest – adjust spec as needed
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
           image: your-image:tag
           resources:
             requests:
               cpu: 200m
   EOF
   ```

3. **If the Deployment exists but under a different name** – edit the HPA to point to the correct Deployment:

   ```bash
   kubectl edit hpa s12-hpa -n kubexplain-eval
   ```

   In the editor change the `scaleTargetRef` section, e.g.:

   ```yaml
   spec:
     scaleTargetRef:
       apiVersion: apps/v1
       kind: Deployment
       name: <actual-deployment-name>
   ```

   Save and exit.

4. **Alternatively, recreate the HPA with the correct reference** (useful if you prefer an imperative command):

   ```bash
   # Delete the broken HPA
   kubectl delete hpa s12-hpa -n kubexplain-eval

   # Re‑create it, pointing at the right Deployment
   kubectl autoscale deployment <actual-deployment-name> \
       --cpu-percent=50 --min=1 --max=5 -n kubexplain-eval
   ```

5. **Verify the fix**  

   ```bash
   kubectl describe hpa s12-hpa -n kubexplain-eval
   ```

   You should now see `AbleToScale  True` and the HPA will start adjusting the replica count based on CPU utilization.

---

### Quick checklist

- ✅ The Deployment named in `scaleTargetRef` exists in the same namespace.  
- ✅ The Deployment’s name matches exactly (case‑sensitive).  
- ✅ The Deployment has a valid `spec.replicas` field (or the field is omitted so the HPA can control it).  
- ✅ The HPA’s `minReplicas` and `maxReplicas` are appropriate for the workload.  

Once the target Deployment is reachable, the HPA will be able to fetch its current scale, compute the desired replica count, and perform scaling as expected.
