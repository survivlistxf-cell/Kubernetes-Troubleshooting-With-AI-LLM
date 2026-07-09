# s13 | mode=none | run=3 | 14.8s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons a Job ends `Failed` and no new Pods are created  

| # | Likely cause (probability high → low) | Why it stops retries | How to verify (when you can run `kubectl`) | Typical fix |
|---|----------------------------------------|----------------------|--------------------------------------------|-------------|
| 1 | **Back‑off limit reached** (default `backoffLimit: 6`) | Each pod that exits with a non‑zero code is counted as a failure. When the count ≥ `backoffLimit` the Job controller marks the Job **Failed** and stops creating new Pods. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep backoffLimit\nkubectl describe job s13-failing-job -n kubexplain-eval | grep \"BackOffLimit\"\n```<br>Check the `status.failed` field – it should be equal to the `backoffLimit`. | *Increase* `backoffLimit` (or remove it) in the Job spec, or fix the underlying pod failure so that fewer retries are needed. |
| 2 | **`activeDeadlineSeconds` exceeded** | The Job has a hard wall‑clock limit. When the total wall‑clock time of the Job reaches this value the controller marks the Job **Failed** regardless of pod state, and no further Pods are started. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep activeDeadlineSeconds\nkubectl describe job s13-failing-job -n kubexplain-eval | grep \"Deadline\"\n``` | *Raise* or remove `activeDeadlineSeconds`, or make the workload complete faster (e.g., optimise code, increase resources). |
| 3 | **Pod never starts (image pull, auth, config errors)** | If the pod fails in the `Pending` phase (e.g., `ImagePullBackOff`, `ErrImagePull`, `CrashLoopBackOff` with `restartPolicy: Never`), each attempt counts as a failure. After the back‑off limit is hit the Job fails. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -A5 \"Events\"\n``` | *Fix the root cause*: ensure the image exists and is reachable, correct imagePullSecrets, verify command/args, add missing ConfigMaps/Secrets, etc. |
| 4 | **Resource‑quota or limit‑range violations** | If the pod is rejected because the namespace quota is exhausted or the pod exceeds a `LimitRange`, the pod never runs and counts as a failure. | ```bash\nkubectl describe quota -n kubexplain-eval\nkubectl describe limitrange -n kubexplain-eval\n``` | Adjust the quota/limitrange, or lower the pod’s `requests`/`limits`. |
| 5 | **Unschedulable node (taints, insufficient capacity)** | The scheduler cannot place the pod (e.g., no node matches nodeSelector/affinity, or all nodes are tainted). The pod stays in `Pending` → failure → back‑off limit → Job fails. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job -o wide\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"taint\"\n``` | Add appropriate node selectors/affinity, remove/adjust taints, or add capacity (scale the node pool). |
| 6 | **Incorrect Job spec (restartPolicy, completions, parallelism)** | A Job with `restartPolicy: Never` (default) treats any container exit as a pod failure. If `completions` is set > 1 but the pod always exits early, the Job will keep failing. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -E \"restartPolicy|completions|parallelism\"\n``` | Change `restartPolicy` to `OnFailure` if you want container restarts inside the same pod, or adjust `completions`/`parallelism` to match the intended workload. |
| 7 | **External dependency failure (API, DB, service)** | The container runs, but its code exits with an error because a required service is unavailable. This is indistinguishable from a “bug” until you look at logs. | ```bash\nkubectl logs <pod-name> -n kubexplain-eval\n``` | Fix the external service, add retries/back‑off in the application, or provide mock/stub endpoints for testing. |

---

## How to verify each cause (once you regain cluster access)

1. **Check Job status**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml
   ```
   - Look at `status.conditions` for `Failed` reason.  
   - `status.failed` tells how many pod failures occurred.

2. **Inspect the most recent pod(s)**  
   ```bash
   kubectl get pods -n kubexplain-eval -l job-name=s13-failing-job
   kubectl describe pod <pod-name> -n kubexplain-eval
   kubectl logs <pod-name> -n kubexplain-eval   # if it ever started
   ```
   - The **Events** section reveals `FailedScheduling`, `ImagePullBackOff`, `CrashLoopBackOff`, etc.  
   - The **Container State** (`terminated` with `exitCode`) shows why the container stopped.

3. **Review Job spec for limits**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -E "backoffLimit|activeDeadlineSeconds|restartPolicy|completions|parallelism"
   ```

4. **Check namespace quotas / limit ranges**  
   ```bash
   kubectl describe quota -n kubexplain-eval
   kubectl describe limitrange -n kubexplain-eval
   ```

5. **Node‑scheduling diagnostics**  
   ```bash
   kubectl get nodes
   kubectl describe node <node-name> | grep -i taint
   ```

---

## Quick “fix checklist” you can apply when you’re back in the cluster

| Issue | Immediate action | Longer‑term prevention |
|-------|------------------|------------------------|
| Back‑off limit hit | `kubectl edit job s13-failing-job` → raise `backoffLimit` or delete the Job and recreate with a higher limit. | Set a sensible `backoffLimit` based on expected transient failures; add alerting on `JobFailed` events. |
| Deadline exceeded | Remove or increase `activeDeadlineSeconds`. | Avoid hard deadlines unless the workload truly must be bounded; monitor job duration trends. |
| Image pull / auth error | Verify image name, registry URL, and `imagePullSecrets`. Fix secret or use a public image. | Use image digest tags, keep secrets up‑to‑date, and enable image‑pull‑policy `IfNotPresent` where appropriate. |
| Resource quota exhausted | Reduce pod `requests/limits` or request a quota increase. | Implement resource‑request budgeting and CI checks for quota compliance. |
| Unschedulable (taints/affinity) | Adjust pod `nodeSelector`, `affinity`, or remove unnecessary taints. | Keep node‑labeling policies documented; use tolerations only when needed. |
| Wrong Job spec | Edit the Job to set `restartPolicy: OnFailure` (if you want container restarts) or correct `completions`/`parallelism`. | Store Job manifests in version‑controlled Helm/ Kustomize templates; review spec defaults. |
| Application error | Look at container logs, fix the code or external dependency, add retries. | Add health‑checks, circuit‑breaker patterns, and proper error handling in the app. |

---

### TL;DR

The most common reason a Job ends `Failed` with no further retries is **hitting the `backoffLimit`** after repeated pod failures (often caused by image‑pull problems, scheduling issues, or application crashes). Next most frequent is an **`activeDeadlineSeconds`** timeout. Verify by inspecting the Job’s `status.failed`, the pod events, and the Job spec fields mentioned above. Fixes range from increasing limits to correcting the underlying pod failure (image, resources, node placement, or application code). Once you regain access, run the `kubectl` commands listed under “How to verify each cause” to pinpoint the exact trigger and apply the corresponding fix.
