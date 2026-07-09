# s09 | mode=static | run=3 | 8.0s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What is happening

| Component | Observation | Meaning |
|-----------|-------------|---------|
| **Pod `s09-bad-readiness`** | `Ready: False` – ContainersReady False | The pod is **running**, but the kubelet does **not consider it ready** for traffic. |
| **Readiness probe** | `http-get http://:80/pagina-inexistenta …` → 404 (failed) | The probe is trying to GET **`/pagina-inexistenta`** on port 80. Nginx returns **404**, so the probe fails. |
| **Service `s09-web`** | Endpoints list only `notReadyAddresses` (the pod’s IP) | Because the pod never becomes *Ready*, the service does **not expose any ready endpoint**. Clients that hit the service get no backend to route to. |

**Root cause:** the readiness probe is pointing at a URL that does not exist (`/pagina-inexistenta`). As long as the probe keeps failing, the pod stays in the *NotReady* state, and the service’s endpoint list remains empty.

### How to fix it

1. **Decide what a “ready” pod should look like**  
   - If the pod should be considered ready as soon as Nginx is listening, use a simple probe that checks the root (`/`) or just the TCP socket.  
   - If you need to verify a specific page, make sure that page actually exists in the container.

2. **Update the pod (or its Deployment/ReplicaSet) spec**  

   Example: change the readiness probe to the default Nginx landing page.

   ```yaml
   readinessProbe:
     httpGet:
       path: /
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
     successThreshold: 1
     failureThreshold: 3
   ```

   Or, if you prefer a TCP‑socket probe:

   ```yaml
   readinessProbe:
     tcpSocket:
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
   ```

3. **Apply the change**  

   ```bash
   kubectl apply -f <your‑deployment‑or‑pod‑manifest>.yaml
   ```

   The pod will be recreated (or the probe will be updated in‑place if you edit the live object). Once the probe succeeds, the pod’s `Ready` condition becomes `True`, and the service will list the pod under `addresses` (instead of `notReadyAddresses`).

4. **Verify**  

   ```bash
   kubectl get pod s09-bad-readiness -n kubexplain-eval
   kubectl describe pod s09-bad-readiness -n kubexplain-eval
   kubectl get endpoints s09-web -n kubexplain-eval
   ```

   You should see the pod’s IP under `addresses` and the service should now route traffic to it.

### Quick checklist

- [ ] **Readiness probe path** matches an existing endpoint (`/` or a real page).  
- [ ] **Port** in the probe matches the container’s exposed port (`80`).  
- [ ] **Service selector** (`app=s09-web`) correctly matches the pod’s label (it already does).  
- [ ] After fixing the probe, **wait a few seconds** for the kubelet to mark the pod Ready and for the service endpoints to be updated.

Once the pod becomes Ready, the service `s09-web` will have a healthy endpoint and traffic will flow as expected.
