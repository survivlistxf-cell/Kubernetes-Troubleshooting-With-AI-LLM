# s08 | mode=none | run=1 | 17.6s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most Likely Reasons a “running‑nginx” pod keeps being restarted  

| # | Likely cause (ordered by probability) | Why it produces restarts even though the service works | How to verify (when you have cluster access) | How to fix (once verified) |
|---|----------------------------------------|--------------------------------------------------------|--------------------------------------------|----------------------------|
| 1 | **Liveness‑probe failure** | The probe returns a non‑zero exit code or timeout, so the kubelet kills the container and starts it again. Nginx may still be serving traffic between probes, so you see “it works” but the pod is constantly killed/restarted. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o wide\nkubectl describe pod s08-bad-liveness -n kubexplain-eval\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state}'\n``` Look for `lastState: { terminated: { reason: "Error", exitCode: ... }}` or `state: { waiting: { reason: "CrashLoopBackOff" }}` and check the **Liveness** section in the `describe` output for “Liveness probe failed”. | *Adjust the probe* – make the HTTP/TCP path, port, `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, and `failureThreshold` match the real nginx health. <br>Example: ```yaml\nlivenessProbe:\n  httpGet:\n    path: /healthz\n    port: 80\n  initialDelaySeconds: 15\n  periodSeconds: 10\n  timeoutSeconds: 2\n  failureThreshold: 3\n``` <br>Or temporarily **disable** the liveness probe to confirm it’s the culprit, then re‑enable with correct values. |
| 2 | **Container exiting with non‑zero code (CrashLoopBackOff)** | Nginx may start, serve a request, then exit (e.g., mis‑configured command line, missing config file, permission error). The pod appears “running” for a short window, but the kubelet restarts it repeatedly. | ```bash\nkubectl logs s08-bad-liveness -n kubexplain-eval --previous\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"exit code\"\n``` The logs will show the termination reason and any error messages from nginx. | *Fix the underlying error* – ensure the container’s `command`/`args` are correct, the config file is present and readable, and any required environment variables are set. Re‑deploy the pod with the corrected spec. |
| 3 | **Out‑of‑Memory (OOMKilled) due to resource limits** | If the pod’s memory request/limit is too low, the kernel OOM killer will terminate the container. Nginx may still answer a few requests before being killed, giving the impression it “works”. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"OOMKilled\"\nkubectl top pod s08-bad-liveness -n kubexplain-eval\n``` Look for `State: Terminated` with `Reason: OOMKilled` and memory usage near the limit. | *Raise the memory limit* – increase `resources.limits.memory` (and optionally `requests.memory`) to a value that covers the observed peak usage. <br>Example: ```yaml\nresources:\n  requests:\n    memory: \"256Mi\"\n  limits:\n    memory: \"512Mi\"\n``` |
| 4 | **Readiness‑probe misconfiguration causing rapid restarts via a side‑car** | A side‑car container that watches readiness may kill the main container when it reports “not ready”. This is less common but can appear as a restart loop while nginx still serves traffic. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"readiness\"\nkubectl logs <sidecar‑container> -n kubexplain-eval\n``` Check if a side‑car is present and whether it logs “killing main container”. | *Correct the readiness probe* – ensure the path/port returns 200 only when nginx is truly healthy, or adjust `failureThreshold`/`periodSeconds`. If a side‑car is unnecessary, remove it. |
| 5 | **Node pressure / pod eviction** | The node may be under memory or disk pressure, causing the kubelet to evict and immediately reschedule the pod. The pod restarts, but the container itself never crashes. | ```bash\nkubectl get events -n kubexplain-eval --field-selector involvedObject.name=s08-bad-liveness\nkubectl describe node <node-name> | grep -i \"Pressure\"\n``` Look for events like “NodeUnderMemoryPressure” or “NodeDiskPressure”. | *Alleviate node pressure* – add more nodes, increase node resources, or lower the pod’s resource requests. You can also set `podAntiAffinity` to spread pods across nodes. |
| 6 | **Incorrect `restartPolicy` (e.g., Always on a Job)** | If the pod is created by a Job or CronJob with `restartPolicy: OnFailure` but the container exits cleanly, the controller may keep recreating it. | ```bash\nkubectl get job -n kubexplain-eval -o yaml | grep s08-bad-liveness -A5\n``` Verify the owner reference. | *Use the proper controller* – if this is a long‑running service, run it as a Deployment/StatefulSet with `restartPolicy: Always`. If it truly belongs to a Job, adjust the Job’s `backoffLimit` or `completions`. |
| 7 | **Image pull or registry authentication issues (transient)** | A temporary registry outage can cause the kubelet to repeatedly try pulling the image, leading to restart attempts. Once the image is cached, the pod runs fine, so you may not notice the failure. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"ImagePull\"\n``` Look for `ImagePullBackOff` or `ErrImagePull` events. | *Fix registry access* – ensure the image pull secret is correct, the registry is reachable, and the image tag exists. Consider using an image digest to avoid tag‑changing issues. |
| 8 | **Pod disruption due to `PodDisruptionBudget` or rolling update** | A rolling update may delete the old pod before the new one is ready, causing a brief restart loop that appears continuous if the update never completes. | ```bash\nkubectl get pdb -n kubexplain-eval\nkubectl rollout status deployment/<deployment-name> -n kubexplain-eval\n``` | *Complete the rollout* – ensure the new replica set becomes ready, or adjust the `maxUnavailable`/`minAvailable` values. |
| 9 | **Mis‑configured `terminationGracePeriodSeconds` causing rapid kill‑restart** | If the pod is being terminated (e.g., by a higher‑level controller) and the grace period is too short, the container may be killed before it can shut down cleanly, leading to a restart. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"terminationGracePeriodSeconds\"\n``` | *Increase the grace period* – set `terminationGracePeriodSeconds` to a value that allows nginx to finish ongoing connections (e.g., 30 s). |
|10| **NetworkPolicy blocking health‑check traffic** | A NetworkPolicy may prevent the kubelet (or the pod itself) from reaching the liveness endpoint, causing probe failures while external traffic still works. | ```bash\nkubectl get networkpolicy -n kubexplain-eval -o yaml\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"NetworkPolicy\"\n``` | *Adjust the NetworkPolicy* – allow traffic from the node’s IP range or from the kubelet to the pod’s health‑check port. |

