# s01 | mode=none | run=1 | 6.5s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What the pod is doing

* **Image** – `busybox:1.36` (a tiny Linux utility image).  
* **Command** –  

```text
sh -c "echo 'fatal: missing configuration'; exit 1"
```

* **Result** – The container prints the line `fatal: missing configuration` and then immediately exits with status `1`.  

Kubernetes treats any non‑zero exit code as a failure. Because the container finishes instantly, the kubelet restarts it, sees the failure again, and puts the pod into **CrashLoopBackOff**. The `Restart Count: 1` you see is the first restart; it will keep increasing until the pod’s command succeeds or the pod is deleted.

### Root cause

The pod’s command is deliberately exiting with an error. The message *“fatal: missing configuration”* is coming from the container itself, not from Kubernetes. There is no missing Kubernetes configuration (service account, volume, etc.) – the container’s own script is the problem.

### How to fix it

1. **Decide what the container should actually do**  
   *If the pod is only for a test or demonstration, replace the failing command with something that stays alive (e.g., `sleep infinity` or `tail -f /dev/null`).*  
   *If the pod is supposed to run a real workload, supply the required configuration or correct the command so it can start successfully.*

2. **Update the pod spec**  
   - If the pod is created directly (e.g., via `kubectl run` or a raw `Pod` manifest), edit the manifest and change the `command`/`args` field.  
   - If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, Job, etc.), edit that controller instead; the pod will be recreated with the new spec.

3. **Apply the corrected manifest** (or use `kubectl edit`/`kubectl patch` on the controller). After the change, the pod will be recreated and should reach the **Running** state without entering CrashLoopBackOff.

### Quick example (replace the failing command)

```bash
# Edit the controller that created the pod (e.g., a Deployment named s01)
kubectl edit deployment s01 -n kubexplain-eval
```

In the editor, locate the `containers:` section and change:

```yaml
command:
- sh
- -c
- echo 'fatal: missing configuration'; exit 1
```

to something like:

```yaml
command:
- sh
- -c
- sleep 3600   # or any real command your workload needs
```

Save and exit; the Deployment will roll out a new ReplicaSet, delete the crashing pod, and create a fresh pod that stays alive.

### Verify the fix

```bash
kubectl get pod s01-crashloop -n kubexplain-eval
kubectl logs s01-crashloop -n kubexplain-eval
```

You should see the pod in **Running** state and no “fatal: missing configuration” line in the logs.

---

**Bottom line:** the CrashLoopBackOff is caused by the container’s own command exiting with status 1. Replace or correct that command (or provide the missing configuration the script expects) and the pod will stop crashing.
