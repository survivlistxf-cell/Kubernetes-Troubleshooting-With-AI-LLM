# s08 | mode=static | run=1 | 13.5s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Likely cause (most to least probable) | How to verify (when you regain access) | How to fix |
|---|----------------------------------------|----------------------------------------|------------|
| 1 | **Liveness probe is failing** – the probe returns a non‑zero exit code or times‑out, so the kubelet kills the container and the pod is restarted. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval\n```Look for the **`Liveness probe failed`** event and the `Last State` of the container (e.g. `reason: ProbeFailed`). Also inspect the pod spec (`kubectl get pod … -o yaml`) for the `livenessProbe` fields (path, port, httpGet, exec, initialDelaySeconds, periodSeconds, timeoutSeconds, failureThreshold). | * Adjust the probe so it matches a real healthy endpoint (e.g. change `httpGet.path` to `/healthz` or increase `initialDelaySeconds`). * If the probe is too aggressive, raise `periodSeconds`/`failureThreshold` or add a `successThreshold`. * Apply the corrected manifest (`kubectl apply -f …`). |
| 2 | **Container crashes (CrashLoopBackOff)** – the nginx process exits with a non‑zero code (e.g. mis‑configured command, missing file, permission error). | In the same `kubectl describe pod` output, check the **`State: Waiting` / `Reason: CrashLoopBackOff`** line and the `lastState` of the container (`reason: Error`, `exitCode`). Pull the container logs: `kubectl logs s08-bad-liveness -n kubexplain-eval --previous`. | * Fix the underlying error (wrong command, missing config, bad env var). * If the image is wrong, update `image:` or `command:` fields. * Re‑apply the pod/deployment. |
| 3 | **OOMKilled (out‑of‑memory)** – the pod exceeds its memory limit, the kernel kills the process and the kubelet restarts it. | Look for `Reason: OOMKilled` in the container’s `lastState`. You can also see it in `kubectl get pod … -o yaml` under `containerStatuses[].state.terminated.reason`. | * Raise the memory `requests`/`limits` or remove the limit if not needed. * Optimize nginx configuration (e.g., lower `worker_processes`, `worker_connections`). * Apply the updated resources. |
| 4 | **Init container failure** – an init container never completes, so the main container never reaches a stable state and the pod is repeatedly restarted. | In `kubectl describe pod`, check the **Init Containers** section for a `State: Waiting` or `Terminated` with non‑zero exit code. | * Fix the init container (wrong image, command, missing secret, etc.). * If the init step is no longer required, remove it from the spec. |
| 5 | **Node problems (e.g., NotReady, eviction)** – the node becomes unhealthy, pods are evicted and recreated on another node, appearing as restarts. | `kubectl describe node <node-name>` for conditions like `Ready=False`. In the pod events you may see `NodeLost` or `Evicted`. | * Resolve node issues (restart kubelet, fix network/storage, etc.). * If the node is permanently unhealthy, cordon and drain it, then let the scheduler place the pod on a healthy node. |
| 6 | **Side‑car container repeatedly failing** – a second container in the same pod crashes, causing the pod’s restart count to increase. | In `kubectl describe pod`, examine each container’s `State` and `Last State`. Identify any container other than nginx with `Reason: CrashLoopBackOff` or `OOMKilled`. | * Fix the side‑car (wrong image, missing config) or remove it if not needed. |
| 7 | **Deployment/ReplicaSet rolling update** – a new replica set is being rolled out and the old pods are terminated/re‑created. | Check the owning controller: `Controlled By: ReplicaSet/...` and then `kubectl rollout status deployment/<name> -n kubexplain-eval`. If a rollout is in progress, the restarts are expected. | * Wait for the rollout to finish, or pause/rollback (`kubectl rollout pause …`, `kubectl rollout undo …`). |
| 8 | **Pod disruption caused by a PodDisruptionBudget (PDB) or manual delete** – an external process repeatedly deletes the pod (e.g., a CI job). | Look for `Deleted` events with a user or controller name. | * Identify the external actor (CI pipeline, cronjob) and stop the unwanted deletions. |

---

## How to verify each cause (once you can run `kubectl`)

1. **General pod health**  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide
   kubectl describe pod s08-bad-liveness -n kubexplain-eval
   ```

2. **Container logs** (current and previous)  
   ```bash
   kubectl logs s08-bad-liveness -n kubexplain-eval
   kubectl logs s08-bad-liveness -n kubexplain-eval --previous
   ```

3. **Full pod spec (YAML)** – to inspect probes, resources, init containers, side‑cars  
   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml
   ```

4. **Node status** (if the pod is scheduled)  
   ```bash
   NODE=$(kubectl get pod s08-bad-liveness -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   kubectl describe node $NODE
   ```

5. **Deployment / ReplicaSet status**  
   ```bash
   kubectl get rs -n kubexplain-eval
   kubectl rollout status deployment/<deployment-name> -n kubexplain-eval
   ```

---

## Quick “first‑fix” checklist (apply in order)

1. **Check the liveness probe** – most common source of silent restarts.  
   *If it’s failing, either correct the endpoint or relax the timing.*  

2. **Look at container exit codes / logs** – if the probe is fine, the container itself is exiting.  

3. **Verify memory usage** – OOMKilled is the next frequent culprit.  

4. **Inspect init containers and side‑cars** – they can cause restarts even when nginx is healthy.  

5. **Confirm node health** – a flaky node will cause pod churn.  

6. **Review deployment rollout** – ensure you’re not in the middle of an update.  

By following the verification steps above you’ll be able to pinpoint the exact reason for the repeated restarts and apply the corresponding fix. Once the root cause is addressed, the pod’s `RESTARTS` count should stop increasing.
