# s01 | mode=none | run=2 | 10.7s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod enters **CrashLoopBackOff** (ordered by probability)

1. **Application exits with a non‑zero status (crash)**
   - **Why it happens:** The container’s main process terminates quickly (e.g., unhandled exception, missing binary, bad config). Kubernetes restarts the pod, sees the failure, and puts it in `CrashLoopBackOff`.
   - **How to verify**
     ```bash
     kubectl logs s01-crashloop -n kubexplain-eval --previous
     kubectl describe pod s01-crashloop -n kubexplain-eval
     ```
     * Look for stack traces, “Error: …”, or “exit code 1” in the logs.  
     * In the `describe` output, check the `State` and `Last State` sections for `Exit Code`.
   - **How to fix**
     * Fix the code bug, missing dependency, or incorrect command line.  
     * If the container expects a config file, ensure it is mounted correctly (ConfigMap/Secret).  
     * Re‑build the image or adjust the `command`/`args` in the pod spec.

2. **Readiness/Liveness probe failure causing rapid restarts**
   - **Why it happens:** A mis‑configured probe returns failure, causing the kubelet to kill the container (liveness) or mark it unready (readiness). If the liveness probe fails repeatedly, the pod restarts and ends up in `CrashLoopBackOff`.
   - **How to verify**
     ```bash
     kubectl describe pod s01-crashloop -n kubexplain-eval | grep -A5 "Liveness"
     kubectl get pod s01-crashloop -n kubexplain-eval -o yaml | grep -A5 "livenessProbe"
     ```
     * Check the `Last State` events for messages like “Liveness probe failed”.
   - **How to fix**
     * Adjust `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, or the probe command/HTTP path so the container has enough time to start.  
     * Temporarily disable the probe to confirm it’s the cause, then re‑enable with corrected values.

3. **Missing or incorrect environment variables / ConfigMap / Secret**
   - **Why it happens:** The application expects certain env vars or files that are not provided, leading to immediate termination.
   - **How to verify**
     ```bash
     kubectl exec -it s01-crashloop -n kubexplain-eval -- env | grep <EXPECTED_VAR>
     kubectl describe pod s01-crashloop -n kubexplain-eval | grep -A3 "ConfigMap"
     ```
     * Compare the required variables (from the app docs) with what the pod actually receives.
   - **How to fix**
     * Add the missing env vars, ConfigMap keys, or Secret data to the pod spec.  
     * Ensure the ConfigMap/Secret exists in the same namespace and is referenced correctly.

4. **Resource limits / OOMKilled**
   - **Why it happens:** The container exceeds its memory limit, the kernel kills it (OOMKilled). The pod restarts, and the cycle repeats.
   - **How to verify**
     ```bash
     kubectl describe pod s01-crashloop -n kubexplain-eval | grep -A3 "OOMKilled"
     ```
     * In the `State` section you’ll see `Reason: OOMKilled`.
   - **How to fix**
     * Increase the `resources.limits.memory` (and possibly `requests.memory`).  
     * Optimize the application’s memory usage or enable swap (if appropriate for your cluster).

5. **Image pull or authentication failure (rare once the pod is running)**
   - **Why it happens:** If the image cannot be pulled or the registry credentials are wrong, the pod never starts and may show `CrashLoopBackOff` after a few retries.
   - **How to verify**
     ```bash
     kubectl describe pod s01-crashloop -n kubexplain-eval | grep -i "pull"
     ```
     * Look for events like “Failed to pull image …” or “ErrImagePull”.
   - **How to fix**
     * Ensure the image name/tag is correct.  
     * Verify the `imagePullSecrets` exist and have proper credentials.  
     * If using a private registry, confirm network access from the node.

6. **Permission / SecurityContext issues**
   - **Why it happens:** The container tries to run a command that requires higher privileges (e.g., binding to a privileged port <1024) and exits.
   - **How to verify**
     ```bash
     kubectl logs s01-crashloop -n kubexplain-eval --previous | grep -i "permission"
     ```
     * Look for “permission denied” or “operation not permitted”.
   - **How to fix**
     * Adjust the `securityContext` (runAsUser, capabilities) or run the process on a non‑privileged port.  
     * Add necessary `capAdd` or `runAsUser` settings.

7. **Dependency service unavailable**
   - **Why it happens:** The pod starts, immediately tries to connect to another service (DB, API) that is not reachable, and exits.
   - **How to verify**
     ```bash
     kubectl logs s01-crashloop -n kubexplain-eval --previous | grep -i "connection refused"
     ```
     * Check for network‑related errors.
   - **How to fix**
     * Ensure the dependent service is up and reachable (correct Service name/DNS, correct port).  
     * Add appropriate `initContainers` or retry logic in the app.

---

### General verification workflow (when you regain cluster access)

1. **Inspect recent logs** – `kubectl logs <pod> -n <ns> --previous` to see why the last container died.  
2. **Check pod events** – `kubectl describe pod <pod> -n <ns>`; focus on the `Events` section for clues (OOMKilled, ProbeFailed, ImagePullBackOff, etc.).  
3. **Review the pod spec** – `kubectl get pod <pod> -n <ns> -o yaml` to confirm probes, resources, env vars, and security settings.  
4. **Iteratively adjust** the offending part (code, config, resources, probes) and redeploy (`kubectl apply -f <manifest>` or `kubectl rollout restart deployment/<name>`).  

By following the steps above for each likely cause, you should be able to pinpoint the root reason for the `CrashLoopBackOff` and apply the appropriate fix. Good luck!
