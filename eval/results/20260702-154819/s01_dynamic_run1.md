# s01 | mode=dynamic | run=1 | 7.0s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What the pod’s status tells us  

| Field | Value | Meaning |
|-------|-------|---------|
| **Command** | `sh -c "echo 'fatal: missing configuration'; exit 1"` | The container deliberately prints a message and then exits with status 1. |
| **State** | `Waiting` – Reason **CrashLoopBackOff** | The kubelet tried to start the container, it exited quickly with a non‑zero code, so the pod is being restarted repeatedly. |
| **Last State – Reason** | **Error**, **Exit Code 1** | The container terminated with an error. |
| **Logs** | `fatal: missing configuration` | The only output comes from the `echo` command. |

**Root cause** – The container’s entry‑point is a command that always fails (`exit 1`). Because the process exits with a non‑zero status, Kubernetes treats the container as crashed and repeatedly restarts it, ending up in the `CrashLoopBackOff` back‑off state.

### How to fix it  

1. **Determine the intended behaviour**  
   * If the pod is supposed to run a real workload, replace the placeholder command with the correct one (or supply the missing configuration the script expects).  
   * If the pod is only meant to run once and exit successfully, change the command so it exits with status 0 (or set `restartPolicy: Never`).

2. **Update the pod spec**  
   * **Edit the existing object** (if it’s a plain Pod):  

   ```bash
   kubectl edit pod s01-crashloop -n kubexplain-eval
   ```

   * Change the `command:` (or `args:`) field to the proper command, e.g.:

   ```yaml
   containers:
   - name: app
     image: busybox:1.36
     command: ["sh", "-c", "echo 'ready'; sleep 3600"]
   ```

   * Or, if you want the pod to finish without being restarted, add:

   ```yaml
   restartPolicy: Never
   ```

3. **Apply a new manifest** (recommended for reproducibility)  

   ```yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: s01-crashloop
     namespace: kubexplain-eval
   spec:
     restartPolicy: OnFailure   # or Never, depending on use‑case
     containers:
     - name: app
       image: busybox:1.36
       command: ["sh", "-c", "your‑real‑command‑here"]
   ```

   Then apply it:

   ```bash
   kubectl apply -f corrected-pod.yaml
   ```

4. **Verify**  

   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval
   kubectl logs s01-crashloop -n kubexplain-eval
   ```

   The pod should transition to **Running** (or **Completed** if it’s a one‑shot job) and the `CrashLoopBackOff` condition should disappear.

### Quick checklist  

- [ ] Replace the placeholder `echo …; exit 1` with the actual command or a harmless long‑running command.  
- [ ] If the pod is meant to run only once, set `restartPolicy: Never` (or use a `Job` instead of a plain Pod).  
- [ ] Re‑apply the corrected manifest or edit the live object.  
- [ ] Confirm the pod’s status and logs are healthy.

Once the container exits with status 0 (or stays alive), the `CrashLoopBackOff` will be resolved.
