# s01 | mode=static | run=1 | 13.8s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff**  
(ordered from the cause you’ll see most often to the least common)

### 1. Application itself exits with a non‑zero status  
**Why it happens** – The entry‑point command or the binary inside the container returns an error (e.g., bad arguments, missing files, uncaught exception).  
**How to verify** – Look at the container’s **State → Last State → Reason** and **Message** in `kubectl describe pod`. The `kubectl logs` output will show the application’s error output or stack trace.  
**How to fix** –  
* Re‑build the image with a working command or correct configuration.  
* Test the container locally (`docker run …`) to be sure it stays up.  
* If the command is wrong in the pod spec, correct the `command`/`args` fields and redeploy.

---

### 2. Liveness (or readiness) probe repeatedly fails  
**Why it happens** – The probe’s HTTP/TCP/exec check returns failure, so the kubelet kills the container and restarts it.  
**How to verify** – In `kubectl describe pod` you’ll see events like *“Liveness probe failed”* and the **Last State → Reason: ProbeFailed**. The pod’s **Ready** column will stay `False`.  
**How to fix** –  
* Adjust the probe parameters (`initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, `failureThreshold`) so the app has time to start.  
* Verify the endpoint the probe checks is actually reachable inside the container.  
* If the probe is unnecessary, remove or disable it.

---

### 3. OOMKill – container exceeds its memory limit  
**Why it happens** – The process uses more RAM than the `resources.limits.memory` defined for the container; the kernel’s OOM killer terminates it.  
**How to verify** – The pod description will show **Reason: OOMKilled** in the container’s last state. `kubectl logs` may be empty because the process was killed abruptly.  
**How to fix** –  
* Increase the memory limit (or request) for the container.  
* Optimize the application to use less memory, or add swap/limit‑adjustments if appropriate.

---

### 4. Image‑pull problems (wrong tag, private registry, missing credentials)  
**Why it happens** – The kubelet cannot download the container image, so the container never starts and the pod repeatedly retries.  
**How to verify** – In the pod events you’ll see *“Failed to pull image”* or *“ImagePullBackOff”* followed by *“CrashLoopBackOff”*. The container’s **State → Reason** will be `ImagePullBackOff` or `ErrImagePull`.  
**How to fix** –  
* Confirm the image name and tag are correct and exist in the registry.  
* If the registry is private, ensure a valid `imagePullSecret` is attached to the service account or pod.  
* Test pulling the image manually on a node (`docker pull …`) to rule out network/auth issues.

---

### 5. Missing or invalid ConfigMap / Secret data  
**Why it happens** – The container expects a file or environment variable that is not provided (e.g., a required key is absent), causing it to exit immediately.  
**How to verify** – Check the pod’s **Events** for messages like *“configmap … not found”* or *“secret … not found”*. The container logs often contain “file not found” or “missing env var”.  
**How to fix** –  
* Verify the referenced ConfigMap/Secret exists in the same namespace.  
* Ensure the keys the application expects are present and correctly spelled.  
* Update the pod spec or the ConfigMap/Secret and redeploy.

---

### 6. Permission / SecurityContext issues  
**Why it happens** – The container runs as a non‑root user that lacks permission to access required files, sockets, or host resources, leading to immediate termination.  
**How to verify** – Look for log messages such as “permission denied” or events indicating *“FailedCreatePodSandBox”* due to security policy.  
**How to fix** –  
* Adjust the `securityContext` (runAsUser, runAsGroup, privileged flag) or the underlying RBAC/PodSecurityPolicy to grant needed rights.  
* If the image expects root, either run it as root (if policy permits) or modify the image to work as a non‑root user.

---

### 7. Dependency services unavailable (e.g., DB, external API)  
**Why it happens** – The application tries to connect to a service that is not reachable or not yet ready, crashes, and the pod restarts.  
**How to verify** – Application logs will show connection‑refused or timeout errors. The pod may also have a readiness probe that fails because the service is down.  
**How to fix** –  
* Ensure the dependent service is running and reachable (correct DNS name, service name, port).  
* Add appropriate retry/back‑off logic in the app, or use an `initContainer` to wait for the dependency before the main container starts.

---

### 8. Node‑level problems (disk pressure, kubelet restart)  
**Why it happens** – The node is under disk pressure or the kubelet restarts, causing the pod to be killed and recreated repeatedly.  
**How to verify** – Node events will show *“NodeHasDiskPressure”* or *“KubeletRestarted”*. The pod’s `RESTARTS` count will increase quickly even though the container itself may not have crashed.  
**How to fix** –  
* Free up disk space or adjust eviction thresholds on the node.  
* Investigate why the kubelet is restarting (e.g., OOM, misconfiguration) and remediate at the node level.

---

## How to verify each cause when you regain cluster access  

1. **Describe the pod** – `kubectl describe pod s01-crashloop -n kubexplain-eval`  
   *Shows events, last state reasons, probe failures, OOMKilled, ImagePullBackOff, etc.*

2. **Check container logs** – `kubectl logs s01-crashloop -n kubexplain-eval --previous`  
   *Shows the last output before the container died; useful for app‑level errors.*

3. **Inspect the pod spec** – `kubectl get pod s01-crashloop -n kubexplain-eval -o yaml`  
   *Verify command/args, probes, resource limits, image name, ConfigMap/Secret refs, securityContext.*

4. **Look at related resources** –  
   * ConfigMaps/Secrets: `kubectl get cm,secret -n kubexplain-eval`  
   * Services/endpoints the pod talks to: `kubectl get svc,ep -n <namespace>`  
   * Node status if needed: `kubectl get node <node-name> -o yaml`

5. **Review events across the namespace** – `kubectl get events -n kubexplain-eval --sort-by=.metadata.creationTimestamp`  
   *Helps spot recurring failures like image pull, probe failures, or node pressure.*

---

## Quick remediation checklist (apply in order of likelihood)

| # | Action | When to apply |
|---|--------|---------------|
| 1 | Fix the application’s command or code that exits with error. | Logs show stack trace / “exit code 1”. |
| 2 | Adjust or disable failing liveness/readiness probes. | ProbeFailed events appear. |
| 3 | Increase memory limits or reduce memory usage. | Reason = OOMKilled. |
| 4 | Correct image name/tag or add proper imagePullSecret. | ImagePullBackOff / ErrImagePull events. |
| 5 | Ensure required ConfigMap/Secret exists and contains correct keys. | Missing‑config events or “file not found” logs. |
| 6 | Update securityContext / RBAC to grant needed permissions. | “permission denied” in logs. |
| 7 | Verify dependent services are up and reachable; add retries. | Connection‑refused errors in logs. |
| 8 | Resolve node‑level issues (disk pressure, kubelet restarts). | Node events indicate pressure or restarts. |

Apply the first fix that matches the evidence you see; after each change, redeploy the pod (or delete it so the controller recreates it) and observe whether the `CrashLoopBackOff` disappears. Once the pod reaches a **Running** state with a stable `READY` condition, the problem is resolved.
