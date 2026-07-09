# s13 | mode=dynamic | run=1 | 10.7s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What the evidence tells us

| Item | Value |
|------|-------|
| **Job name / namespace** | `s13-failing-job` / `kubexplain-eval` |
| **Backoff Limit** | `2` (the job may be retried ≤ 2 times after a failure) |
| **Pod template – container command** | `sh -c "echo 'processing batch...'; exit 1"` |
| **Pod logs** | `processing batch...` (the container then exits with status 1) |
| **Job events** | Only one event: *Created pod: s13-failing-job‑xv9d2* |
| **Pod status** | `Failed` (the pod is not running any containers) |
| **Pods Statuses** | `0 Active / 0 Succeeded / 1 Failed` |

#### Root cause
The container’s command ends with `exit 1`, which is a non‑zero exit code. In a **Job** the default `restartPolicy` is `OnFailure`; when a container exits with a non‑zero code the pod is marked **Failed**. The Job controller then creates a new pod to retry, but it will stop retrying after the **backoff limit** is exhausted ( `backoffLimit + 1` total attempts ).  

Because the command deliberately fails, the pod fails immediately, and the Job will keep retrying until it reaches the backoff limit. Once the limit is hit, the Job’s status becomes **Failed** and no further pods are created – which is exactly what you are seeing.

### How to fix it

#### 1. Make the job’s work succeed
Edit the Job (or the manifest you use to create it) so that the container exits with status 0.

```yaml
containers:
- name: task
  image: busybox:1.36
  command: ["sh", "-c", "echo 'processing batch...'; exit 0"]
```

Apply the change:

```bash
kubectl apply -f <job‑manifest>.yaml
```

#### 2. If the failure is expected but you want more retries
Increase the backoff limit (or remove it) so the Job can retry more times before being marked Failed.

```bash
kubectl patch job s13-failing-job -n kubexplain-eval \
  -p '{"spec":{"backoffLimit":5}}' --type=merge
```

#### 3. If you want the job to stop after the first failure (no retries)
Set `backoffLimit: 0` so the Job is marked Failed immediately after the first pod failure.

```bash
kubectl patch job s13-failing-job -n kubexplain-eval \
  -p '{"spec":{"backoffLimit":0}}' --type=merge
```

#### 4. Verify the fix
After applying the change, watch the job:

```bash
kubectl get job s13-failing-job -n kubexplain-eval -w
```

You should see the pod transition to **Succeeded** (if you fixed the command) or the job retrying the expected number of times (if you changed the backoff limit).

### TL;DR
*The job fails because the container deliberately exits with status 1. The Job controller retries until the `backoffLimit` is exhausted, after which the Job is marked Failed and no more pods are created.*  
Fix it by correcting the command (or script) so it exits 0, or by adjusting `backoffLimit` to the retry behavior you want.
