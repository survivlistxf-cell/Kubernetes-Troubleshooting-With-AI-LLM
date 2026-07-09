# s08 | mode=static | run=1 | 10.0s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What is happening?

| Observation | Meaning |
|-------------|----------|
| **Liveness probe**: `http-get http://:8081/ delay=5s timeout=1s period=5s` | The kubelet tries to reach **port 8081** on the pod’s IP every 5 seconds, starting 5 seconds after the container starts. |
| **Container image**: `nginx:1.25` (default Nginx) | The Nginx image listens on **port 80** (and only on that port unless you re‑configure it). |
| **Event**: `Liveness probe failed: Get "http://10.244.226.84:8081/": dial tcp 10.244.226.84:8081: connect: connection refused` | The probe cannot open a TCP connection to 8081 because nothing is listening there. |
| **Result**: `Container web failed liveness probe, will be restarted` | The kubelet kills the container and starts a new one. The pod stays in *Running* because the new container starts quickly, but the cycle repeats as soon as the probe runs again. |

**Root cause:** The liveness probe is pointing at the wrong port (and possibly the wrong path). Nginx is healthy on port 80, but the probe checks port 8081, so it always fails and triggers a restart.

---

### How to fix it

1. **Decide what you really want the liveness probe to check**  
   * If you just want to verify that Nginx is up, probe `http://:80/` (or `http://:80/healthz` if you add a simple health‑check endpoint).  
   * If you intended to run another service on 8081, make sure that service is actually listening inside the container (e.g., add a second container or change Nginx config).

2. **Update the pod (or its owning Deployment/ReplicaSet) spec**  

   **Option A – edit the live object** (quick test)

   ```bash
   kubectl edit pod s08-bad-liveness -n kubexplain-eval
   ```

   Find the `livenessProbe` section and change it to:

   ```yaml
   livenessProbe:
     httpGet:
       path: /
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
   ```

   Save and exit. The pod will be recreated (if it’s managed by a controller) or the probe will be updated in‑place.

   **Option B – patch the owning controller** (recommended for production)

   ```bash
   kubectl patch deployment <deployment-name> -n kubexplain-eval \
     --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/port","value":80}]'
   ```

   Adjust the JSON path if the container index is different.

   **Option C – rewrite the manifest** (if you have the YAML file)

   ```yaml
   containers:
   - name: web
     image: nginx:1.25
     ports:
     - containerPort: 80
     livenessProbe:
       httpGet:
         path: /
         port: 80
       initialDelaySeconds: 5
       periodSeconds: 5
       timeoutSeconds: 1
   ```

   Apply the updated manifest:

   ```bash
   kubectl apply -f <updated‑manifest>.yaml
   ```

3. **Verify the fix**

   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -w
   ```

   Watch the `RESTARTS` column – it should stay at `0` (or stop increasing). You can also check the probe status:

   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -A2 "Liveness"
   ```

4. **Optional – add a readiness probe**  
   If you also need a readiness check (to control service traffic), add a similar `readinessProbe` that points to the same healthy endpoint.

---

### Quick checklist

- [ ] **Port matches** – Nginx → 80, probe → 80.  
- [ ] **Path is reachable** – `/` works out‑of‑the‑box; custom path requires an endpoint.  
- [ ] **Probe timings are reasonable** – `initialDelaySeconds` should be > the time Nginx needs to start (5 s is fine for a simple Nginx).  
- [ ] **Controller is updated** – if the pod is managed by a Deployment/StatefulSet, edit the controller spec, not the individual pod (the controller will recreate pods with the corrected spec).  

After correcting the probe, the pod will stay up without unnecessary restarts, and the rest of your application can rely on a stable Nginx service.
