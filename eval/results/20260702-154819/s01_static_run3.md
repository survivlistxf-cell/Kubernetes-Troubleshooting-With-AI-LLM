# s01 | mode=static | run=3 | 8.1s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What is happening?

| Observation | Meaning |
|-------------|---------|
| **Container command**: `sh -c "echo 'fatal: missing configuration'; exit 1"` | The container deliberately prints a message and then exits with status `1`. |
| **State**: `Waiting → CrashLoopBackOff` | Because the process exits with a non‑zero code, the kubelet restarts it. After a few rapid failures it backs‑off, showing the *CrashLoopBackOff* status. |
| **Pod logs**: `fatal: missing configuration` | The log is exactly the output of the `echo` command, confirming the container is failing for the expected reason. |
| **Restart count**: `1` (and will increase) | Each restart increments the counter; the pod will keep looping until the command succeeds or the pod is deleted. |

**Root cause:** The container’s entry‑point command is written to fail (`exit 1`). Kubernetes treats any non‑zero exit as an error, so the pod never reaches a *Ready* state and ends up in a CrashLoopBackOff.

---

### How to fix it

1. **Decide what the container should actually do**  
   * If the pod is only a test/example, replace the failing command with a harmless one (e.g. `sleep infinity` or `tail -f /dev/null`).  
   * If the pod is supposed to run a real application, provide the required configuration so the command can succeed (remove the `exit 1`, add config files, environment variables, etc.).

2. **Update the pod spec**  
   *If the pod is created directly (no controller):*  

   ```bash
   kubectl edit pod s01-crashloop -n kubexplain-eval
   ```

   Change the `command:` (or `args:`) section, for example:

   ```yaml
   containers:
   - name: app
     image: busybox:1.36
     command: ["sh", "-c", "echo 'ready'; sleep 3600"]
   ```

   *If the pod is managed by a Deployment, ReplicaSet, Job, etc.:*  

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   (or `kubectl edit <controller>`). Update the same `command:` field and save. The controller will roll out a new replica with the corrected command.

3. **Apply a new manifest (alternative to `edit`)**  
   Create a corrected YAML file, e.g. `s01-crashloop.yaml`:

   ```yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: s01-crashloop
     namespace: kubexplain-eval
     labels:
       eval-scenario: s01
   spec:
     containers:
     - name: app
       image: busybox:1.36
       command: ["sh", "-c", "echo 'ready'; sleep 3600"]
   ```

   Then apply it:

   ```bash
   kubectl replace --force -f s01-crashloop.yaml
   ```

   (`--force` deletes the existing pod and creates a new one.)

4. **Verify the fix**  

   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval
   kubectl logs s01-crashloop -n kubexplain-eval
   ```

   The pod should transition to **Running → Ready** and the logs should show the new message (`ready`) without an exit code.

---

### Quick checklist

- [ ] Identify whether the pod is standalone or managed by a controller.  
- [ ] Edit the appropriate manifest to remove the intentional `exit 1` (or supply the missing configuration).  
- [ ] Apply the change (via `kubectl edit`, `kubectl apply`, or `kubectl replace`).  
- [ ] Confirm the pod reaches the **Ready** state and no longer shows *CrashLoopBackOff*.  

Once the command exits with status `0`, the CrashLoopBackOff will disappear and the pod will stay healthy.
