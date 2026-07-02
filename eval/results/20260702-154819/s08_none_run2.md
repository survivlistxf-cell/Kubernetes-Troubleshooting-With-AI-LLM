# s08 | mode=none | run=2 | 7.9s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What is happening?

* **Liveness probe configuration**  
  The pod’s `Liveness` line shows  

  ```
  http-get http://:8081/ delay=5s timeout=1s period=5s
  ```

  This tells the kubelet to perform an HTTP GET request against **port 8081** of the pod every 5 seconds (after an initial 5 second delay).

* **Nginx container**  
  The container runs the official `nginx:1.25` image, which by default listens on **port 80** (the `Port: 80/TCP` entry in the describe output). Nothing in the pod spec opens port 8081, so there is no service listening there.

* **Result**  
  The kubelet tries to reach `http://10.244.226.72:8081/`, gets a *connection refused* error, marks the probe as **Unhealthy**, and kills the container. The container is then restarted, which repeats the same failure. That is why you see:

  ```
  Liveness probe failed: Get "http://10.244.226.72:8081/": dial tcp 10.244.226.72:8081: connect: connection refused
  ```

  The pod itself stays in `Running` state because the container is quickly restarted, but the restart count increments (currently `1`).

### Root cause

The liveness probe is pointing at the wrong port (8081) for an Nginx container that only serves on port 80. The probe therefore always fails, causing the pod to be killed and restarted.

### How to fix it

1. **Update the liveness probe to use the correct port (80) and, optionally, a path that returns `200`.**  
   Example snippet for the container spec:

   ```yaml
   livenessProbe:
     httpGet:
       path: /
       port: 80          # <-- correct port
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
     failureThreshold: 2
   ```

2. **If you really need the probe on port 8081**, expose that port from the container (e.g., run Nginx on 8081 or add a side‑car that listens on 8081). That would require changing the container command or adding a second container.

3. **Apply the corrected manifest** (or edit the existing pod/deployment). Typical ways:

   * If the pod is created directly from a YAML file, edit the file and `kubectl apply -f <file>`.
   * If it is managed by a Deployment/ReplicaSet, edit the Deployment:

     ```bash
     kubectl edit deployment <deployment-name> -n kubexplain-eval
     ```

     Adjust the `livenessProbe` section as shown above, then save. The Deployment controller will roll out a new ReplicaSet with the corrected probe.

4. **Verify the fix**  

   * Wait a few seconds for the new pod to start.  
   * Run `kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide` and confirm `RESTARTS` stays at `0` (or does not increase).  
   * Check events: `kubectl describe pod s08-bad-liveness -n kubexplain-eval` – there should be no “Killing … failed liveness probe” messages.

### Quick checklist

- [ ] Liveness probe `port` matches the container’s listening port (`80` for default Nginx).  
- [ ] `path` returns a 2xx/3xx response (e.g., `/` or `/healthz`).  
- [ ] `initialDelaySeconds` is long enough for Nginx to start (5 s is usually fine).  
- [ ] No other probes (readiness, startup) are mis‑configured in the same way.

Once the probe points at a reachable endpoint, the kubelet will stop killing the container and the pod will stay healthy without unnecessary restarts.
