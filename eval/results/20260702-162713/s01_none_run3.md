# s01 | mode=none | run=3 | 13.4s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff**  
(ordered from the cause that most often shows up to the less‑common ones)

### 1. The container process exits immediately (non‑zero exit code)  
**Why it happens**  
* The entry‑point script or binary returns an error right away.  
* A required argument, environment variable, or file is missing, so the process aborts.  

**How to verify (when you can run `kubectl`)**  
* `kubectl logs s01-crashloop -n kubexplain-eval` – look at the last lines before the container stops.  
* `kubectl describe pod s01-crashloop -n kubexplain-eval` – check the **State → Terminated → Reason** and **Exit Code** fields.  

**Fix**  
* Make sure the image’s entry‑point works with the supplied `command`/`args`.  
* Add any missing environment variables, command‑line flags, or config files.  
* If the container is supposed to run a long‑lived service, change the Dockerfile/command so it stays alive (e.g., run the service binary instead of a one‑shot script).

---

### 2. Image pull / image‑related problems  
**Why it happens**  
* The image tag does not exist in the registry, or the registry requires authentication that the pod’s imagePullSecrets don’t provide.  
* The registry is unreachable (network policy, DNS, proxy).  

**How to verify**  
* `kubectl describe pod …` – look for events like **Failed to pull image** or **ImagePullBackOff**.  
* `kubectl get events -n kubexplain-eval --field-selector involvedObject.name=s01-crashloop` – see any pull‑related warnings.  

**Fix**  
* Confirm the image name and tag are correct.  
* Ensure the appropriate `imagePullSecrets` exist and are referenced in the pod spec.  
* If the registry is private, verify the secret contains valid credentials.  
* Test pulling the image manually from a node (`docker pull …` or `crictl pull …`) once you have cluster access.

---

### 3. Liveness or readiness probe failures that kill the container  
**Why it happens**  
* The probe is mis‑configured (wrong port, path, or initialDelaySeconds).  
* The application needs more time to start than the probe allows, causing the kubelet to kill it repeatedly.  

**How to verify**  
* `kubectl describe pod …` – under **Events** you’ll see messages like **Liveness probe failed** or **Readiness probe failed**.  
* `kubectl get pod s01-crashloop -o jsonpath='{.status.containerStatuses[0].lastState}'` – shows the reason for the last termination (often “ProbeFailed”).  

**Fix**  
* Adjust the probe parameters: increase `initialDelaySeconds`, `periodSeconds`, or `failureThreshold`.  
* Verify the endpoint actually returns the expected HTTP code or TCP success.  
* If the probe isn’t needed, remove it (or set `failureThreshold` high enough) until the container stabilises.

---

### 4. Out‑of‑Memory (OOM) kill  
**Why it happens**  
* The container exceeds its memory limit; the kernel OOM killer terminates it.  

**How to verify**  
* In `kubectl describe pod …` look for **State → Terminated → Reason: OOMKilled**.  
* Check the pod’s **containerStatuses → lastState → terminated → message** for “OOMKilled”.  

**Fix**  
* Raise the memory `requests`/`limits` in the pod spec.  
* Optimize the application’s memory usage (e.g., reduce cache size, fix memory leaks).  
* If you cannot change limits, add a side‑car that monitors memory and restarts the pod less aggressively.

---

### 5. Missing ConfigMap / Secret or invalid volume mount  
**Why it happens**  
* The pod references a ConfigMap/Secret that does not exist, or the volume mount path is incorrect, causing the container to crash on start‑up.  

**How to verify**  
* `kubectl describe pod …` – events such as **MountVolume.SetUp failed** or **ConfigMap “…“ not found**.  
* Container logs often contain errors like “cannot find configuration file”.  

**Fix**  
* Create the missing ConfigMap/Secret (`kubectl create configmap …` or `kubectl create secret …`).  
* Ensure the names match exactly (including case).  
* Verify the mountPath is writable and the file permissions are correct.

---

### 6. Application‑level crash (e.g., uncaught exception, missing dependency)  
**Why it happens**  
* The binary throws an exception or exits because a runtime dependency (library, database) is unavailable.  

**How to verify**  
* Examine the container logs for stack traces or error messages.  
* Check external service connectivity (e.g., database endpoint reachable) from within the pod (once you have exec access).  

**Fix**  
* Add the missing dependency or correct the configuration (e.g., set proper DB connection string).  
* Add retry logic or a start‑up script that waits for dependent services before launching the main process.

---

### 7. Node‑level problems (disk pressure, network issues, kubelet restart)  
**Why it happens**  
* The node is under disk pressure, causing the kubelet to evict or restart pods.  
* Network policies or CNI failures prevent the pod from reaching required services, leading to immediate exit.  

**How to verify**  
* `kubectl describe node <node-name>` – look for **Conditions** like `DiskPressure=True`.  
* Events on the pod may show **FailedScheduling** or **NodeAffinity** problems.  

**Fix**  
* Free up disk space or add more nodes.  
* Investigate CNI plugin logs and network policies.  
* If the node is unhealthy, cordon and drain it, then let the pod be scheduled on a healthy node.

---

## How to verify each cause when you regain cluster access  

| Step | Command (run when you can) | What you’re looking for |
|------|----------------------------|--------------------------|
| 1️⃣  | `kubectl logs s01-crashloop -n kubexplain-eval` | Last log lines, error messages, stack traces |
| 2️⃣  | `kubectl describe pod s01-crashloop -n kubexplain-eval` | **State → Terminated** reason, **Events** (ImagePullBackOff, OOMKilled, ProbeFailed, MountVolume errors) |
| 3️⃣  | `kubectl get events -n kubexplain-eval --field-selector involvedObject.name=s01-crashloop` | Any warning/error events tied to the pod |
| 4️⃣  | `kubectl exec -it s01-crashloop -n kubexplain-eval -- /bin/sh` (if it stays up) | Test connectivity to DB, config files, environment variables |
| 5️⃣  | `kubectl get pod s01-crashloop -o jsonpath='{.status.containerStatuses[0].lastState}'` | Detailed termination reason (e.g., `ExitCode`, `Signal`, `Reason`) |
| 6️⃣  | `kubectl get configmap,secret -n kubexplain-eval` | Verify referenced ConfigMaps/Secrets exist |
| 7️⃣  | `kubectl get node $(kubectl get pod s01-crashloop -n kubexplain-eval -o jsonpath='{.spec.nodeName}') -o yaml` | Node health conditions (DiskPressure, MemoryPressure, NetworkUnavailable) |

---

### Quick “first‑check” checklist (when you’re back in the cluster)

1. **Logs** – do they show an obvious error or a stack trace?  
2. **Describe** – does the pod’s **Events** list a specific failure (pull, OOM, probe)?  
3. **Termination reason** – `ExitCode != 0` → application error; `OOMKilled` → memory; `Error` → image or config problem.  
4. **Probes** – are they firing too early?  
5. **Resources** – are limits too low?  
6. **Config/Secrets** – are they present and mounted correctly?  
7. **Node health** – any pressure conditions?

Address the first failing check you encounter; that usually resolves the CrashLoopBackOff. If fixing one cause doesn’t stop the restarts, move down the list to the next likely culprit. Good luck!
