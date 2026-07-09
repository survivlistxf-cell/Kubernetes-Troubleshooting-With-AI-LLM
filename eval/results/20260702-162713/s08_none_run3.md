# s08 | mode=none | run=3 | 15.2s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Likely cause (most to least probable) | Why it produces restarts even though nginx “works” | How to verify (when you have access) | How to fix (once verified) |
|---|----------------------------------------|------------------------------------------------------|--------------------------------------|----------------------------|
| 1 | **Mis‑configured liveness probe** (wrong path, port, timeout, or initial‑delay) | The probe tells the kubelet the container is unhealthy, so the kubelet kills it and starts a new one. Nginx may still be serving traffic, but the probe never succeeds. | `kubectl describe pod s08-bad-liveness -n kubexplain-eval` → look for `Liveness:` events (`Failed` → `Killing`). <br> `kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state}'` → see `waiting` → `reason: CrashLoopBackOff` caused by `Liveness probe failed`. <br> `kubectl logs s08-bad-liveness -n kubexplain-eval` – the log will usually show “Liveness probe failed”. | Adjust the probe definition: <br> - Verify the endpoint (`/healthz`, `/`, or a custom URL) actually returns **2xx**. <br> - Increase `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, or `failureThreshold` so the probe gives nginx enough time to start. <br> - If you don’t need a liveness probe, remove it. Apply the corrected manifest (`kubectl apply -f …`). |
| 2 | **Container exiting with a non‑zero code (CrashLoopBackOff)** – e.g., nginx receives a signal, mis‑configured command, or a startup script that exits. | Even a brief exit triggers a restart. Nginx may restart quickly, so you see it “working”, but the kubelet records a crash and restarts the pod repeatedly. | `kubectl logs s08-bad-liveness -n kubexplain-eval --previous` – shows the last terminated container output and exit code. <br> `kubectl describe pod …` → look for `State: Terminated` with `Exit Code: X`. | Ensure the container’s `command`/`args` start nginx in the foreground (`nginx -g 'daemon off;'`). Remove any stray `exit` statements in entrypoint scripts. Re‑build the image or edit the pod spec accordingly. |
| 3 | **Out‑of‑Memory (OOMKilled) due to low memory limits** | The node’s OOM killer terminates the container when it exceeds its memory request/limit. Nginx may survive a short spike, but the OOM event forces a restart. | `kubectl describe pod …` → under **Events** look for `OOMKilled`. <br> `kubectl get pod … -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'` → should show `OOMKilled`. | Raise the memory **request** and **limit** for the nginx container, or tune nginx worker processes (`worker_processes`, `worker_connections`) to use less RAM. Apply the updated manifest. |
| 4 | **Node‑level pressure (disk, memory, or CPU) causing pod eviction & restart** | When a node is under pressure, the kubelet may evict pods and the controller recreates them. The pod appears to restart even though the container itself is fine. | `kubectl describe node <node‑name>` → check **Conditions** (`MemoryPressure`, `DiskPressure`). <br> `kubectl get events --field-selector involvedObject.kind=Pod,involvedObject.name=s08-bad-liveness -n kubexplain-eval` → look for `Evicted` events. | Move the pod to a less‑loaded node (e.g., add a node selector or taint/toleration), or increase node resources. If using a DaemonSet, consider spreading the workload. |
| 5 | **Readiness probe causing the pod to be repeatedly killed by a higher‑level controller** (e.g., a custom controller that deletes pods that never become ready) | Some operators or CI pipelines watch readiness and may delete/recreate pods that stay `NotReady`. Nginx can still serve, but the pod never reaches `Ready`, triggering the external action. | Check the **controller** that created the pod (Deployment, StatefulSet, custom operator). Look at its events: `kubectl describe deployment <owner> -n kubexplain-eval`. <br> Search for messages like “Pod deleted because it failed readiness”. | Fix the readiness probe (correct path/port, increase delays) or adjust the external controller’s policy. |
| 6 | **Init container failure** – an init container exits with error, causing the pod to restart before the main container runs. | The main nginx container may be fine, but the pod never progresses past the init phase, so the kubelet keeps trying. | `kubectl describe pod …` → under **Init Containers** see `State: Terminated` with non‑zero exit code. <br> `kubectl logs s08-bad-liveness -c <init‑container> -n kubexplain-eval` for logs. | Fix the init container (correct image, command, or required files). If the init container is unnecessary, remove it. |
| 7 | **Image pull / registry authentication issues** (transient) | If the image cannot be pulled, the pod stays in `ImagePullBackOff`. When the registry becomes reachable, the pod starts, runs nginx, then the next pull attempt fails again, causing a restart loop. | `kubectl describe pod …` → look for `ImagePullBackOff` or `ErrImagePull` events. | Verify the image tag exists, the secret for the registry is correct, and the node can reach the registry. Update the image reference or secret. |
| 8 | **Pod disruption caused by a mis‑configured `PodDisruptionBudget` or rolling update** | A PDB that forces eviction of the pod during a rollout can cause repeated restarts if the new replica never becomes ready. | `kubectl get pdb -n kubexplain-eval` → see if a PDB targets the pod. <br> Check rollout status: `kubectl rollout status deployment/<owner> -n kubexplain-eval`. | Adjust the PDB’s `minAvailable`/`maxUnavailable` values or fix the rollout strategy. |
| 9 | **External watchdog or side‑car that kills the container** | Some side‑car containers (e.g., a custom health‑checker) may send a `SIGTERM` to nginx if they think it’s unhealthy. | Look at side‑car container logs (`kubectl logs s08-bad-liveness -c <sidecar> -n kubexplain-eval`). <br> Check `kubectl describe pod` for `TerminationReason` from another container. | Fix the side‑car logic or remove it if unnecessary. |

