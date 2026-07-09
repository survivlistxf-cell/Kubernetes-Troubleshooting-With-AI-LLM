# s08 | mode=static | run=3 | 12.7s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Likely cause | Why it produces a restart even though the web server works | How to verify (when you can reach the cluster) | How to fix (once verified) |
|---|--------------|------------------------------------------------------------|-----------------------------------------------|----------------------------|
| 1 | **Liveness‑probe failure** | The kubelet kills the container when the probe returns non‑zero (or times out). Nginx may still be serving traffic because the probe is checking the wrong endpoint, path, or port. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval\n```Look for `Liveness probe` section and events such as `Killing container` or `Liveness probe failed`. Also run `kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 livenessProbe`. | *Adjust the probe* – correct `httpGet.path`, `port`, `initialDelaySeconds`, `periodSeconds`, `failureThreshold`, or change it to a `exec` probe that actually reflects the health of nginx. Then apply the updated manifest. |
| 2 | **Container exiting with non‑zero code (CrashLoopBackOff)** | Nginx may start, serve a request, then the process exits (e.g., mis‑configured `command`, signal handling, or a post‑start script). The pod is restarted, but you see a brief window where the service is still reachable. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state}'\n```If you see `waiting` → `reason: CrashLoopBackOff` or `terminated` with `exitCode != 0`, that’s the culprit. Also check `kubectl logs s08-bad-liveness -n kubexplain-eval --previous`. | *Make the container stay alive* – remove any stray `command`/`args` that override the default nginx entrypoint, ensure the process runs in the foreground (`nginx -g 'daemon off;'`), and fix any failing init‑container or postStart hook. |
| 3 | **Out‑of‑Memory (OOMKilled)** | The pod’s memory limit is lower than what nginx actually uses under load. When the cgroup OOM killer terminates the container, the pod is restarted. Nginx may still answer a few requests before the kill. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i OOM\n```or look for `State: Terminated` with `Reason: OOMKilled`. | *Raise the memory request/limit* or tune nginx’s worker processes / buffer sizes. Update the pod spec and redeploy. |
| 4 | **Resource‑quota or limit‑range enforcement** | If the namespace has a `LimitRange` that caps CPU/memory, the pod may be throttled or killed when it exceeds the quota, causing restarts. | ```bash\nkubectl get limitrange -n kubexplain-eval\nkubectl get resourcequota -n kubexplain-eval\n```Check whether the pod’s requests/limits exceed the allowed values. | *Adjust the pod’s requests/limits* to fit inside the quota, or request a higher quota from the cluster admin. |
| 5 | **Failed postStart / preStop hook** | A hook that returns a non‑zero exit code is treated as a container failure, triggering a restart. The main nginx process may still be running, masking the problem. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 postStart\n```Look for `exec` or `httpGet` hooks and any related events (`FailedPostStartHook`). | *Remove or correct the hook* so it succeeds, or add proper error handling. |
| 6 | **Node‑level problems (e.g., kubelet restart, node pressure)** | If the node is under memory or disk pressure, the kubelet may evict and restart pods. Nginx may appear healthy after the node recovers. | ```bash\nkubectl describe node <node‑name>\n```Check for conditions `MemoryPressure`, `DiskPressure`, or recent `Kubelet` restarts. | *Fix node health* – free up resources, increase node size, or move the pod to a healthier node (e.g., by adding a node selector or taint/toleration). |
| 7 | **Pod disruption caused by a controller (e.g., Deployment rollout)** | A rolling update or a mis‑configured `replicas` field can repeatedly delete and recreate the pod. Nginx works after each recreation, but you see continuous restarts. | ```bash\nkubectl rollout status deployment/<deployment‑name> -n kubexplain-eval\n```and `kubectl get events -n kubexplain-eval | grep s08-bad-liveness`. | *Stabilize the controller* – pause the rollout (`kubectl rollout pause`), fix the deployment spec, then resume (`kubectl rollout resume`). |
| 8 | **Incorrect `restartPolicy` (e.g., `Always` on a Job)** | If the pod is managed by a Job or CronJob but the policy is `Always`, the controller will keep recreating it. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep restartPolicy\n```| *Set the appropriate `restartPolicy`* (`OnFailure` for Jobs) or change the controller type. |

---

## How to verify each cause (once you have cluster access)

1. **Liveness probe** – `kubectl describe pod …` → look for `Liveness probe failed` events.  
2. **CrashLoopBackOff** – `kubectl get pod … -o jsonpath='{.status.containerStatuses[0].state}'` and `kubectl logs … --previous`.  
3. **OOMKilled** – `kubectl describe pod …` → `Reason: OOMKilled`.  
4. **Quota / LimitRange** – `kubectl get limitrange`, `kubectl get resourcequota`.  
5. **Hooks** – inspect the pod spec (`kubectl get pod -o yaml`) for `postStart` / `preStop`.  
6. **Node pressure** – `kubectl describe node <node>` for `MemoryPressure` / `DiskPressure`.  
7. **Controller rollout** – `kubectl rollout status deployment/<name>` and events.  
8. **RestartPolicy** – check the pod spec for `restartPolicy`.

---

## General remediation steps

1. **Edit the manifest** (YAML) with the corrected settings (probe, resources, hooks, etc.).  
2. **Apply the changes**: `kubectl apply -f <updated‑manifest>.yaml -n kubexplain-eval`.  
3. **Force a new rollout** (if a Deployment): `kubectl rollout restart deployment/<name> -n kubexplain-eval`.  
4. **Monitor**: `kubectl get pod s08-bad-liveness -w -n kubexplain-eval` to confirm the restarts stop.  

By checking the items in the order above, you’ll quickly pinpoint the most common trigger—an unhealthy liveness probe—and then move on to the less frequent, but still possible, causes. Good luck!
