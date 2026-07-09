# s01 | mode=dynamic | run=3 | 8.2s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What the `describe` and `logs` show

| Field | Value | Meaning |
|-------|-------|---------|
| **Command** | `sh -c "echo 'fatal: missing configuration'; exit 1"` | The container runs a shell that prints a message **and then exits with status 1**. |
| **State → Reason** | `CrashLoopBackOff` | The container has terminated with a non‑zero exit code, the kubelet has tried to restart it, and after a few rapid failures it backs‑off to avoid a tight restart loop. |
| **Last State → Exit Code** | `1` | The process reported an error. |
| **Logs** | `fatal: missing configuration` | The only output produced before the container exited. |
| **Restart Count** | `1` (will increase quickly) | Each failure increments this counter. |

**Root cause:** The container’s entry‑point deliberately exits with an error (`exit 1`). Because the process ends immediately with a non‑zero status, the pod never reaches a *Running*‑ready state and the kubelet puts it into `CrashLoopBackOff`.

### How to fix it

1. **Decide what the pod is supposed to do**  
   - If it should stay alive (e.g., run a server, a script that loops, or just keep the pod for debugging), change the command so the process does **not** exit with an error.  
   - If the exit is intentional (the pod is meant to fail when configuration is missing), then the failure is expected and you should not treat it as a problem; instead, provide the required configuration.

2. **Update the pod (or its controller) with a correct command**  

   *If the pod is created directly (no Deployment/Job):*  

   ```bash
   kubectl delete pod s01-crashloop -n kubexplain-eval
   kubectl run s01-crashloop \
     --image=busybox:1.36 \
     --restart=Never \
     --command -- sh -c "while true; do echo 'running'; sleep 30; done" \
     -n kubexplain-eval
   ```

   *If the pod is managed by a Deployment/ReplicaSet/Job:* edit the manifest and apply the change:

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   In the `spec.template.spec.containers[0].command` (or `args`) replace the failing line with something that stays alive, e.g.:

   ```yaml
   command: ["sh", "-c", "while true; do echo 'healthy'; sleep 60; done"]
   ```

   Then save; the Deployment will roll out a new ReplicaSet and the pod will start without crashing.

3. **Provide the missing configuration (if the exit is meant to guard against it)**  
   - Add a ConfigMap, Secret, or environment variable that the script expects.  
   - Example (adding a ConfigMap named `app-config` with a key `config.yaml`):

     ```bash
     kubectl create configmap app-config --from-file=config.yaml=./config.yaml -n kubexplain-eval
     ```

     Then mount it into the pod and adjust the command to read the file instead of exiting.

4. **Verify the fix**  

   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval -w
   ```

   The pod should reach `STATUS   Running` and `READY   1/1`.  

   ```bash
   kubectl logs s01-crashloop -n kubexplain-eval
   ```

   You should see the new output (e.g., “running” or “healthy”) and no `CrashLoopBackOff` events.

### Quick checklist

- [ ] **Identify why the container exits** – in this case the `exit 1` in the command.  
- [ ] **Change the command** to a long‑running process **or** supply the missing configuration the script expects.  
- [ ] **Apply the updated manifest** (edit the Deployment/Pod spec).  
- [ ] **Watch the pod** until it becomes `Ready`.  
- [ ] **Confirm logs** show the expected output and no crash events.

Once the container no longer terminates with a non‑zero exit code, the `CrashLoopBackOff` will disappear and the pod will stay healthy.
