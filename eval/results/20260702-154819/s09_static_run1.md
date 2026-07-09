# s09 | mode=static | run=1 | 7.4s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

**Root cause**

The pod **s09‑bad‑readiness** is running, but its **Readiness** condition is **False**:

```
Readiness: http-get http://:80/pagina-inexistenta delay=0s timeout=1s period=5s
Warning  Unhealthy  …  Readiness probe failed: HTTP probe failed with statuscode: 404
```

The readiness probe is asking the container for the URL **/pagina‑inexistenta** on port 80.  
Nginx (the container image) does not have such a page, so the probe always returns **404**.  

Kubernetes treats a pod whose readiness probe fails as **not ready**, and it places the pod’s IP in the `notReadyAddresses` list of the service’s Endpoints object. Consequently the Service **s09‑web** has no ready endpoints, so no traffic is routed to the pod.

---

**How to fix it**

1. **Make the readiness probe succeed**  
   *Either* change the probe to request a path that actually exists (e.g. “/”) or remove/disable the probe if you don’t need one.

   Example – edit the pod (or, preferably, the owning Deployment/ReplicaSet) and set the probe to “/”:

   ```bash
   kubectl edit pod s09-bad-readiness -n kubexplain-eval
   ```

   In the editor change the `readinessProbe` section to:

   ```yaml
   readinessProbe:
     httpGet:
       path: /
       port: 80
     periodSeconds: 5
     timeoutSeconds: 1
   ```

   Save and exit. The kubelet will re‑run the probe; it will now return **200** and the pod will become Ready.

2. **If the pod is managed by a Deployment (typical), update the Deployment instead**  
   ```bash
   kubectl edit deployment <deployment‑name> -n kubexplain-eval
   ```
   Modify the `readinessProbe` under `spec.template.spec.containers[0]` as shown above, then let the Deployment roll out the change (or force a restart with `kubectl rollout restart deployment <deployment‑name>`).

3. **Verify the fix**

   ```bash
   # Pod should become Ready
   kubectl get pod s09-bad-readiness -n kubexplain-eval

   # Service should now have a ready address
   kubectl get endpoints s09-web -n kubexplain-eval -o wide
   ```

   You should see the pod listed under `addresses:` (not `notReadyAddresses:`) and the Service will start routing traffic to it.

---

**Summary**

- The pod is not ready because its readiness probe points to a non‑existent URL, causing a 404.
- The Service therefore has no ready endpoints, so traffic never reaches the pod.
- Fix the probe (or remove it) so that it returns a successful HTTP status; the pod will become Ready and the Service will acquire a healthy endpoint.
