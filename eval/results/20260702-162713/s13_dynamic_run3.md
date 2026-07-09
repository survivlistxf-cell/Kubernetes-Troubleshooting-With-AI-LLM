# s13 | mode=dynamic | run=3 | 12.9s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons a Job stops retrying after a failure  

| # | Possible cause | Why it makes the Job stop | How to verify (when you can access the cluster) | Typical fix |
|---|----------------|---------------------------|--------------------------------------------------|-------------|
| 1 | **`backoffLimit` reached** – the Job’s `.spec.backoffLimit` (default = 6) counts how many times a pod has failed. When the count ≥ `backoffLimit` the Job is marked **Failed** and the controller stops creating new pods. | The Job controller stops retrying once the limit is hit, so you see no more pods. | ```bash\nkubectl describe job s13-failing-job -n kubexplain-eval\n``` Look for a line like `BackoffLimit: 6` and an event `BackoffLimitExceeded`. Also check `status.failed` – it should be equal to the backoff limit. | Raise the limit (`kubectl edit job …` → `spec.backoffLimit: <higher>`), or fix the underlying pod failure so fewer retries are needed. |
| 2 | **`activeDeadlineSeconds` expired** – the Job has a global deadline. When the wall‑clock time since the Job started exceeds this value the Job is terminated and marked Failed. | The controller treats the deadline as a hard stop; no further pod creation occurs. | In `kubectl describe job …` look for `ActiveDeadlineSeconds:` and an event `DeadlineExceeded`. | Increase or remove the deadline, or make the pod complete faster (e.g., reduce work, improve resources). |
| 3 | **Pod template error that causes immediate failure** – e.g., wrong image name, missing command, image‑pull secret problems, CrashLoopBackOff that exhausts the backoff limit quickly. | Each pod fails instantly, counting toward the backoff limit, so the Job gives up after a few attempts. | 1. `kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval` to list the pods that were created. <br>2. `kubectl describe pod <pod>` to see events such as `Failed to pull image`, `CrashLoopBackOff`, `Error`. <br>3. `kubectl logs <pod> --previous` to view the last container logs. | Fix the container spec: correct image name/tag, add a valid imagePullSecret, add the missing `command`/`args`, or use an image that contains a shell if you need debugging. |
| 4 | **Insufficient cluster resources / scheduling failures** – the pod cannot be scheduled (Pending) and the scheduler keeps retrying until the backoff limit is hit. | Pods stay in `Pending` and eventually the Job controller counts them as failures, stopping further retries. | `kubectl describe pod <pod>` will show a `FailedScheduling` event with reasons such as `Insufficient cpu`, `Insufficient memory`, or `node(s) had taints`. | Add more nodes, free resources, lower the pod’s `requests/limits`, or add appropriate tolerations/affinity rules. |
| 5 **`ttlSecondsAfterFinished` caused immediate deletion** – if the Job has a TTL controller enabled, the Job object (and its pods) may be removed shortly after it reaches a terminal state. | You may think the Job “stopped retrying” because the object disappeared, but the real failure happened earlier. | `kubectl get job s13-failing-job -n kubexplain-eval -o yaml` – look for `ttlSecondsAfterFinished`. If the Job is already gone, check the audit log or the controller manager logs for `TTL after finished`. | Remove or increase the TTL, or rely on external monitoring to capture the failure before deletion. |
| 6 | **Job controller disabled or mis‑configured** – the `job-controller` component is not running or is stuck, so it never creates new pods after a failure. | Even though the Job spec says “retry”, the controller never acts. | Check the control‑plane logs (`/var/log/kube-controller-manager.log`) for `job-controller` errors, or run `kubectl get componentstatuses`. | Restart the controller manager, fix any configuration errors, or upgrade the control plane. |
| 7 | **`parallelism` / `completions` mismatch** – the Job expects more completions than it can achieve because pods keep failing, and the backoff limit is reached before the required number of successful pods finish. | The Job is considered Failed once the backoff limit is hit, even though the required completions were not met. | `kubectl describe job …` shows `Completions: X` and `Parallelism: Y`. Compare with `status.succeeded`. | Reduce the required completions, increase `backoffLimit`, or make the workload more reliable. |

---

## How to verify each cause (once you have cluster access)

1. **Inspect the Job status and events**  
   ```bash
   kubectl describe job s13-failing-job -n kubexplain-eval
   ```
   - Look for `BackoffLimitExceeded`, `DeadlineExceeded`, `Failed` conditions, and the values of `backoffLimit` and `activeDeadlineSeconds`.

2. **Check the pods that the Job created**  
   ```bash
   kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval
   ```
   - For each pod, run `kubectl describe pod <pod>` and `kubectl logs <pod> --previous` to see why it failed (image pull, CrashLoopBackOff, scheduling, etc.).

3. **Review scheduler events** (if pods are stuck in Pending)  
   ```bash
   kubectl get events -n kubexplain-eval --field-selector involvedObject.kind=Pod,involvedObject.name=<pod>
   ```
   - Look for `FailedScheduling` reasons.

4. **Examine control‑plane logs** (if you suspect the controller)  
   - On the master node: `journalctl -u kube-controller-manager` or view `/var/log/kube-controller-manager.log`.  
   - Search for “job-controller” errors.

5. **Check TTL settings**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep ttlSecondsAfterFinished
   ```

---

## Quick “first‑fix” checklist

1. **If `BackoffLimitExceeded` appears** – increase `backoffLimit` or fix the pod failure.  
2. **If `DeadlineExceeded` appears** – raise `activeDeadlineSeconds` or speed up the job’s work.  
3. **If pods are in `CrashLoopBackOff` or `ImagePullBackOff`** – correct the container image, add missing secrets, or adjust the command.  
4. **If pods are `Pending` with scheduling errors** – add resources, adjust requests/limits, or add nodes.  
5. **If the Job disappears quickly** – remove or increase `ttlSecondsAfterFinished`.  
6. **If the controller manager is unhealthy** – restart the control‑plane component.  

---

### References  

- Debugging a crashing pod and using `kubectl describe` / `kubectl logs` – <https://kubernetes.io/docs/tasks/debug/debug-application/debug-running-pod/>  
- General troubleshooting of pods and jobs (Pending, CrashLoopBackOff, backoff limits) – <https://kubernetes.io/docs/tasks/debug/debug-pods/>  

Apply the appropriate fix once you can run the verification commands. Good luck!