---

## General verification checklist (run when you regain access)

1. **Inspect pod status & events**  
   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval
   ```
   Look for `Liveness probe failed`, `OOMKilled`, `Failed`, `Evicted`, or `ImagePullBackOff`.

2. **Check container exit codes & previous logs**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'
   kubectl logs s08-bad-liveness -n kubexplain-eval --previous
   ```

3. **Review probe definitions**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 livenessProbe
   ```

4. **Look at resource usage** (if metrics server is installed)  
   ```bash
   kubectl top pod s08-bad-liveness -n kubexplain-eval
   ```

5. **Identify the owning controller** (Deployment, DaemonSet, etc.)  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.metadata.ownerReferences[0].name}'
   ```

6. **Check node health** (if you suspect node pressure)  
   ```bash
   kubectl describe node $(kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   ```

---

## Quick “fix‑first” approach (once you can run commands)

1. **If the liveness probe is the culprit** – edit the pod (or its Deployment) and either correct the endpoint or increase the delays. Example patch:
   ```bash
   kubectl patch deployment <owner> -n kubexplain-eval \
     --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/initialDelaySeconds","value":30}]'
   ```

2. **If OOMKilled** – raise limits:
   ```bash
   kubectl set resources deployment <owner> -n kubexplain-eval \
     --limits=memory=256Mi --requests=memory=128Mi
   ```

3. **If the container exits** – ensure nginx runs in foreground:
   ```yaml
   command: ["nginx"]
   args: ["-g", "daemon off;"]
   ```

4. **If an init container fails** – fix its command or image, or remove it from the manifest.

5. **If node pressure** – add more nodes or rebalance pods with `nodeSelector`/`affinity`.

Apply the corrected manifest (`kubectl apply -f …`) and watch the pod stabilize:
```bash
kubectl rollout restart deployment <owner> -n kubexplain-eval
kubectl get pod s08-bad-liveness -n kubexplain-eval -w
```

---

### Bottom line
The **single most common reason** for a pod that “looks fine” but keeps restarting is a **liveness‑probe misconfiguration**. Verify the probe first; if it’s healthy, move down the list (exit code, OOM, node pressure, etc.). Each item above includes the exact checks you can run and the concrete changes needed to stop the restart loop. Good luck!
