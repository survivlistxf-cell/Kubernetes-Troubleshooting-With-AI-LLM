# s08 | mode=static | run=2 | 14.7s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## Most likely reasons why **`s08-bad-liveness`** keeps being restarted  

| # | Probable cause | Why it makes the pod restart (even though nginx serves traffic) | How to verify it | How to fix it |
|---|----------------|---------------------------------------------------------------|------------------|---------------|
| 1 | **Liveness‑probe failure** – the probe is mis‑configured (wrong path, port, scheme, or timing) so the kubelet kills the container and the pod is recreated. | The kubelet treats a failed liveness check as a sign the container is unhealthy and sends a `SIGTERM` → the pod is restarted. Nginx may still be serving requests when the probe runs, so you see “it works” but the probe keeps failing. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval\n``` Look for events such as `Liveness probe failed` and the `Readiness/Liveness` section that shows `http-get` details and the failure count. <br> Also check the pod’s recent logs: <br> ```bash\nkubectl logs s08-bad-liveness -n kubexplain-eval --previous\n``` | *Correct the probe* – edit the pod (or its Deployment/StatefulSet) so the `livenessProbe` points to a URL that actually returns `200` (e.g. `/healthz` or `/`). <br> *Adjust timing* – increase `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, or raise `failureThreshold` so the probe does not fire before nginx is ready. <br> Example patch: <br> ```bash\nkubectl edit deployment <name> -n kubexplain-eval\n``` and modify the `livenessProbe` block. |
| 2 | **Container exiting because of an uncaught signal** – a script or side‑car in the pod is exiting (or the main process receives `SIGTERM`) which the kubelet interprets as a crash. | Even if nginx stays up, if the PID that Kubernetes watches (the first container process) exits, the whole pod is considered terminated and is restarted. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep -A5 -i command\n``` Look for `command:` or `args:` that might finish quickly. Also inspect the exit code in `kubectl describe pod` (`State: Terminated` with a non‑zero `ExitCode`). | *Make the entrypoint long‑running* – ensure the container’s main process stays alive (e.g., use `nginx -g 'daemon off;'`). <br> *Remove or fix side‑car scripts* that exit prematurely. |
| 3 | **Out‑of‑Memory (OOM) kill** – the pod’s memory limit is too low, the kernel OOM killer terminates the nginx process, and the pod restarts. | When the container exceeds its `memory` limit, the kernel kills the process; the kubelet then restarts the pod. Nginx may still answer some requests before the kill, giving the impression that it “works”. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i OOM\n``` and check the `Events` for `OOMKilled`. Also look at the pod’s `resources.limits.memory` value. | *Raise the memory limit* or *reduce the workload* (e.g., lower `worker_processes`). <br> Example: edit the Deployment and increase `resources.limits.memory` and `resources.requests.memory`. |
| 4 | **Node‑level problems (e.g., node not ready, eviction, or taints)** – the node becomes `NotReady` or has a taint that forces the pod to be evicted and rescheduled. | The pod is killed on the problematic node and recreated on another node, which appears as a restart. | ```bash\nkubectl get node -o wide\nkubectl describe node <node‑name>\n``` Look for `Ready` status, `Taints`, or recent `NodeNotReady` events. | *Fix the node* – restart kubelet, address disk/CPU pressure, or remove the taint. <br> If the node is unhealthy, let the scheduler place the pod on a healthy node (no action needed beyond fixing the node). |
| 5 | **Image‑pull or registry issues** – the pod restarts because the container image cannot be pulled after a crash (e.g., image tag changed, auth failure). | The first start may have succeeded, but after a crash the kubelet tries to pull the image again and fails, causing a restart loop. | ```bash\nkubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i Pull\n``` Look for `Failed to pull image` events. | *Ensure the image exists and the pull secret is correct*; use an immutable tag or digest. <br> If the image is fine, you can set `imagePullPolicy: IfNotPresent` to avoid re‑pulling on every restart. |
| 6 | **Mis‑configured `restartPolicy` (e.g., `Always` on a Job)** – the pod is part of a Job/CronJob that is meant to run once, but the policy forces a restart. | The pod finishes successfully, but the controller restarts it because the policy says “always”. | ```bash\nkubectl get pod s08-bad-liveness -n kubexplain-eval -o yaml | grep restartPolicy\n``` | *Change the controller* (use a Deployment for long‑running services) or set `restartPolicy: OnFailure`/`Never` where appropriate. |

### How to verify the root cause without direct cluster access  

1. **Ask a teammate (or use a remote console) to run `kubectl describe pod …`** – the `Events` section will immediately reveal liveness‑probe failures, OOM kills, node problems, or image‑pull errors.  
2. **Check the pod’s recent logs** (`kubectl logs … --previous`) – a crash message or “failed to start” line often points to a mis‑configured command or a signal.  
3. **Inspect the pod’s YAML** (`kubectl get pod … -o yaml`) – look at `livenessProbe`, `resources.limits`, `command/args`, and `restartPolicy`.  
4. **Look at node health** (`kubectl get nodes` and `kubectl describe node …`) – if the node is `NotReady` or has taints, the pod will be evicted and recreated.  

### Quick “fix checklist” you can hand to the on‑call person  

1. **Liveness probe** – verify URL/port, increase delays, or temporarily disable (`kubectl edit …` → remove `livenessProbe`).  
2. **Memory limits** – raise `resources.limits.memory` if OOMKilled events appear.  
3. **Entrypoint** – ensure the container’s main process stays alive (`nginx -g 'daemon off;'`).  
4. **Node health** – restart kubelet or move the pod to a healthy node.  
5. **Image pull** – confirm image tag/digest and pull secret; set `imagePullPolicy: IfNotPresent`.  
6. **Controller type** – use a Deployment for a long‑running service instead of a Job with `restartPolicy: Always`.  

Following the verification steps in order of the table will usually pinpoint the exact reason why **`s08-bad-liveness`** is being restarted, and the corresponding fix will stop the restart loop while keeping nginx serving traffic.  

*References:*  
- Debug Running Pods – <https://kubernetes.io/docs/tasks/debug/debug-running-pod/>  
- Debug Services – <https://kubernetes.io/docs/tasks/debug/debug-application/debug-service/>  
- Troubleshooting Applications – <https://kubernetes.io/docs/tasks/debug/debug-application/>
