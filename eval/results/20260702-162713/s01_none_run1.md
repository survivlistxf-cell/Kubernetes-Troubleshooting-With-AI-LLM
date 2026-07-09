# s01 | mode=none | run=1 | 16.8s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff**  
Below is a short‑list ordered by how often each shows up in real clusters, together with the **quick checks** you can run (once you have console access) and the **typical fix** for each problem.

---

### 1. The container process exits with a non‑zero code (application bug)  
**Why it’s common:**  
Most images run a custom binary or script. If the binary throws an exception, returns an error, or finishes immediately, the pod will be restarted over and over.

**How to verify (when you can run `kubectl`):**  

```bash
kubectl logs s01-crashloop -n kubexplain-eval          # last container logs
kubectl logs s01-crashloop -n kubexplain-eval --previous  # logs from the previous run
kubectl describe pod s01-crashloop -n kubexplain-eval
```

*Look for:*  
- Stack traces, “panic”, “Exception”, “Error” messages.  
- An exit code shown in the `State` section (e.g. `Exit Code: 1`).  

**Fix:**  
- Re‑build the image with the bug fixed, or adjust the command/args.  
- If the entrypoint script expects arguments, make sure they are correct in the pod spec.  
- Add more robust error handling / retries inside the app.

---

### 2. Liveness (or readiness) probe constantly failing  
**Why it’s common:**  
A mis‑configured probe (wrong path, wrong port, too aggressive `initialDelaySeconds`/`periodSeconds`) will mark the container unhealthy, causing the kubelet to kill it and trigger a restart.

**How to verify:**  

```bash
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -A5 "Liveness"
```

*Look for:*  
- Events like `Liveness probe failed` with timestamps.  
- The probe definition in the pod spec (path, port, timeout, etc.).  

**Fix:**  
- Adjust the probe parameters (increase `initialDelaySeconds`, `periodSeconds`, or `failureThreshold`).  
- Verify the endpoint actually returns a 2xx/3xx response (e.g. `curl http://localhost:<port>/<path>` inside the container).  
- If the app does not expose a health endpoint, consider removing the probe or using a simple `exec` probe that checks a process is running.

---

### 3. OOMKilled – container exceeds its memory limit  
**Why it’s common:**  
When a pod’s memory request/limit is too low for the workload, the kernel’s OOM killer terminates the process, and the pod restarts.

**How to verify:**  

```bash
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -i "OOMKilled"
```

*Look for:*  
- `State: Terminated` with `Reason: OOMKilled`.  
- High memory usage in the previous logs (`RSS`, `heap` messages).  

**Fix:**  
- Raise the `resources.limits.memory` (and optionally the `requests.memory`) in the pod spec.  
- Optimize the application’s memory usage (e.g., JVM heap settings, caching).  

---

### 4. Image pull error (registry auth, tag not found, network)  
**Why it’s common:**  
If the image cannot be pulled, the container never starts, and the pod repeatedly retries.

**How to verify:**  

```bash
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -i "Pull"
```

*Look for:*  
- Events such as `Failed to pull image`, `ImagePullBackOff`, `ErrImagePull`.  
- Authentication errors (`registry unauthorized`) or “manifest not found”.  

**Fix:**  
- Confirm the image name and tag are correct.  
- Verify the image exists in the registry.  
- Ensure a valid `imagePullSecret` is attached (or that the node has access).  
- If using a private registry, update the secret or re‑create it with correct credentials.

---

### 5. Missing ConfigMap / Secret or wrong volume mount  
**Why it’s common:**  
Applications often depend on configuration files or credentials. If the referenced ConfigMap/Secret is absent or the mount path is wrong, the process may exit immediately.

**How to verify:**  

```bash
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -i "ConfigMap\|Secret"
kubectl logs s01-crashloop -n kubexplain-eval
```

*Look for:*  
- Errors like “file not found”, “permission denied”, or “cannot read environment variable”.  

