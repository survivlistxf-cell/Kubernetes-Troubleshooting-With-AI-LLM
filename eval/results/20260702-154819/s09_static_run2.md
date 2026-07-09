# s09 | mode=static | run=2 | 8.0s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## What is happening?

| Observation | Meaning |
|-------------|----------|
| **Pod status** – `Ready: False` | The kubelet has decided the pod is *not ready* for traffic. |
| **Readiness probe** – `http-get http://:80/pagina-inexistenta …` | The probe is trying to GET **/pagina‑inexistenta** on port 80. |
| **Probe result** – `Readiness probe failed: HTTP probe failed with statuscode: 404` | The request returns **404**, so the probe is considered failed. |
| **Service endpoints** – pod appears under `notReadyAddresses` only | Because the pod is not ready, the Service does **not** include it in the list of routable endpoints, so no traffic reaches the pod. |

**Root cause:** The readiness probe is pointing at a URL that does not exist (`/pagina‑inexistenta`). Nginx returns 404, the probe fails, the pod stays in the *NotReady* state, and the Service excludes it.

## How to fix it

1. **Decide what a “ready” pod should look like**  
   * If any HTTP 200 response from the root (`/`) is enough, use that path.  
   * If you need a custom health‑check page, create it in the container (e.g., add an `index.html` or a small health‑check endpoint).

2. **Update the pod (or its controller) definition**  

   *If the pod is created directly (no Deployment/ReplicaSet):*  

   ```bash
   kubectl edit pod s09-bad-readiness -n kubexplain-eval
   ```

   Change the `readinessProbe` section, for example:

   ```yaml
   readinessProbe:
     httpGet:
       path: /
       port: 80
     initialDelaySeconds: 0
     periodSeconds: 5
     timeoutSeconds: 1
     successThreshold: 1
     failureThreshold: 3
   ```

   *If the pod is managed by a Deployment/ReplicaSet:*  

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   (or `kubectl patch` with a JSON/YAML patch).

3. **Apply the change** – the kubelet will restart the container (or recreate the pod) with the new probe.  

4. **Verify**  

   ```bash
   kubectl get pod s09-bad-readiness -n kubexplain-eval
   kubectl describe pod s09-bad-readiness -n kubexplain-eval
   kubectl get endpoints s09-web -n kubexplain-eval
   ```

   You should see:

   * `Ready: True` on the pod.
   * The pod listed under `addresses:` (not `notReadyAddresses:`) of the Service endpoints.
   * Traffic from the Service `s09-web` now reaching the pod.

## Alternative quick fixes (if you just want traffic now)

* **Remove the readiness probe** – the pod will be considered ready as soon as it starts.

  ```bash
  kubectl patch pod s09-bad-readiness -n kubexplain-eval \
    --type=json -p='[{"op":"remove","path":"/spec/containers/0/readinessProbe"}]'
  ```

* **Make the probe succeed** – add a simple file `pagina-inexistenta` that returns 200, or change the Nginx config to serve that path.

  ```bash
  # Example: add a static file via a ConfigMap and mount it
  ```

  After the change, the pod will become ready and the Service will route traffic.

## Summary

The pod is excluded from the Service because its **readiness probe is failing** (404 on a non‑existent URL). Fix the probe – either point it at a valid path, adjust the container to serve that path, or remove the probe – and the pod will become ready, appear in the Service’s endpoints, and start receiving traffic.
