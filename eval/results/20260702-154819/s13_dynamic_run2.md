# s13 | mode=dynamic | run=2 | 7.8s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What happened

| Item | Observation |
|------|--------------|
| **Job spec** | `backoffLimit: 2` – the controller will try the job **at most 3 times** (initial try + 2 retries). |
| **Pod template** | The container runs `sh -c "echo 'processing batch...'; exit 1"`. The `exit 1` makes the container terminate with a non‑zero exit code. |
| **Pod logs** | `processing batch...` – the command ran and then exited with status 1. |
| **Job status** | `Pods Statuses: 0 Active / 0 Succeeded / 1 Failed`. The job has already recorded a failure. |
| **Events** | Only a single `SuccessfulCreate` event – the pod was created, ran, failed, and the controller did **not** create another pod. |

**Root cause**

The job’s pod exits with a failure (`exit 1`). Because the job’s `backoffLimit` is set to 2, the controller has already exhausted its allowed retries (the first attempt plus two retries). Once the limit is reached, the job is marked **Failed** and no further pods are created, which is why you see “no pods are retrying anymore”.

### How to fix it

1. **Correct the command (or image) so that the container exits with status 0**  
   *If the job is supposed to succeed, change the command to something that completes successfully, e.g.*  

   ```yaml
   command: ["sh", "-c", "echo 'processing batch...'; exit 0"]
   ```

2. **If the failure is expected and you want more retries**  
   *Increase the backoff limit* (or remove it to use the default of 6). Example:

   ```yaml
   backoffLimit: 5   # allow up to 6 attempts total
   ```

3. **Re‑apply the corrected Job**  

   ```bash
   kubectl replace -f job.yaml   # or kubectl apply -f job.yaml
   ```

4. **Verify the fix**  

   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o wide
   kubectl describe job s13-failing-job -n kubexplain-eval
   kubectl logs pod/<new-pod-name> -n kubexplain-eval
   ```

   You should see the pod transition to **Succeeded** (or, if you kept the failure, that additional retries are created until the new `backoffLimit` is hit).

### Quick checklist

- ✅ Check the container’s exit code (`kubectl logs …`).  
- ✅ Make sure the command you intend to run ends with `exit 0` (or simply omits an explicit non‑zero exit).  
- ✅ Adjust `backoffLimit` if you need more retry attempts.  
- ✅ Re‑apply the Job and watch the new pod(s) with `kubectl get pods -w -n kubexplain-eval`.

Once the command succeeds (or you allow enough retries), the Job will move from **Failed** to **Complete** and no further manual intervention will be needed.
