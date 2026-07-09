# s08 | mode=none | run=2 | 17.4s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Probable cause | Why it fits the symptom (nginx itself is fine) | How to verify (when you can run `kubectl`) | How to fix (once verified) |
|---|----------------|-----------------------------------------------|--------------------------------------------|----------------------------|
| 1 | **Liveness‑probe failure** – the probe is mis‑configured or too aggressive. | The pod is reported “Running” but the kubelet kills it as soon as the probe returns a non‑200 status. Nginx continues to serve traffic, so you see “it works”, but the container is repeatedly restarted. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.status.containerStatuses[0].state}'\nkubectl -n kubexplain-eval describe pod s08-bad-liveness | grep -i liveness\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o yaml | grep -A5 livenessProbe\n``` | *Adjust the probe* – increase `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, or `failureThreshold`. <br>*Or* change the probe path/port to match the actual nginx endpoint. <br>*Or* temporarily disable the liveness probe to confirm it’s the culprit. |
| 2 | **Readiness‑probe failure causing rapid restarts** (if the pod is part of a Deployment with `restartPolicy: Always`). | A failing readiness probe does **not** kill the container, but if the pod is being recreated by a higher‑level controller (e.g., a Deployment that treats the pod as “unready” and replaces it), you’ll see restarts. | ```bash\nkubectl -n kubexplain-eval describe pod s08-bad-liveness | grep -i readiness\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o yaml | grep -A5 readinessProbe\n``` | Same adjustments as for liveness, but focus on `readinessProbe`. Ensure the endpoint returns `200` after nginx has fully started. |
| 3 | **OOMKilled – container exceeds its memory limit**. | Nginx may serve fine for a short time, then the kernel OOM‑killer terminates it; the pod restarts immediately. The logs you see while it’s up look normal. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'\nkubectl -n kubexplain-eval describe pod s08-bad-liveness | grep -i OOM\n``` | Raise the memory `requests`/`limits` or tune nginx worker processes. If you don’t need a limit, remove it (or set a high value). |
| 4 | **CrashLoopBackOff due to container exit code ≠ 0** (e.g., mis‑configured command, missing config file). | Even if nginx works when you exec into the pod, the container may exit right after start because the entrypoint script fails before nginx daemonizes. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}'\nkubectl -n kubexplain-eval logs s08-bad-liveness -c <container-name> --previous\n``` | Fix the entrypoint/command, ensure required files (e.g., `/etc/nginx/nginx.conf`) are present, or add `--restart=Never` to a test pod to isolate the issue. |
| 5 | **Node‑level pressure (disk, memory, PID) causing kubelet to evict/restart pods**. | The pod may be healthy, but the node is under pressure; kubelet kills containers to reclaim resources, then the controller restarts them. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'\nkubectl -n kubexplain-eval describe node <node-name> | grep -i pressure\n``` | Move the pod to a healthier node (e.g., by adding a node selector or taint/toleration), or increase node resources. |
| 6 | **Pod spec `restartPolicy: Never` overridden by a controller** (e.g., a Job that keeps recreating the pod). | If the pod is managed by a Job or CronJob, each completion triggers a new pod, which looks like a restart. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.metadata.ownerReferences[0].kind}'\n``` | Ensure the workload type matches the intended lifecycle (use a Deployment/DaemonSet for long‑running services). |
| 7 | **Image pull back‑off or registry auth issues** (unlikely if nginx is already running, but can happen on rapid restarts). | The first start succeeds; subsequent restarts may hit a rate‑limit or token expiry, causing the pod to fail to pull the image and restart. | ```bash\nkubectl -n kubexplain-eval describe pod s08-bad-liveness | grep -i Pull\n``` | Refresh image pull secret, or use an image with a tag that is already cached on the node. |
| 8 | **Mis‑configured `terminationGracePeriodSeconds` causing abrupt kill during rollout**. | If a rolling update forces a fast termination, the container may be killed before nginx finishes its shutdown, leading to immediate restart. | ```bash\nkubectl -n kubexplain-eval get pod s08-bad-liveness -o yaml | grep terminationGracePeriodSeconds\n``` | Increase the grace period or adjust the Deployment’s `strategy` (`maxSurge`, `maxUnavailable`). |

