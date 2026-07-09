# s08 | mode=dynamic | run=3 | 12.8s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

**Most likely reasons why the pod `s08-bad-liveness` keeps being restarted (in descending order of probability)**  

1. **Liveness‑probe failure**  
   *Why it happens* – The pod’s container is running fine, but the liveness probe (HTTP/TCP/exec) returns a non‑successful result or times out. When the probe fails, the kubelet kills the container and the Deployment/ReplicaSet restarts it, even though the application itself is healthy.  
   *How to verify* –  
   • Look at the pod’s events (`kubectl describe pod s08-bad-liveness -n kubexplain-eval`). You’ll see entries such as `Liveness probe failed` with timestamps.  
   • Inspect the pod’s status section (`kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml`) and find the `livenessProbe` definition and the `lastProbeTime`/`failureThreshold` counters.  
   • Check the probe endpoint from inside the node (e.g. `curl http://<podIP>:<port>/healthz`) to see whether it really returns the expected code.  
   *Fix* –  
   • Adjust the probe parameters (increase `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, or `failureThreshold`) so the probe gives the container enough time to become ready.  
   • Verify the probe path/port matches what nginx actually serves (e.g. `/` or `/healthz`).  
   • If the probe is unnecessary, you can remove it from the pod spec or replace it with a more appropriate check.  

2. **Readiness‑probe misconfiguration that indirectly triggers restarts**  
   *Why it happens* – A failing readiness probe does not kill the container, but it can cause the Service to drop the pod and the Deployment controller may repeatedly create new pods if the existing one never becomes “Ready”. In some setups (e.g., with `restartPolicy: Always` on a single‑container pod) the controller may delete and recreate the pod when it stays unready for a long time.  
   *How to verify* –  
   • In the pod description, look for `Readiness probe failed` events.  
   • Observe the `READY` column of `kubectl get pods` – it will stay `0/1`.  
   *Fix* –  
   • Correct the readiness‑probe definition (path, port, delay).  
   • Ensure the endpoint actually returns a 200 OK when nginx is serving.  

3. **Container‑level OOM (Out‑Of‑Memory) kills**  
   *Why it happens* – Even if nginx appears to work, the container may exceed its memory limit, causing the kubelet to kill it with an OOM event. The pod is then restarted, and the logs may still show “nginx is up” because the process restarts quickly.  
   *How to verify* –  
   • Check the pod’s events for `OOMKilled` or `Killing` messages.  
   • Look at the container status (`state: terminated, reason: OOMKilled`).  
   • Review the memory `requests` and `limits` in the pod spec; compare them with the actual usage (`kubectl top pod`).  
   *Fix* –  
   • Increase the memory `limit` (and possibly the `request`).  
   • Optimize nginx configuration to reduce memory consumption, or enable caching limits.  

4. **CrashLoopBackOff caused by a non‑zero exit code from the container’s entrypoint**  
   *Why it happens* – The container may start, run nginx, then the entrypoint script exits (perhaps after a mis‑configured `exec` or a background‑process daemonizing incorrectly). The pod appears “Running” for a moment, then the container exits, and the kubelet restarts it.  
   *How to verify* –  
   • In `kubectl describe pod`, see `State: Terminated` with a non‑zero `exitCode`.  
   • Look at the container logs (`kubectl logs`) for a line indicating the process stopped.  
   *Fix* –  
   • Ensure the container’s command runs nginx in the foreground (e.g., `nginx -g 'daemon off;'`).  
   • Remove any `exit` statements or scripts that end prematurely.  

5. **Node‑level issues (e.g., node NotReady, kubelet restart) that cause pod eviction and immediate recreation**  
   *Why it happens* – If the node hosting the pod becomes NotReady or the kubelet restarts, the pod may be terminated and the controller creates a new instance on the same or a different node. The restart count increments even though the container itself never failed.  
   *How to verify* –  
   • Check node status (`kubectl get nodes`) for `NotReady` or recent `Ready` transitions.  
   • Review node events for kubelet restarts or network partitions.  
   *Fix* –  
   • Resolve the underlying node problem (restart kubelet, fix networking, ensure sufficient resources).  
   • If the node is unstable, consider cordoning it and moving workloads to a healthy node.  

6. **Finalizer or admission‑webhook preventing graceful termination**  
   *Why it happens* – A pod that is being deleted may get stuck in `Terminating` because a finalizer or a mutating/validating webhook blocks its removal. The controller may repeatedly attempt to delete and recreate it, inflating the restart count.  
   *How to verify* –  
   • Look for `finalizers` in the pod’s metadata and for `Terminating` status.  
   • Check for webhook configurations that target pod updates.  
   *Fix* –  
   • Remove or adjust the offending finalizer/webhook, or delete the pod with `--force --grace-period=0`.  

---

### How to verify each cause when you regain cluster access  

| Cause | What to inspect | Typical evidence |
|-------|----------------|------------------|
| Liveness‑probe failure | `kubectl describe pod …` events, `livenessProbe` section in pod YAML | `Liveness probe failed` messages, repeated `Killing` events |
| Readiness‑probe failure | Same as above, but look for `Readiness probe failed` | Pod never reaches `READY 1/1` |
| OOM kill | Pod events, container `state: terminated, reason: OOMKilled`, `kubectl top pod` | `OOMKilled` reason, memory usage > limit |
| CrashLoopBackOff (non‑zero exit) | Container `state: terminated, exitCode != 0`, pod logs | `CrashLoopBackOff` status, exit code 1‑255 |
| Node problems | `kubectl get nodes`, node events, `kubectl describe node` | Node `NotReady`, kubelet restarts, network errors |
| Finalizer/webhook | Pod metadata `finalizers`, cluster `ValidatingWebhookConfiguration`/`MutatingWebhookConfiguration` | Pod stuck in `Terminating`, webhook logs |

---

### General remediation steps (once you can run commands)

1. **Identify the exact failure** – Pull the pod description and logs; the event that precedes each restart tells you the root cause.  
2. **Adjust the pod spec** – Edit the Deployment/StatefulSet manifest to fix probes, resources, or command. Apply with `kubectl apply -f …`.  
3. **Validate the change** – Use `kubectl rollout status` to ensure the new pod runs without restarts.  
4. **Monitor** – After the fix, watch the pod’s `RESTARTS` column for a period (e.g., `kubectl get pod -w`).  

By following the verification steps above, you’ll quickly pinpoint whether the restarts are driven by a mis‑configured liveness probe (the most common scenario for “nginx works but pod restarts”) or by one of the other listed issues, and you can apply the corresponding fix.
