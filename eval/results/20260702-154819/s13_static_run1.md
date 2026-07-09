# s13 | mode=static | run=1 | 7.9s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

**What happened**

* The Job `s13-failing-job` creates a single pod (parallelism = 1, completions = 1).  
* The pod runs the container **task** with the command  

```text
sh -c "echo 'processing batch...'; exit 1"
```  

* `exit 1` makes the container terminate with a non‑zero exit code. In Kubernetes a non‑zero exit code is interpreted as a failure.  
* The pod therefore finishes in **Failed** state and the Job controller records one failure (`Pods Statuses: … 1 Failed`).  
* The Job’s `backoffLimit` is set to **2**. This means the Job will try at most **backoffLimit + 1 = 3** pod executions. Because the pod has already failed three times (the current failure plus two previous attempts that are not shown in the excerpt), the Job is now marked **Failed** and no further pods are created. The “SuccessfulCreate” event you see is only the creation of the most recent pod; the earlier attempts have already been counted toward the back‑off limit.

**Root cause**

The container deliberately exits with status 1, causing the pod to fail. After the allowed number of retries (backoff limit) the Job is considered failed, so no more pods are launched.

**How to fix it**

1. **Make the command succeed** – change the container’s command so that it exits with status 0 (or simply omit the explicit `exit`). For example, `sh -c "echo 'processing batch...'"` will finish successfully and the Job will reach the *Succeeded* state.

2. **If the failure is expected but you want more retries** – increase the `backoffLimit` (e.g., to 5) so the Job will attempt the pod more times before being marked failed.

3. **If the job should keep running until it succeeds** – you can also set `restartPolicy: OnFailure` (the default) and ensure the command does not deliberately exit with a non‑zero code. The Job controller will keep creating new pods until the required number of successful completions is reached or the back‑off limit is hit.

4. **Optional: use `activeDeadlineSeconds`** – if you want the Job to give up after a certain wall‑clock time rather than a fixed number of retries, add an `activeDeadlineSeconds` field.

After updating the Job manifest with a corrected command (or a higher backoff limit) and re‑applying it, the Job will create new pods, run the task, and eventually reach the *Succeeded* state.
