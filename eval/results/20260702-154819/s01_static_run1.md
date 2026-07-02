# s01 | mode=static | run=1 | 6.6s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

### What is happening?

* The pod **s01‑crashloop** is scheduled and the container image (`busybox:1.36`) is pulled successfully.  
* The container is started with the command you defined:

```text
sh -c "echo 'fatal: missing configuration'; exit 1"
```

* The command prints the line **`fatal: missing configuration`** and then exits with status **1**.  
* Because the process terminates with a non‑zero exit code, the kubelet marks the container as **Failed** and immediately tries to start it again. After a few rapid restarts the kubelet backs off, resulting in the **CrashLoopBackOff** state you see.

So the *root cause* is simply that the container’s entry‑point intentionally exits with an error. Kubernetes is doing exactly what it should: it restarts a container that ends in failure.

### How to fix it

1. **Decide what the container is supposed to do**  
   *If the container is meant to run a long‑lived service*, replace the current command with the real binary or script that starts that service.  
   *If the container is only a test that should finish successfully*, remove the `exit 1` (or change it to `exit 0`) so the process ends with a zero exit code.

2. **Update the pod (or the Deployment/Job that creates it)**  

   - **For a one‑off pod** you can delete it and recreate it with the corrected command:

     ```bash
     kubectl delete pod s01-crashloop -n kubexplain-eval
     # then apply a new manifest that has the proper command
     ```

   - **If the pod is managed by a Deployment, ReplicaSet, or Job**, edit the controller’s spec:

     ```bash
     kubectl edit deployment <name> -n kubexplain-eval
     ```

     Locate the `containers[].command` (or `args`) field and change it to the desired command, e.g.:

     ```yaml
     command: ["sh", "-c", "while true; do echo hello; sleep 30; done"]
     ```

     Save the edit; the controller will roll out a new replica with the corrected command.

3. **Verify the change**

   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval -w
   ```

   The pod should transition to **Running** and stay there, with `Ready: True` and `Restart Count` staying at 0 (or only the initial start).

### Quick checklist

- [ ] **Command** – does it end with a non‑zero exit? Change it to a long‑running process or a successful exit (`exit 0`).  
- [ ] **Image** – `busybox` is fine for simple scripts; just ensure the script you run is correct.  
- [ ] **Controller** – if the pod is part of a higher‑level controller, edit the controller spec, not just the pod, so the change persists.  
- [ ] **Observe** – after applying the fix, watch the pod events (`kubectl describe pod …`) to confirm the `CrashLoopBackOff` disappears.

Once the container no longer exits with an error, the pod will stay healthy and the CrashLoopBackOff will be resolved.