---

## How to verify each cause (once you have cluster access)

1. **Check the pod’s status and recent restarts**  
   ```bash
   kubectl -n kubexplain-eval get pod s08-bad-liveness -o wide
   kubectl -n kubexplain-eval describe pod s08-bad-liveness
   ```

2. **Inspect container state details** (look for `waiting`, `terminated`, `reason` fields)  
   ```bash
   kubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.status.containerStatuses[*].state}'
   ```

3. **Review events for the pod** – they often contain the exact reason (e.g., `Liveness probe failed`, `OOMKilled`).  
   ```bash
   kubectl -n kubexplain-eval get events --field-selector involvedObject.name=s08-bad-liveness
   ```

4. **Examine the probe definitions** in the pod spec.  
   ```bash
   kubectl -n kubexplain-eval get pod s08-bad-liveness -o yaml | grep -A10 -i livenessProbe
   kubectl -n kubexplain-eval get pod s08-bad-liveness -o yaml | grep -A10 -i readinessProbe
   ```

5. **Pull the container logs** (including the previous instance if it crashed).  
   ```bash
   kubectl -n kubexplain-eval logs s08-bad-liveness
   kubectl -n kubexplain-eval logs s08-bad-liveness --previous
   ```

6. **Check node health** if the pod is scheduled on a node that may be under pressure.  
   ```bash
   NODE=$(kubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.spec.nodeName}')
   kubectl describe node $NODE | grep -i pressure
   ```

7. **Identify the owning controller** (Deployment, DaemonSet, Job, etc.) to see if the restart is driven by a higher‑level controller.  
   ```bash
   kubectl -n kubexplain-eval get pod s08-bad-liveness -o jsonpath='{.metadata.ownerReferences[*].kind}'
   ```

---

## Typical fixes (apply the one that matches the verified cause)

| Cause | Fix actions |
|-------|-------------|
| **Liveness probe** | Edit the Deployment/Pod spec: <br>`kubectl -n kubexplain-eval edit deployment <name>` <br>Adjust `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, `failureThreshold`, or change the `httpGet` path/port to a real nginx endpoint (`/healthz` or `/`). |
| **Readiness probe** | Same edit as above but for `readinessProbe`. Ensure the endpoint returns `200` only after nginx has fully started (e.g., after config reload). |
| **OOMKilled** | Increase memory `requests`/`limits` or remove the limit. Example: <br>`resources:\n  limits:\n    memory: "512Mi"\n  requests:\n    memory: "256Mi"` |
| **CrashLoopBackOff** | Look at the container’s entrypoint/command. Fix missing files, wrong flags, or add `--daemon-off` to keep nginx in the foreground. |
| **Node pressure** | Either add more resources to the node, taint the node and schedule the pod elsewhere, or spread the workload using `affinity`/`nodeSelector`. |
| **Job/CronJob controller** | If the pod should be a long‑running service, recreate it under a Deployment or DaemonSet instead of a Job. |
| **Image pull issues** | Verify the image tag exists, ensure the imagePullSecret is correct, or pre‑pull the image on the node (`docker pull …`). |
| **TerminationGracePeriod** | Increase `terminationGracePeriodSeconds` in the pod spec, and adjust the Deployment’s rolling‑update strategy to give pods more time to shut down gracefully. |

---

### Quick “first‑check” checklist (when you get back to the cluster)

1. `kubectl describe pod s08-bad-liveness` → look for **Liveness probe failed** or **OOMKilled** in the Events section.  
2. `kubectl get pod s08-bad-liveness -o jsonpath='{.status.containerStatuses[0].state}'` → see if the state is `waiting` (reason) or `terminated` (reason).  
3. `kubectl logs s08-bad-liveness --previous` → any crash messages?  
4. Verify the probe definitions (`grep -i livenessProbe` in the YAML).  
5. If the above point to a probe, edit the Deployment to relax the probe parameters and redeploy.  

Address the first cause that matches; most “nginx works but pod restarts” cases are resolved by fixing a mis‑behaving **liveness probe**. Good luck!
