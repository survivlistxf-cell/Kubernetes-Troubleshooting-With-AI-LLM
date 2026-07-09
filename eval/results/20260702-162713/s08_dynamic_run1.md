# s08 | mode=dynamic | run=1 | 12.3s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps restarting  

| # | Probable cause | Why it makes the pod restart | How to verify (kubectl) | How to fix |
|---|----------------|-----------------------------|--------------------------|------------|
| 1 | **Liveness probe is failing** (timeout, wrong path, wrong port, wrong scheme) | When the probe returns a failure the kubelet kills the container and the pod is recreated, even though the service inside (nginx) is healthy. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -A5 \"Liveness\" \n```<br>Check the **Events** section for messages like *“Liveness probe failed”* and look at the **Readiness/Liveness** sections in the pod description. | • Adjust the probe definition (path, port, `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`).<br>• Example fix: ```yaml\nlivenessProbe:\n  httpGet:\n    path: /healthz   # or / (root) if nginx returns 200\n    port: 80\n  initialDelaySeconds: 10\n  periodSeconds: 10\n```<br>Apply the updated manifest with `kubectl apply -f <file>.yaml`. |
| 2 | **Readiness probe is mis‑configured** (often confused with liveness) | If the readiness probe fails, the pod is marked *NotReady* and the Service may route traffic away, but the kubelet does **not** restart the container. However, a mis‑configured readiness probe can mask a real liveness problem, leading you to think nginx is fine while the pod keeps being killed by the liveness probe. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A10 \"readinessProbe\"\n``` | • Verify the readiness probe settings are correct (same as above).<br>• If the readiness probe is unnecessary, you can remove it or set `failureThreshold: 1` to avoid long back‑off. |
| 3 | **CrashLoopBackOff caused by container exit code** (e.g., `nginx` exits with non‑zero code) | Even if nginx appears to work when you `exec` into the pod, the container may be exiting shortly after start (e.g., mis‑configured `command` or `args`). The kubelet restarts it, producing the restart loop. | ```bash\nkubectl logs s08-bad-liveness -n kubexplain-eval --previous\n```<br>Look for a non‑zero exit code or error messages. Also check `kubectl describe pod …` → **State** section for *“Exit Code: X”*. | • Ensure the container image’s default command is correct.<br>• Remove any accidental `command:` or `args:` overrides in the pod spec.<br>• If you need a custom entrypoint, make sure it ends with `nginx -g 'daemon off;'`. |
| 4 | **Resource limits / OOMKill** (memory or CPU) | If the pod exceeds its memory limit, the kernel OOM‑killer terminates the container, which the kubelet treats as a crash and restarts it. The nginx process may still respond for a short time before being killed, giving the impression that “nginx works”. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"OOMKilled\"\n```<br>Or check events for *“Killing container … OOMKilled”*. | • Increase the memory `requests`/`limits` or remove the limit if not needed.<br>• Example fix: ```yaml\nresources:\n  limits:\n    memory: 256Mi\n  requests:\n    memory: 128Mi\n``` |
| 5 | **Node‑level problems** (node NotReady, kubelet restart) | When the node becomes NotReady, pods are evicted and later recreated on another node, causing restarts that are unrelated to the container itself. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o wide\n```<br>Note the **NODE** column; then run `kubectl describe node <node>` to see its conditions. | • Resolve node health (e.g., restart kubelet, fix networking, ensure sufficient disk).<br>• If the node is permanently unhealthy, cordon and drain it: `kubectl cordon <node>` → `kubectl drain <node> --ignore-daemonsets`. |
| 6 | **Pod disruption due to Deployment rollout / ReplicaSet replacement** | A new ReplicaSet may be created (e.g., image tag changed) and the old pod is terminated as part of the rollout, appearing as a restart. | ```bash\nkubectl rollout status deployment/<deployment-name> -n kubexplain-eval\n```<br>Check recent events for *“Scaled up replica set”* and *“Scaled down replica set”*. | • Ensure the Deployment’s `strategy` is appropriate (e.g., `RollingUpdate` with proper `maxSurge`/`maxUnavailable`).<br>• If the rollout was accidental, roll back: `kubectl rollout undo deployment/<deployment-name> -n kubexplain-eval`. |
| 7 | **Finalizer / Admission webhook blocking deletion** (rare) | If a finalizer or webhook prevents the pod from being fully terminated, the pod may stay in a terminating state and be recreated repeatedly. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -i finalizers\n``` | • Remove the offending finalizer or adjust the webhook configuration.<br>• Delete the pod with `--force --grace-period=0` if safe: `kubectl delete pod s08-bad-liveness -n kubexplain-eval --force --grace-period=0`. |

---

### Quick verification checklist (run in order)

1. **Check events & probe status**  
   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -A10 "Events"
   ```
2. **Inspect liveness/readiness definitions**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A15 "livenessProbe"
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A15 "readinessProbe"
   ```
3. **Look at container exit logs**  
   ```bash
   kubectl logs s08-bad-liveness -n kubexplain-eval --previous
   ```
4. **Detect OOMKilled**  
   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i "OOMKilled"
   ```
5. **Confirm node health**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide
   kubectl describe node <node-name>
   ```

---

### General remediation flow

1. **Fix the probe** – most common cause. Adjust path/port/delays, then re‑apply the manifest.  
2. **If probes are fine, check container exit** – correct command/args or increase resources.  
3. **If resources are the issue, raise limits**.  
4. **If node health is suspect, address node problems or move the pod**.  
5. **If a rollout or finalizer is involved, adjust the Deployment strategy or webhook**.

By following the checklist and applying the corresponding fixes, the restart loop for `s08-bad-liveness` should be eliminated.
