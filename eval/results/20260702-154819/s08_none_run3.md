# s08 | mode=none | run=3 | 6.8s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What’s happening

* **Liveness probe is mis‑configured**  
  The pod definition shows  

  ```
  Liveness: http-get http://:8081/ delay=5s timeout=1s period=5s
  ```

  Nginx (image `nginx:1.25`) listens on **port 80** by default.  
  The probe is trying to reach `http://10.244.226.72:8081/`, which has nothing listening, so the kubelet reports:

  ```
  Liveness probe failed: Get "http://10.244.226.72:8081/": dial tcp ... connection refused
  ```

* Because the probe fails, the kubelet kills the container and restarts it.  
  The container itself is healthy (it serves on port 80), but the probe never succeeds, so the pod is constantly restarted.

### How to fix it

1. **Point the probe at the correct port (and optionally a path).**  
   Typical nginx health check:

   ```yaml
   livenessProbe:
     httpGet:
       path: /
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
   ```

2. **If you prefer a TCP check** (just verify the port is open):

   ```yaml
   livenessProbe:
     tcpSocket:
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
   ```

3. **Apply the change** – either edit the manifest that created the pod (Deployment, StatefulSet, etc.) or patch the live pod for a quick test.

   **Edit the owning controller (recommended):**

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   Adjust the `livenessProbe` section as shown above, save, and let the controller roll out the updated pods.

   **Or patch the pod directly (temporary):**

   ```bash
   kubectl patch pod s08-bad-liveness -n kubexplain-eval \
     --type='json' -p='[{"op":"replace","path":"/spec/containers/0/livenessProbe/httpGet/port","value":80}]'
   ```

   (You can also replace the whole `livenessProbe` object with the corrected YAML.)

4. **Verify the fix**

   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -w
   ```

   The pod should stay in `Running` with `Restart Count` unchanged, and the events list should no longer show “Killing … failed liveness probe”.

### Summary

The restarts are caused by a **liveness probe that checks the wrong port (8081)**. Updating the probe to target the actual nginx port (80) – or disabling/changing the probe – stops the kubelet from killing the container, allowing the pod to run normally.
