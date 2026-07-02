# s01 | mode=none | run=3 | 7.2s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it?

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## What is happening?

- The pod **s01‑crashloop** is using the image `busybox:1.36`.
- Its container `app` is started with the command  

  ```text
  sh -c "echo 'fatal: missing configuration'; exit 1"
  ```
- The command prints **`fatal: missing configuration`** and then exits with status **1**.
- An exit code ≠ 0 tells the kubelet that the container failed, so it is terminated.
- The kubelet immediately tries to start it again. After a few rapid failures it backs‑off and reports **`CrashLoopBackOff`**.

In short, the container is deliberately exiting with an error; there is no hidden runtime problem, networking issue, or missing image.

## How to fix it

### 1. Decide what the container should actually do
- **If the pod is only a test/example**, replace the failing command with something that stays alive, e.g.:

  ```yaml
  command: ["sh", "-c", "sleep infinity"]
  ```

- **If the pod is supposed to run a real workload**, provide the missing configuration (file, env var, command‑line flag, etc.) that the script expects.

### 2. Update the pod definition

#### Option A – Quick edit (for a single‑run pod)

```bash
kubectl edit pod s01-crashloop -n kubexplain-eval
```

- In the editor, locate the `containers:` → `app:` → `command:` block.
- Change it to the desired command (e.g., `["sh","-c","sleep infinity"]`) **or** add the required configuration (mount a ConfigMap, set env vars, etc.).
- Save and exit. The pod will be terminated and a new pod will be created with the corrected spec.

#### Option B – Apply a new manifest (recommended for reproducibility)

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
    # Replace the failing command with the proper one
    command: ["sh", "-c", "sleep infinity"]
    # If you need configuration, mount it here, e.g.:
    # env:
    # - name: CONFIG_PATH
    #   value: "/etc/myapp/config.yaml"
    # volumeMounts:
    # - name: config
    #   mountPath: /etc/myapp
  # volumes:
  # - name: config
  #   configMap:
  #     name: myapp-config
```

Apply it:

```bash
kubectl apply -f s01-crashloop.yaml
```

If a pod with the same name already exists, delete it first (or change the name):

```bash
kubectl delete pod s01-crashloop -n kubexplain-eval
kubectl apply -f s01-crashloop.yaml
```

### 3. Verify the fix

```bash
kubectl get pod s01-crashloop -n kubexplain-eval
kubectl logs s01-crashloop -n kubexplain-eval
```

The pod should reach **`Running`** and stay there, with no `CrashLoopBackOff` event.

## Summary

- **Root cause:** The container’s command intentionally exits with status 1 after printing “fatal: missing configuration”.
- **Fix:** Change the command to a valid one (or supply the missing configuration) and redeploy the pod. Use `kubectl edit` for a quick change or apply a corrected manifest for a permanent solution. Once the container exits with code 0 (or stays alive), the `CrashLoopBackOff` will disappear.
