# s01 | mode=dynamic | run=2 | 12.3s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff**  
(ordered from the cause you’ll see most often to the less‑common)

### 1. The container process exits with a non‑zero code (application crash)  
**Why it happens** – The binary inside the image returns an error (e.g., uncaught exception, missing file, bad configuration) and the container stops. The kubelet then restarts it, leading to the loop.  
**How to verify** –  
* Look at the **`State` → `Last State` → `Reason`** and **`Exit Code`** in `kubectl describe pod s01-crashloop -n kubexplain-eval`.  
* Pull the pod logs (`kubectl logs …`) for the most recent start; the log will usually contain a stack trace or an explicit error message.  
**How to fix** –  
* Fix the bug in the application code or adjust its runtime configuration (environment variables, command‑line flags, config files).  
* Re‑build and push a new image, then roll out the updated pod (e.g., via a Deployment update).

---

### 2. Liveness (or readiness) probe repeatedly fails  
**Why it happens** – The probe definition (HTTP/TCP/exec) does not match the actual state of the container, so the kubelet kills the container thinking it is unhealthy.  
**How to verify** –  
* In `kubectl describe pod …` check the **Events** section for messages like *“Liveness probe failed”* or *“Readiness probe failed”*.  
* The pod’s **`STATUS`** will show `Running` but the **`RESTARTS`** count will increase quickly.  
**How to fix** –  
* Adjust the probe parameters (initialDelaySeconds, periodSeconds, timeoutSeconds, successThreshold, failureThreshold) so the container has enough time to start.  
* Verify the endpoint the probe hits actually returns the expected response (e.g., a health‑check endpoint).  
* If the probe is not needed, remove or disable it.

---

### 3. Image pull problems (wrong tag, private registry, missing credentials)  
**Why it happens** – The kubelet cannot download the container image, so the container never starts and the pod repeatedly retries.  
**How to verify** –  
* In `kubectl describe pod …` look for events such as *“Failed to pull image …”* or *“ImagePullBackOff”* preceding the CrashLoopBackOff.  
* The container’s **`State` → `Waiting` → `Reason`** may be `ErrImagePull` or `ImagePullBackOff`.  
**How to fix** –  
* Confirm the image name and tag are correct.  
* If the image lives in a private registry, ensure a valid `imagePullSecret` is attached to the service account or pod spec.  
* Test pulling the image manually on a node (`docker pull …`) to rule out network or auth issues, then update the pod spec accordingly.

---

### 4. OOMKill / resource limits exceeded  
**Why it happens** – The container exceeds its memory limit; the kernel’s OOM killer terminates it, and the pod restarts.  
**How to verify** –  
* In `kubectl describe pod …` you’ll see an event like *“Killing container …: OOMKilled”*.  
* The container’s **`Last State`** will have `Reason: OOMKilled`.  
**How to fix** –  
* Increase the memory request/limit in the pod spec, or tune the application’s memory usage.  
* If the pod is part of a Deployment, update the Deployment with the new resources and let it roll out.

---

### 5. Incorrect command / args in the pod spec  
**Why it happens** – The `command` or `args` fields are misspelled or point to a non‑existent binary, so the container exits immediately.  
**How to verify** –  
* `kubectl describe pod …` will show the container’s **`Command`** and **`Args`** as they were applied.  
* The logs will often contain “exec: …: not found” or similar messages.  
**How to fix** –  
* Correct the `command`/`args` fields (or remove them to let the image’s default entrypoint run).  
* Re‑apply the corrected manifest.

---

### 6. Missing ConfigMap / Secret or bad environment variable  
**Why it happens** – The container expects a file or env var that isn’t provided, crashes on start‑up.  
**How to verify** –  
* Look for events like *“Error: configmap … not found”* or *“Error: secret … not found”*.  
* Container logs often contain messages such as “missing configuration file” or “required env var XYZ not set”.  
**How to fix** –  
* Create or update the ConfigMap/Secret with the needed data.  
* Ensure the pod spec references the correct names and keys.  

---

### 7. Node‑level problems (disk pressure, network partition)  
**Why it happens** – The node is under disk pressure or other resource starvation, causing the kubelet to kill containers.  
**How to verify** –  
* `kubectl describe node <node>` will show conditions like `DiskPressure=True`.  
* Events on the pod may mention *“Node is under disk pressure”*.  
**How to fix** –  
* Free up disk space or add more nodes.  
* If the node is unhealthy, cordon and drain it, then let the pod be scheduled elsewhere.

---

## How to verify each cause when you regain access  

| Cause | What to look for (kubectl output) | Typical fix |
|-------|-----------------------------------|-------------|
| Application crash | `State: Terminated → Reason: Error` and non‑zero exit code; logs show stack trace | Patch code/config, rebuild image |
| Liveness probe | Events: “Liveness probe failed”; `RestartCount` rises quickly | Adjust probe settings or remove probe |
| Image pull | `State: Waiting → Reason: ErrImagePull` or `ImagePullBackOff`; events about pull failure | Correct image name/tag, add/repair `imagePullSecret` |
| OOMKill | `Reason: OOMKilled` in last state; events about memory pressure | Increase memory limits/requests |
| Bad command/args | `Command`/`Args` shown incorrectly; logs “not found” | Fix command/args in manifest |
| Missing Config/Secret | Events about missing ConfigMap/Secret; logs about missing env vars | Create or correct ConfigMap/Secret |
| Node problems | Node conditions `DiskPressure`, `MemoryPressure`; pod events referencing node | Resolve node resource issue, move pod to healthy node |

When you can run `kubectl` again, start with `kubectl describe pod s01-crashloop -n kubexplain-eval` – the **Events** and **Container Status** sections will usually point directly to the root cause. Then apply the corresponding fix from the list above.
