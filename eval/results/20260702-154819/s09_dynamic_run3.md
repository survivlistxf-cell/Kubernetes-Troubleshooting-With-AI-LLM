# s09 | mode=dynamic | run=3 | 7.9s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What’s happening

| Component | Observation |
|-----------|--------------|
| **Pod `s09-bad-readiness`** | `Ready: False` – the container is running but the **readiness probe** is failing. |
| **Readiness probe** | `http-get http://:80/pagina-inexistenta …` → the HTTP request returns **404**. |
| **Endpoints object for Service `s09‑web`** | The pod appears under `notReadyAddresses` – therefore the Service has **no ready endpoints** and cannot route traffic to the pod. |

**Root cause** – the readiness probe is checking a URL (`/pagina-inexistenta`) that does **not exist** in the Nginx image, so the probe always returns 404. Because the pod never becomes *Ready*, the Service excludes it from its endpoint list.

### How to fix it

1. **Decide what a healthy pod should look like**  
   * If you simply want the pod to be considered ready as soon as the container starts, you can remove the probe or change it to a path that returns 200 (e.g. `/`).  
   * If you need a custom health‑check, make sure the path actually exists (add a file, a custom HTML page, or a small HTTP server that serves the expected endpoint).

2. **Update the pod (or its Deployment/ReplicaSet) definition**  

   ```bash
   # Edit the manifest that creates the pod (Deployment, StatefulSet, etc.)
   kubectl edit deployment <deployment‑name> -n kubexplain-eval
   ```

   In the `readinessProbe` section:

   * **Option A – Remove the probe**  

     ```yaml
     # delete the whole readinessProbe block
     ```

   * **Option B – Change the probe to a valid path**  

     ```yaml
     readinessProbe:
       httpGet:
         path: /          # a path that returns 200 (default Nginx index)
         port: 80
       initialDelaySeconds: 5
       periodSeconds: 10
       timeoutSeconds: 2
       successThreshold: 1
       failureThreshold: 3
     ```

3. **Apply the change** – the controller will roll out a new pod (or you can delete the existing pod so it is recreated):

   ```bash
   # If you edited a Deployment, it will automatically replace the pod.
   # Otherwise, force a restart:
   kubectl delete pod s09-bad-readiness -n kubexplain-eval
   ```

4. **Verify**  

   ```bash
   # Wait a few seconds, then check the pod status
   kubectl get pod s09-bad-readiness -n kubexplain-eval
   # Should show READY 1/1

   # Check the endpoints for the service
   kubectl get endpoints s09-web -n kubexplain-eval -o yaml
   # The pod should now appear under `addresses` (ready) instead of `notReadyAddresses`
   ```

### Quick checklist

- ✅ **Readiness probe points to a URL that returns HTTP 200** (or remove it).  
- ✅ **Pod label `app=s09-web` matches the Service selector** (it already does).  
- ✅ After the change, **pod becomes Ready → Service gets a healthy endpoint → traffic is routed**.

Once the readiness probe succeeds, `s09-web` will have a populated endpoint list and your pod will start receiving traffic.