**Fix:**  
- Create or update the missing ConfigMap/Secret (`kubectl create configmap …`).  
- Check the `volumeMounts` paths and `envFrom` references in the pod spec.  
- Ensure the pod’s service account has permission to read the secret (RBAC).

---

### 6. Incorrect command / args in the pod spec  
**Why it’s common:**  
A typo or missing argument can cause the entrypoint to exit instantly.

**How to verify:**  

```bash
kubectl get pod s01-crashloop -n kubexplain-eval -o yaml | grep -A5 "command\|args"
```

*Look for:*  
- Empty `command` when the image expects one, or wrong ordering of arguments.  

**Fix:**  
- Edit the deployment/statefulset/daemonset to supply the correct `command` and/or `args`.  
- Test the command locally (e.g., `docker run <image> <cmd>`) to confirm it runs.

---

### 7. Init container failure  
**Why it’s common:**  
If an init container never completes (e.g., waiting for a DB, failing to download data), the main container never starts and the pod stays in a restart loop.

**How to verify:**  

```bash
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -A5 "Init Containers"
```

*Look for:*  
- `Init container <name> failed` events, exit codes, or logs (`kubectl logs <pod> -c <init-container>`).  

**Fix:**  
- Fix the init container’s script or image.  
- Add proper `restartPolicy: OnFailure` or increase timeouts.  
- If the init step is optional, consider removing it.

---

### 8. RBAC / ServiceAccount permission problems  
**Why it’s common:**  
If the pod’s process tries to call the Kubernetes API (or other cluster resources) without sufficient rights, it may abort.

**How to verify:**  

```bash
kubectl logs s01-crashloop -n kubexplain-eval | grep -i "Forbidden\|permission"
kubectl describe pod s01-crashloop -n kubexplain-eval | grep -i "serviceAccount"
```

*Look for:*  
- Errors like “cannot list pods: Forbidden”.  

**Fix:**  
- Grant the needed role/clusterrole to the ServiceAccount (`kubectl create rolebinding …`).  
- Verify the ServiceAccount name in the pod spec matches the one with the proper permissions.

---

### 9. Node‑level problems (disk pressure, network, kubelet crash)  
**Why it’s less common but possible:**  
If the node is under disk pressure or the kubelet is unstable, pods may be evicted and restarted.

**How to verify:**  

```bash
kubectl get node -o wide
kubectl describe node <node‑name> | grep -i "Pressure"
```

*Look for:*  
- Conditions like `DiskPressure=True`, `MemoryPressure=True`.  

**Fix:**  
- Drain the node and let the pod schedule elsewhere (`kubectl drain <node> --ignore-daemonsets`).  
- Resolve the underlying node issue (clean up disk, add resources).

---

## Quick “when‑you‑have‑access” checklist

1. **Inspect events & status** – `kubectl describe pod …`  
2. **Grab the latest logs** – `kubectl logs …` (add `--previous` for the prior run)  
3. **Check health probes** – look for `Liveness probe failed` events.  
4. **Verify resource limits** – see if the pod was OOMKilled.  
5. **Confirm image pull** – ensure the image exists and credentials work.  
6. **Validate ConfigMaps/Secrets** – missing data often causes immediate exits.  
7. **Review command/args** – a typo can be the whole problem.  
8. **Look at init containers** – they must finish before the main container starts.  
9. **Check RBAC** – permission errors appear in logs.  
10. **If everything looks fine, consider node health** – disk/memory pressure can cause restarts.

---

### TL;DR – Most probable root cause order

1. **Application crash (bug / wrong command)**  
2. **Liveness/readiness probe mis‑configuration**  
3. **OOMKilled (memory limit too low)**  
4. **Image pull failure**  
5. **Missing/incorrect ConfigMap or Secret**  
6. **Wrong command/args**  
7. **Init container failure**  
8. **RBAC / ServiceAccount permission issue**  
9. **Node‑level resource pressure**

When you regain console access, walk through the checklist in that order; the first item that shows a clear error is almost always the one to fix. Good luck!
