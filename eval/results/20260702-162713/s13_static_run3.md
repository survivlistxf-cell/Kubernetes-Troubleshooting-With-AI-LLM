# s13 | mode=static | run=3 | 16.1s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons why the Job **`s13-failing-job`** is stuck in **`Failed`** and is no longer creating Pods  

| # | Probable cause | Why it leads to a permanent Failed state | How to verify (when you can reach the cluster) | How to fix |
|---|----------------|------------------------------------------|-----------------------------------------------|------------|
| 1 | **Back‑off limit exhausted** (`spec.backoffLimit` reached) | Jobs retry failed Pods only up to the value of `backoffLimit` (default = 6). When the count of failed Pods equals this limit the Job status becomes **Failed** and no more Pods are created. | ```bash\nkubectl describe job s13-failing-job -n kubexplain-eval\n``` Look for `BackoffLimit` in the spec and `Failed: X` in the status. The event “BackoffLimitExceeded” will be listed. | • Increase `backoffLimit` (or set it to a higher number) in the Job manifest and re‑apply.<br>• Or, fix the underlying pod failure so the Job can succeed before the limit is hit. |
| 2 | **`activeDeadlineSeconds` exceeded** | If the Job runs longer than the deadline, the controller marks it **Failed** and stops retries, regardless of how many attempts remain. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep activeDeadlineSeconds\n``` and check `status.startTime` vs `status.completionTime`. The event “DeadlineExceeded” will appear. | • Raise or remove `activeDeadlineSeconds` in the Job spec.<br>• Or make the workload finish faster (e.g., optimise the command, reduce data size). |
| 3 | **Pod template cannot start** (image‑pull error, missing command, bad init container, CrashLoopBackOff) | Each retry creates a new Pod that immediately fails. After a few attempts the back‑off limit is hit, so the Job ends up Failed. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job\nkubectl describe pod <pod-name> -n kubexplain-eval\nkubectl logs <pod-name> -n kubexplain-eval --previous\n``` Look for `ErrImagePull`, `ImagePullBackOff`, `CrashLoopBackOff`, or init‑container failures in the events and container status. | • Verify the image name/tag exists and is reachable from the cluster.<br>• If the registry requires credentials, ensure a proper `imagePullSecret` is attached.<br>• Check the command/args in the container spec; correct any typo.<br>• If an init container fails, fix its image or command. |
| 4 | **Insufficient cluster resources / unschedulable Pods** | Scheduler cannot place a new Pod (e.g., no CPU/memory, hostPort conflict). The Pod stays in **Pending** → counts as a failure → back‑off limit is reached. | ```bash\nkubectl describe pod <pod-name> -n kubexplain-eval\n``` Look for events like `FailedScheduling` with reasons such as `Insufficient cpu`, `Insufficient memory`, or `hostPort` conflicts. | • Free resources (delete unused Pods, scale down other workloads).<br>• Add more nodes or increase node capacity.<br>• Adjust the Job’s `resources.requests/limits` to fit available capacity.<br>• If using `hostPort`, remove it or ensure enough nodes. |
| 5 | **Job’s `restartPolicy` set to `Never`** (or an unsupported value) | With `restartPolicy: Never`, a container that exits with a non‑zero code is considered a failure; the Job will retry only via back‑off, not by restarting the same Pod. If the failure is immediate, the back‑off limit is quickly exhausted. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep restartPolicy\n``` | • Change `restartPolicy` to `OnFailure` (the only allowed value for Jobs) in the manifest.<br>• Re‑apply the Job. |
| 6 | **Job was manually suspended** (`spec.suspend: true`) after failures | A suspended Job will not create new Pods, leaving the status as **Failed** if the back‑off limit was already hit. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep suspend\n``` | • Set `spec.suspend: false` and re‑apply, or delete & recreate the Job. |
| 7 | **Namespace‑level quota or limit range blocking Pod creation** | If a `ResourceQuota` or `LimitRange` prevents the Job’s Pod from being admitted, each attempt fails and the back‑off limit is reached. | ```bash\nkubectl describe quota -n kubexplain-eval\nkubectl describe limitrange -n kubexplain-eval\n``` | • Adjust the quota/limit range or lower the Job’s resource requests.<br>• Or request a quota increase from the cluster admin. |
| 8 | **Controller‑manager or scheduler outage** (cluster‑wide issue) | If the control plane components that create Pods are down, the Job cannot spawn new Pods, eventually hitting the back‑off limit. | ```bash\nkubectl get componentstatuses\nkubectl get nodes\n``` Look for `NotReady` or `Unknown` components. | • Restore the affected control‑plane component (restart kube‑scheduler, kube‑controller‑manager, etc.).<br>• If the whole cluster is down, bring the control plane back up before re‑creating the Job. |

---

### General verification workflow (run when you regain access)

1. **Inspect the Job status and events**  
   ```bash
   kubectl describe job s13-failing-job -n kubexplain-eval
   ```
   - Look for events: `BackoffLimitExceeded`, `DeadlineExceeded`, `FailedScheduling`, `FailedCreate`, etc.  
   - Note `spec.backoffLimit`, `spec.activeDeadlineSeconds`, `spec.restartPolicy`, `spec.suspend`.

2. **Find the last Pod(s) created by the Job**  
   ```bash
   kubectl get pods -n kubexplain-eval -l job-name=s13-failing-job
   ```
   - Pick the most recent pod (usually the one with the highest suffix).

3. **Examine that Pod’s detailed state**  
   ```bash
   kubectl describe pod <pod-name> -n kubexplain-eval
   kubectl logs <pod-name> -n kubexplain-eval --previous   # if it crashed
   ```
   - Identify image‑pull problems, crash loops, init‑container failures, or scheduling failures.

4. **Check node health (if scheduling is the suspect)**  
   ```bash
   kubectl get nodes
   kubectl describe node <node-name>
   ```

5. **Review namespace‑wide quotas/limits** (if resource‑quota errors appear)  
   ```bash
   kubectl get quota -n kubexplain-eval
   kubectl get limitrange -n kubexplain-eval
   ```

6. **Apply the appropriate fix** (adjust the Job manifest, fix the container image/command, increase resources, modify quotas, or repair the control plane). Then re‑create or patch the Job:

   ```bash
   # Example: increase backoff limit
   kubectl patch job s13-failing-job -n kubexplain-eval \
     -p '{"spec":{"backoffLimit":20}}' --type=merge
   ```

   Or delete the failed Job and apply a corrected manifest.

---

### Quick “first‑check” checklist

- **Backoff limit reached?** → `BackoffLimitExceeded` event → raise `backoffLimit`.  
- **Deadline exceeded?** → `DeadlineExceeded` event → increase/remove `activeDeadlineSeconds`.  
- **Pod never starts?** → Look for `ErrImagePull`, `CrashLoopBackOff`, `FailedScheduling`. Fix image, command, or resources.  
- **Cluster resources exhausted?** → `FailedScheduling` with “Insufficient …”. Free resources or add nodes.  
- **Job suspended?** → `spec.suspend: true`. Unsuspend.  

Address the first cause you discover; often fixing the underlying pod failure (image, command, resources) will also prevent the back‑off limit from being hit, making the Job succeed without further changes.
