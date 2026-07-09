# s01 | mode=dynamic | run=2 | 6.9s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## What’s happening

- The container **app** runs the command  

  ```sh
  sh -c "echo 'fatal: missing configuration'; exit 1"
  ```

- `echo` prints *fatal: missing configuration* and the subsequent `exit 1` makes the process terminate with a non‑zero exit code.  
- Kubernetes treats any non‑zero exit as a failure, so the kubelet restarts the container. After the first failure it applies the **CrashLoopBackOff** back‑off algorithm, which is why you see the “Back‑off restarting failed container” event and the pod stays in `CrashLoopBackOff` state.

**Root cause:** the container’s command intentionally exits with status 1, so the pod can never reach a healthy, running state.

## How to fix it

### 1. Decide what the container should actually do
- If the pod is meant to run a long‑running service, replace the failing command with the proper entrypoint (e.g. the binary that serves your application).
- If the pod is only for a one‑time script, let the script exit with `0` when it succeeds, or remove the explicit `exit 1`.

### 2. Update the pod (or its controller) definition
- **If you created the pod directly:** edit the manifest and change the `command` (or `args`) field, then delete & recreate the pod.

  ```bash
  kubectl delete pod s01-crashloop -n kubexplain-eval
  kubectl apply -f <updated‑manifest>.yaml
  ```

- **If the pod is managed by a Deployment/ReplicaSet/Job:** edit the controller so the new command is rolled out.

  ```bash
  kubectl edit deployment <name> -n kubexplain-eval
  ```

  (or `kubectl set image …` / `kubectl patch …` as appropriate).

### 3. Verify the fix
- Watch the pod status until it becomes `Running` and `Ready`:

  ```bash
  kubectl get pod s01-crashloop -n kubexplain-eval -w
  ```

- Check the logs again; the “fatal: missing configuration” line should disappear.

## Optional: Prevent similar issues

- **Add a readiness probe** that only succeeds when the container is truly ready. This prevents the pod from being marked Ready while the command is still failing.
- **Use `restartPolicy: Never`** for one‑shot jobs (e.g., `Job` objects) so that a failure does not trigger a back‑off loop.
- **Validate container images locally** before deploying to ensure the entrypoint behaves as expected.

Once the command no longer exits with a non‑zero status, the pod will stay up and the CrashLoopBackOff will disappear.
