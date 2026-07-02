# s01 | mode=static | run=2 | 7.5s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What the `describe` and `logs` show

| Item | Value |
|------|-------|
| **Container image** | `busybox:1.36` |
| **Command** | `sh -c "echo 'fatal: missing configuration'; exit 1"` |
| **Last state** | `Terminated` – `Exit Code: 1` |
| **Current state** | `Waiting` – `Reason: CrashLoopBackOff` |
| **Pod status** | `Ready: False` (restart count = 1) |
| **Pod logs** | `fatal: missing configuration` |

The container starts, prints the message *“fatal: missing configuration”* and then **exits with status 1**. Because the process ends with a non‑zero exit code, the kubelet marks the container as failed. It immediately tries to restart it, fails again, and after a few attempts the pod enters the **CrashLoopBackOff** back‑off state.

### Root cause

The pod’s command is deliberately terminating with an error:

```sh
sh -c "echo 'fatal: missing configuration'; exit 1"
```

`exit 1` tells the shell to exit with a failure status, which Kubernetes interprets as a container crash. There is no missing configuration in the cluster – the container itself is programmed to fail.

### How to fix it

1. **Decide what the pod is supposed to do**  
   * If it should run a long‑lived process (e.g., a web server, a worker, etc.), replace the current command with the proper executable.  
   * If the pod is only a test that should succeed once, remove the `exit 1` (or change it to `exit 0`).  

2. **Update the pod spec**  
   - For a one‑off pod you can edit it directly:

   ```bash
   kubectl edit pod s01-crashloop -n kubexplain-eval
   ```

   Change the `command` (or `args`) section, for example:

   ```yaml
   containers:
   - name: app
     image: busybox:1.36
     command: ["sh", "-c", "echo 'all good'; sleep 3600"]
   ```

   - If the pod is managed by a Deployment, StatefulSet, or Job, edit the controller’s manifest and re‑apply it:

   ```bash
   kubectl edit deployment <deployment-name> -n kubexplain-eval
   ```

   or

   ```bash
   kubectl apply -f <updated‑manifest>.yaml
   ```

3. **Verify the change**  

   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval -w
   ```

   Wait until the pod reaches `Running` and `Ready: True`. Then check the logs to confirm the expected output:

   ```bash
   kubectl logs s01-crashloop -n kubexplain-eval
   ```

### Quick checklist

- [ ] **Command** no longer ends with `exit 1` (or any non‑zero exit).  
- [ ] Container image is correct for the workload you need.  
- [ ] If the pod is part of a higher‑level controller, update that controller, not just the pod (otherwise the controller will recreate the broken pod).  
- [ ] After applying the change, confirm the pod’s `Ready` condition becomes `True` and the restart count stops increasing.

Once the container starts a process that stays alive (or exits with code 0), the CrashLoopBackOff will disappear and the pod will become healthy.
