# s01 | mode=static | run=3 | 13.3s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff**  

| Rank | Likely cause | Why it shows up as a restart loop | How you would verify it (when you can run `kubectl`) | Typical fix |
|------|--------------|-----------------------------------|------------------------------------------------------|-------------|
| **1** | **Application process exits with a non‑zero code** (bug, missing env var, bad config, etc.) | The container starts, runs the entry‑point, immediately exits → kubelet restarts it, repeats → CrashLoopBackOff. | • `kubectl describe pod s01-crashloop -n kubexplain-eval` – look at the **State → Last State → Reason** and **Exit Code**.<br>• `kubectl logs s01-crashloop -n kubexplain-eval --previous` – shows the stdout/stderr of the failed run. | Fix the application code or its configuration (add missing env vars, correct command‑line flags, update the image with a working version). |
| **2** | **Out‑of‑Memory (OOMKilled)** – container exceeds its memory limit | The kernel kills the process, kubelet records **Reason: OOMKilled** and restarts the pod. | • In the pod description, under **State → Last State**, the **Reason** will be **OOMKilled**.<br>• `kubectl logs … --previous` may contain a “Killed” line.<br>• Check the **resources.limits.memory** value in the pod spec. | Raise the memory limit (or request) for the container, or reduce the memory consumption of the app. |
| **3** | **Liveness‑probe failure** – probe returns non‑success repeatedly | A failing liveness probe tells the kubelet the container is unhealthy, so it kills and restarts it, producing a loop. | • In `kubectl describe pod …` look for events like **Liveness probe failed**.<br>• The **Readiness** and **Liveness** sections of the pod spec show the probe configuration; compare the expected response with what the container actually returns. | Adjust the probe (correct path/port, increase `initialDelaySeconds` or `failureThreshold`), or fix the service inside the container so the probe succeeds. |
| **4** | **Init‑container failure** (or repeated restarts of an init container) | If an init container keeps failing, the pod never reaches the running phase; kubelet keeps trying, showing a restart loop for the whole pod. | • `kubectl describe pod …` lists each init container and its **State**.<br>• Look for **Reason** such as **CrashLoopBackOff** or **Error** on the init container. | Fix the init container’s image/command, provide required resources, or remove it if it is no longer needed. |
| **5** | **Incorrect command / args** – the container starts with a wrong entrypoint that exits immediately | Similar to #1, but the root cause is a spec mistake rather than a bug in the app code. | • In the pod spec (`kubectl get pod … -o yaml`) check the **command** and **args** fields.<br>• The pod description will show **Reason: Error** with a message like “container failed to start”. | Correct the `command`/`args` fields, or remove them to let the image’s default entrypoint run. |
| **6** | **Image‑pull problems** (rarely shown as CrashLoopBackOff, more often ImagePullBackOff) | If the image can be pulled but the container crashes right after start (e.g., corrupted image, missing binary), the pod will loop. | • `kubectl describe pod …` will show **ImagePullBackOff** first; if that resolves and the container then crashes, you’ll see **CrashLoopBackOff**.<br>• Check the image tag and digest, and try pulling it locally. | Re‑build the image, ensure the entrypoint binary exists, or use a known good tag. |
| **7** | **Dependency failure** – the app cannot reach a required service (DB, API) and exits | The container’s own logic decides to terminate when a required service is unavailable. | • Application logs (`kubectl logs … --previous`) will contain connection‑refused or timeout messages.<br>• Verify that the dependent services are healthy. | Deploy the missing dependency, adjust service endpoints, or add retry logic / back‑off in the app. |
| **8** | **Security / admission‑webhook rejection** – pod is created but a webhook mutates immutable fields on restart, causing a loop | The webhook may change something that the kubelet cannot apply on a running pod, forcing a delete‑and‑recreate cycle. | • Look for events mentioning a **ValidatingWebhookConfiguration** or **MutatingWebhookConfiguration**.<br>• Check the pod’s `metadata.annotations` for webhook‑added data. | Update or disable the offending webhook, or make it idempotent on updates. |

---

## How to verify each cause (when you regain access)

1. **Describe the pod** – shows status, last state, reasons, and recent events.  
2. **Fetch the previous container logs** – reveals the exact error output that caused the termination.  
3. **Inspect the pod’s YAML** – lets you see resource limits, probes, command/args, and init‑container definitions.  
4. **Check node‑level metrics** (e.g., `kubectl top pod …`) if you suspect OOM or CPU throttling.  
5. **Validate dependent services** – ping the service endpoints from a temporary debug pod or from the container’s logs.  

---

## General remediation checklist

1. **Fix the application** – rebuild the image with the bug corrected, or adjust configuration (env vars, config maps, secrets).  
2. **Adjust resources** – increase `memory` limits/requests if OOMKilled; optionally add `cpu` limits if the container is being throttled.  
3. **Correct probes** – ensure the liveness probe matches a healthy endpoint, and give the container enough time to start (`initialDelaySeconds`, `periodSeconds`).  
4. **Repair init containers** – make sure they succeed before the main container starts.  
5. **Validate command/args** – remove accidental overrides that bypass the intended entrypoint.  
6. **Re‑push a clean image** – if the image itself is corrupted or missing binaries.  
7. **Ensure dependencies are up** – bring up databases, external APIs, or configure proper service DNS names.  
8. **Review webhooks** – disable or fix any mutating/validating webhook that interferes with pod restarts.  

By walking through the verification steps in the order above, you’ll quickly pinpoint which of these common reasons is responsible for the `s01-crashloop` pod’s repeated restarts and apply the appropriate fix.
