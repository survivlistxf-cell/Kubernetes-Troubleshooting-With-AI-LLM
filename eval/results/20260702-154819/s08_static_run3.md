# s08 | mode=static | run=3 | 5.8s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

**Root cause**

The pod’s liveness probe is checking the wrong address:

```
Liveness: http-get http://:8081/ delay=5s timeout=1s period=5s
```

* The container runs the **nginx** image, which by default listens on **port 80**.
* The probe tries to reach **port 8081** on the pod IP (`10.244.226.84:8081`).  
  Since nothing is listening there, the probe gets *connection refused*:

```
Liveness probe failed: Get "http://10.244.226.84:8081/": dial tcp 10.244.226.84:8081: connect: connection refused
```

Because the probe fails twice (failureThreshold = 2), the kubelet kills the container and restarts it. The container itself is healthy – the restart loop is solely caused by the mis‑configured liveness probe.

---

**How to fix it**

1. **Update the liveness probe to match the actual service**  
   - Use the correct port (`80`) and, if needed, a path that returns a 200 response (e.g. `/` or `/healthz`).  
   - Example snippet for a pod/deployment spec:

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

2. **Apply the corrected manifest**  
   - If the pod is created from a Deployment/ReplicaSet, edit the resource:

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   - Locate the `livenessProbe` section under `containers.web` and change `port: 8081` to `port: 80` (or remove the probe if you don’t need one).

   - Save and exit; the Deployment will roll out a new ReplicaSet with the corrected probe.

   *or* patch directly:

   ```bash
   kubectl patch deployment <deployment-name> -n kubexplain-eval \
     --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/port","value":80}]'
   ```

3. **Verify the fix**  

   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -w
   ```

   - The pod should stay in `Running` with `Restart Count: 0` and no new `Unhealthy` events.

4. **Optional: Disable the probe** (only if you truly don’t need a liveness check)

   ```yaml
   # simply remove the livenessProbe block from the container spec
   ```

   Then re‑apply the manifest as above.

---

**Summary**

The restart loop is caused by a liveness probe that points to a non‑listening port (8081). Align the probe with the container’s actual listening port (80) or remove the probe, and the pod will stop being killed and restarted.
