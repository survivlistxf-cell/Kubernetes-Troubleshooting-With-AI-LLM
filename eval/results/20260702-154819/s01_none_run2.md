# s01 | mode=none | run=2 | 5.7s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Root cause  

The pod is in **CrashLoopBackOff** because the only container (`app`) runs the command:

```text
sh -c "echo 'fatal: missing configuration'; exit 1"
```

The command deliberately prints *“fatal: missing configuration”* and then exits with status `1`.  
Kubernetes treats any non‑zero exit code as a failure, so the container is terminated, the pod is restarted, and after a few attempts the kubelet applies a back‑off delay, resulting in the `CrashLoopBackOff` state you see.

There is no missing image, network, or node problem – the container is doing exactly what its spec tells it to do.

## How to fix it  

### 1. Decide what the container should actually do  

* If the container is supposed to run a real workload, replace the placeholder command with the correct entrypoint/command and provide any required configuration (e.g., via a ConfigMap, Secret, environment variables, or mounted files).  
* If the container is only for testing and you want it to stay alive, change the command to something that does not exit, such as `sleep infinity` or `tail -f /dev/null`.  

### 2. Update the pod (or the Deployment/Job that creates it)

#### a. Quick edit of the existing pod (for a one‑off test)

```bash
kubectl edit pod s01-crashloop -n kubexplain-eval
```

* In the editor, locate the `containers:` → `app:` → `command:` section and replace it, e.g.:

```yaml
command:
- sh
- -c
- "sleep infinity"
```

Save and exit. The pod will be terminated and recreated with the new command; it should stay in `Running` state.

#### b. Permanent fix via the manifest (recommended)

If the pod is created by a higher‑level controller (Deployment, ReplicaSet, etc.), edit that resource instead:

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

* Update the `spec.template.spec.containers[0].command` (or `args`) to the desired command.  
* If the container needs configuration, add a `configMap` or `secret` volume and mount it, or set appropriate `env:` entries.

After saving, the controller will roll out a new ReplicaSet and the pods will start with the corrected command.

### 3. Verify the fix  

```bash
kubectl get pod s01-crashloop -n kubexplain-eval
kubectl describe pod s01-crashloop -n kubexplain-eval
kubectl logs s01-crashloop -n kubexplain-eval
```

The pod should show `STATUS` **Running**, `READY` **1/1**, and the logs should reflect the new command’s output (or be empty if the container just sleeps).

---

### Summary  

* **Root cause:** The container’s command intentionally exits with status 1 (`exit 1`), causing the CrashLoopBackOff.  
* **Fix:** Replace the placeholder command with the real workload command or a non‑terminating command, and apply the change via `kubectl edit` (for a quick test) or by updating the owning controller’s manifest for a permanent solution.
