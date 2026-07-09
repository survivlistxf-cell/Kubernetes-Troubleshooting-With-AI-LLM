# s01 | mode=static | run=2 | 14.3s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

**Most likely reasons a pod ends up in a CrashLoopBackOff**  
(ordered from the cause you’ll see most often to the less‑common ones)

---

### 1. The application itself exits with a non‑zero code  
**Why it happens** – The container starts, runs the entry‑point command, and then terminates (or crashes) because of a bug, missing environment variable, bad configuration, etc. The kubelet restarts it, sees the failure again and marks the pod *CrashLoopBackOff*.  

**How to verify**  
* Look at the pod’s **status → containerStatuses → lastState → terminated → reason** and **exitCode**.  
* The reason will be *Error* (or a custom message) and the exit code will be non‑zero (usually 1).  
* Check the container logs for the stack‑trace or error message that appears just before the termination.  

**How to fix**  
* Re‑produce the failure locally (run the same image with the same command/args).  
* Fix the bug, add the missing env‑var/flag, or adjust the command.  
* Re‑build the image (or edit the pod spec) and redeploy.

---

### 2. OOMKilled – the container exceeds its memory limit  
**Why it happens** – The pod has a memory **limit** that is lower than what the process needs. When the cgroup runs out of memory the kernel kills the container; the pod is restarted and the cycle repeats.  

**How to verify**  
* In the pod description the container’s **lastState → terminated → reason** will be *OOMKilled*.  
* The **message** may contain “Container killed due to memory limit”.  
* The **events** for the pod will show a warning about “memory limit exceeded”.  

**How to fix**  
* Raise the memory **request** and **limit** in the pod spec (or remove the limit if it isn’t needed).  
* If the workload truly needs more memory, consider scaling the node pool or adding a larger node.  
* Optionally add a **resource‑request** that matches the typical usage so the scheduler places the pod on a node with enough capacity.

---

### 3. Liveness‑probe failure causing the kubelet to kill the container  
**Why it happens** – A liveness probe (HTTP, TCP, or exec) is mis‑configured or the application is not ready to answer it yet. When the probe fails repeatedly, the kubelet kills the container, which is then restarted.  

**How to verify**  
* The pod description will show **containerStatuses → lastState → terminated → reason: “Error”** and the **message** will mention “Liveness probe failed”.  
* The **events** list entries like “Liveness probe failed: …”.  

**How to fix**  
* Adjust the probe parameters (initialDelaySeconds, periodSeconds, timeoutSeconds, failureThreshold) so the probe gives the app enough time to start.  
* Verify the endpoint the probe checks actually returns a healthy response.  
* If the probe is unnecessary, remove it or change it to a readiness probe only.

---

### 4. Wrong command / args or missing entry‑point in the image  
**Why it happens** – The pod spec overrides the image’s default command or args with an invalid value, or the Dockerfile’s `ENTRYPOINT`/`CMD` is missing. The container starts, immediately exits with status 0 or a non‑zero code, and the pod loops.  

**How to verify**  
* In the pod description the **container → command** and **args** fields will be present.  
* The **lastState → terminated → reason** will be *Completed* (exit 0) or *Error* (non‑zero).  
* Logs will usually be empty or show the shell exiting instantly.  

**How to fix**  
* Remove the erroneous `command`/`args` override, or correct them to match what the image expects.  
* If you need a custom command, ensure it stays running (e.g., use `sleep infinity` for debugging).  
* Re‑apply the corrected manifest.

---

### 5. Missing ConfigMap / Secret or invalid environment variables  
**Why it happens** – The pod references a ConfigMap, Secret, or env‑var that does not exist or contains malformed data. The application fails at start‑up because required configuration is absent.  

**How to verify**  
* The pod description will contain events like “Failed to mount ConfigMap …” or “Error: secret … not found”.  
* Container logs will show configuration‑related errors (e.g., “missing DATABASE_URL”).  

**How to fix**  
* Create or update the missing ConfigMap/Secret.  
* Ensure the names in the pod spec match the actual resources.  
* Verify the data format (e.g., JSON/YAML) is what the application expects.

---

### 6. Image‑pull failure (registry auth, tag not found, network)  
**Why it happens** – The node cannot download the container image because the tag does not exist, credentials are wrong, or the registry is unreachable. The pod never starts, and the kubelet keeps retrying.  

**How to verify**  
* The pod description will show **containerStatuses → state → waiting → reason: “ImagePullBackOff”** or **“ErrImagePull”**.  
* Events will contain messages like “Failed to pull image …”.  

**How to fix**  
* Verify the image name and tag are correct.  
* Ensure any imagePullSecrets are present and contain valid credentials.  
* Check network connectivity to the registry (firewall, proxy).  
* If the image is private, create or update the secret and reference it in the pod spec.

---

### 7. Node‑level problems (node NotReady, disk pressure, kubelet crash)  
**Why it happens** – The pod is scheduled onto a node that later becomes unhealthy. The kubelet may evict the pod or repeatedly restart it.  

**How to verify**  
* `kubectl get nodes` would show the node status as **NotReady** or with a **Condition** like *DiskPressure*.  
* Events on the pod may mention “Node is not ready” or “Node was unreachable”.  

**How to fix**  
* Resolve the node issue (restart kubelet, free disk space, fix network).  
* If the node cannot be recovered quickly, delete the pod so the scheduler can place it on a healthy node.  

---

## How to verify each cause when you regain access  

1. **Describe the pod** – `kubectl describe pod s01-crashloop -n kubexplain-eval` gives you the *Events* and the *ContainerStatuses* that contain the termination reason, exit code, OOMKilled flag, probe failures, etc.  
2. **Check the logs** – `kubectl logs s01-crashloop -n kubexplain-eval --previous` (or without `--previous` for the current attempt) shows the last output before the container died.  
3. **Inspect the pod spec** – `kubectl get pod s01-crashloop -n kubexplain-eval -o yaml` lets you see the command/args, resource limits, probes, env‑vars, ConfigMap/Secret references, and imagePullSecrets.  
4. **Look at node health** – `kubectl get node` and `kubectl describe node <node>` reveal any node‑level conditions that could affect the pod.  

Once you have identified the specific reason, apply the corresponding fix (update the image, adjust resources, correct probes, create missing ConfigMaps/Secrets, etc.) and redeploy the pod. After the change, the pod should transition to **Running** and stay there.
