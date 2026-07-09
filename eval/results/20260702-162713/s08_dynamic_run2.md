# s08 | mode=dynamic | run=2 | 12.7s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Probable cause | Why it makes the pod restart (even if the container’s own process is healthy) | How to verify | How to fix |
|---|----------------|-----------------------------------------------------------------------------------|---------------|------------|
| 1 | **Liveness‑probe failure** | The kubelet kills the container when the probe returns non‑zero (timeout, failure code, or wrong path/port). The container may still be serving traffic, but the probe does not see it. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -A5 \"Liveness\" \n```<br>Check the **Events** section for messages like *“Liveness probe failed”* and the **Readiness/Liveness** section for the probe definition and last probe result. | • Adjust the probe parameters (initialDelaySeconds, periodSeconds, timeoutSeconds, failureThreshold) so the probe gives the container enough time to start.<br>• Verify the probe endpoint (path, port, scheme) is correct and reachable from the pod’s network namespace.<br>• If you don’t need a liveness probe, remove it or set `failureThreshold: 0` (not recommended for production). |
| 2 | **Readiness‑probe failure causing rapid restarts via a Deployment/ReplicaSet** | Some controllers (e.g., a Deployment with `restartPolicy: Always`) will delete and recreate a pod if it stays *NotReady* for a long time, especially when combined with a `PodDisruptionBudget` or a rolling‑update strategy that forces replacement. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep readinessProbe -A4\n```<br>Look for `readinessProbe` failures in the **Events**. Also check the owning ReplicaSet/Deployment for `strategy: RollingUpdate` with `maxUnavailable` that may be triggering replacement. | • Fix the readiness probe the same way as the liveness probe.<br>• If the pod is being recreated by the controller, you may see a new pod name each time; check the Deployment’s `kubectl rollout status` to confirm. |
| 3 | **CrashLoopBackOff caused by a side‑car or init container** | The main `nginx` container may stay up, but an init container or side‑car (e.g., a logging agent) could be exiting with a non‑zero code, causing the pod to be marked *Failed* and restarted. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -A5 \"Init Containers\" \n```<br>Check the **State** of each container; look for `Restart Count` > 0 on non‑nginx containers. | • Inspect the failing container’s logs (`kubectl logs <pod> -c <container>`).<br>• Fix its command, image, or resource limits, or remove it if it isn’t needed. |
| 4 | **Resource‑limit OOM kills** | If the pod’s memory request/limit is too low, the kubelet may OOM‑kill the container even though nginx appears to keep serving (the kill may be brief). The container restarts quickly, giving the impression that nginx “works”. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i \"oom\" -A3\n```<br>Look for events like *“OOMKilled”* or `containerStatuses[].state.terminated.reason: OOMKilled`. | • Increase the `resources.limits.memory` (and possibly `requests.memory`).<br>• Monitor actual memory usage (`kubectl top pod …`) once you have access. |
| 5 | **Node‑level issues (e.g., node NotReady, kubelet restart)** | If the node hosting the pod is repeatedly marked NotReady or the kubelet restarts, all pods on that node are killed and recreated, causing restarts unrelated to the pod spec. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o wide\n```<br>Note the `NODE` column, then run `kubectl describe node <node>` to see recent `Ready`/`NotReady` events. | • Fix the underlying node problem (disk pressure, network, kubelet crash, etc.).<br>• If the node is unhealthy, consider draining it and letting the scheduler move the pod to a healthy node. |
| 6 | **Pod‑disruption‑budget (PDB) or eviction policy** | A PDB with `maxUnavailable: 0` combined with a node drain or other eviction can cause the pod to be terminated and immediately recreated, appearing as a restart loop. | ```bash\nkubectl get pdb -n kubexplain-eval\n```<br>Check if a PDB applies to the pod’s labels. | • Adjust the PDB to allow at least one pod to be unavailable, or temporarily delete the PDB while troubleshooting. |
| 7 | **Mis‑configured `restartPolicy` (e.g., `Always` on a non‑Deployment)** | If the pod is created directly (not via a controller) with `restartPolicy: Always`, any container exit (even a graceful stop) will be restarted indefinitely. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep restartPolicy\n``` | • Change the policy to `OnFailure` or `Never` if you want the pod to stop after a clean exit, or manage it through a higher‑level controller (Deployment, Job, etc.). |

---

### How to verify the root cause when you *do* get cluster access  

1. **Inspect the pod’s status and events**  
   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval
   ```
   Look for repeated `Liveness probe failed`, `Readiness probe failed`, `OOMKilled`, or `Killing` messages.

2. **Check container‑specific restart counts**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].restartCount}'
   ```
   Identify which container(s) are restarting most often.

3. **View the probe definitions**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 "livenessProbe"
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 "readinessProbe"
   ```

4. **Examine logs of the failing container (if not nginx)**  
   ```bash
   kubectl logs s08-bad-liveness -n kubexplain-eval -c <container-name>
   ```

5. **Check node health**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide
   kubectl describe node <node-name>
   ```

6. **Review the owning controller** (Deployment, ReplicaSet, etc.)  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.metadata.ownerReferences[0].name}'
   kubectl describe deployment <name> -n kubexplain-eval
   ```

---

### Quick “fix‑first” checklist (apply in order of probability)

1. **Liveness probe** – increase `initialDelaySeconds`/`periodSeconds` or correct the endpoint.  
2. **Readiness probe** – same adjustments; ensure the service port matches.  
3. **Side‑car / init container** – check its logs, fix its command or remove it.  
4. **Memory limits** – raise `resources.limits.memory` (and `requests.memory`).  
5. **Node health** – if the node is flapping, cordon/drain it and let the pod reschedule.  
6. **PDB / eviction** – relax the budget temporarily.  
7. **Restart policy** – use a controller (Deployment) instead of a bare pod with `Always`.

By walking through the verification steps above, you’ll quickly pinpoint which of these common culprits is driving the restart loop for `s08-bad-liveness`. Once identified, apply the corresponding fix and the pod should stabilize.