---

## General Verification Checklist (run when you can access the cluster)

1. **Pod status & events**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide
   kubectl describe pod s08-bad-liveness -n kubexplain-eval
   ```

2. **Container exit reason**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'
   ```

3. **Recent logs (including previous instance)**  
   ```bash
   kubectl logs s08-bad-liveness -n kubexplain-eval
   kubectl logs s08-bad-liveness -n kubexplain-eval --previous
   ```

4. **Resource usage** (if metrics‑server is installed)  
   ```bash
   kubectl top pod s08-bad-liveness -n kubexplain-eval
   ```

5. **Node health**  
   ```bash
   kubectl describe node $(kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   ```

6. **Owner controller** (Deployment, DaemonSet, Job, etc.)  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.metadata.ownerReferences}'
   ```

7. **NetworkPolicy impact**  
   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

---

## Quick “Fix‑first” Approach (when you regain access)

1. **Check the liveness probe** – if it’s failing, edit the deployment (or pod spec) to correct the path/port or increase delays.  
2. **Inspect logs for OOM or nginx errors** – raise memory limits or fix config files accordingly.  
3. **Confirm the container isn’t exiting** – correct command/args or mount the proper config.  
4. **Validate node pressure** – if the node is under memory/disk pressure, add capacity or rebalance pods.  
5. **Review any side‑car or custom controllers** that might be killing the pod on readiness failures.  

Address the highest‑probability cause first (liveness‑probe misconfiguration) and then move down the list if the problem persists. This systematic approach will usually reveal the root cause within a few minutes once you have cluster access.
