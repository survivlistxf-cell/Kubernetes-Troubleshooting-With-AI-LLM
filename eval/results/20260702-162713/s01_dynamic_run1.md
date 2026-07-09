# s01 | mode=dynamic | run=1 | 12.7s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

**Most likely reasons a pod ends up in a CrashLoopBackOff (restart loop)**  
*(ordered from the cause that most often produces this symptom to the least common)*  

1. **Application process exits with a non‑zero status**  
   *Why it happens* – The container’s entrypoint or command returns an error (e.g., a script that immediately fails, a missing binary, or a mis‑typed command).  
   *How to verify* – Look at the container’s termination reason and exit code in the pod’s status. The reason will be **Error** and the exit code will be non‑zero.  
   *How to fix* –  
   * Ensure the command/args in the pod spec are correct.  
   * Verify the binary exists in the image and is executable.  
   * If the process needs arguments or environment variables, add them or correct their values.  

2. **Image‑pull failures**  
   *Why it happens* – The image name is misspelled, the tag does not exist, the registry requires authentication, or network connectivity to the registry is broken.  
   *How to verify* – The pod status will show a **Waiting** state with a reason such as *ImagePullBackOff* or *ErrImagePull*. Event messages will mention “failed to pull image”.  
   *How to fix* –  
   * Confirm the image name and tag are correct and that the image is present in the registry.  
   * If a private registry is used, make sure the appropriate imagePullSecret is attached and contains valid credentials.  
   * Test pulling the image manually from a node (or from a local Docker client) to rule out network or auth problems.  

3. **Liveness or readiness probe failures**  
   *Why it happens* – A probe is mis‑configured (wrong port, path, or command) or the application needs more time to start than the probe allows. When the liveness probe fails, the kubelet kills the container, causing a restart loop.  
   *How to verify* – The pod status will list **Running** containers but with a *Last State* of **Terminated** and a reason of *ProbeFailed*. Event logs will contain messages like “Liveness probe failed”.  
   *How to fix* –  
   * Adjust the probe parameters (initialDelaySeconds, periodSeconds, timeoutSeconds, failureThreshold) so the application has enough time to become healthy.  
   * Verify the probe endpoint (HTTP path, TCP port, or exec command) actually returns success.  
   * If the probe is not needed, remove or disable it.  

4. **Out‑of‑memory (OOM) kills**  
   *Why it happens* – The container exceeds its memory limit; the kernel OOM killer terminates it, and the pod restarts.  
   *How to verify* – The termination reason will be **OOMKilled** and the event log will mention “container was killed due to memory usage”.  
   *How to fix* –  
   * Increase the memory request/limit for the container, or lower the application’s memory consumption.  
   * Add a swap‑like buffer (e.g., use a larger node or enable memory overcommit if appropriate).  

5. **Missing or invalid ConfigMap / Secret data**  
   *Why it happens* – The pod mounts a ConfigMap or Secret that does not exist, is empty, or contains malformed data that the application cannot parse, causing it to exit.  
   *How to verify* – The pod status will show a **Waiting** state with a reason such as *CreateContainerConfigError* or *CrashLoopBackOff* accompanied by an event like “configmap … not found”.  
   *How to fix* –  
   * Ensure the referenced ConfigMap/Secret exists in the same namespace.  
   * Verify the keys and values are correctly formatted for the application.  
   * Update the pod spec to reference the correct name.  

6. **Permission or security context issues**  
   *Why it happens* – The container runs as a non‑root user that lacks required filesystem or network permissions, or a security policy (PodSecurityPolicy, Seccomp, AppArmor) blocks needed actions, leading to immediate termination.  
   *How to verify* – The termination reason may be **Error** with a message indicating “permission denied” or “operation not permitted”. Logs from the container will show the same.  
   *How to fix* –  
   * Adjust the securityContext (runAsUser, runAsGroup, privileged flag) to grant needed rights.  
   * Update or relax the applicable PodSecurityPolicy or other admission controls.  

7. **Node‑level problems (disk pressure, network partition, kubelet crash)**  
   *Why it happens* – The node where the pod is scheduled is unhealthy, causing the kubelet to repeatedly kill containers.  
   *How to verify* – The pod’s events will include messages about the node being *NotReady* or *DiskPressure*. The node’s status will show conditions other than Ready.  
   *How to fix* –  
   * Drain the affected node and let the pod be rescheduled onto a healthy node.  
   * Resolve the underlying node issue (free disk space, fix network, restart kubelet).  

---

### How to verify each hypothesis without direct cluster access  

1. **Ask a teammate or use a remote console** to run a “describe pod”‑type query and retrieve the pod’s status and recent events.  
2. **Check the container logs** (they are stored in the API server) for error messages, stack traces, or OOM reports.  
3. **Inspect the pod’s YAML/JSON** (the full object) to see the command, args, image name, probes, resource limits, and references to ConfigMaps/Secrets.  
4. **Look at the node conditions** for the node that the pod is scheduled on (if known) to rule out node‑level failures.  
5. **Cross‑reference the image registry** manually (e.g., `docker pull` from a workstation) to confirm the image tag exists and is pullable.  

---

### General remediation workflow  

1. **Gather the pod’s status and events** – this tells you which of the above reasons is being reported.  
2. **If the reason is an application error**, fix the code, command, or environment variables and redeploy.  
3. **If the reason is an image or secret problem**, correct the spec (image name, pull secret, ConfigMap/Secret name) and apply the updated manifest.  
4. **If probes are the culprit**, tune the probe settings or remove them.  
5. **If resources are insufficient**, adjust the request/limit values or add capacity to the cluster.  
6. **If node health is the issue**, move the pod to a healthy node (drain, delete, or let the scheduler reschedule).  

By following these verification steps in order of likelihood, you can pinpoint the root cause of the `CrashLoopBackOff` for `s01-crashloop` and apply the appropriate fix even when you cannot directly access the cluster at the moment.
