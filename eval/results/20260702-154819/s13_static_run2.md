# s13 | mode=static | run=2 | 7.0s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

**What happened**

* The Job `s13-failing-job` creates a single Pod that runs  

  ```bash
  sh -c "echo 'processing batch...'; exit 1"
  ```

* The container exits with status `1`. For a Job this is treated as a failure, so the Job controller creates a new Pod (restart = OnFailure) until the **backoffLimit** is reached.

* Your Job spec has `backoffLimit: 2`. That means the controller will try the pod **3 times total** (the first attempt + 2 retries). After the third failure the Job is marked **Failed** and no more Pods are created – which is exactly what you see.

**Why no more retries**

* The backoff limit has been exhausted. The Job controller stops creating Pods once the failure count ≥ `backoffLimit + 1`.

**How to fix it**

1. **Fix the command (most common fix)**
   * Change the container command so it exits `0` when the work is successful, e.g.:

     ```yaml
     containers:
     - name: task
       image: busybox:1.36
       command: ["sh", "-c", "echo 'processing batch...'"]
     ```

   * Apply the change by deleting the failed Job and recreating it (Jobs are not mutable for the pod template):

     ```bash
     kubectl delete job s13-failing-job -n kubexplain-eval
     kubectl apply -f <updated‑job‑manifest>.yaml
     ```

2. **If you want more retries before the Job is marked Failed**
   * Increase the backoff limit (or remove it to use the default of 6):

     ```bash
     kubectl patch job s13-failing-job -n kubexplain-eval \
       -p '{"spec":{"backoffLimit":5}}' --type=merge
     ```

   * Then delete the current failed Job and recreate it (the patched spec will be used for the new run).

3. **If the failure is expected and you don’t want retries**
   * Set `backoffLimit: 0` (or `1` if you want a single attempt) so the Job fails immediately after the first pod exit.

4. **Verify the fix**
   * After applying the corrected manifest, watch the Job:

     ```bash
     kubectl get job s13-failing-job -n kubexplain-eval -w
     ```

   * Check the pod logs to confirm the command succeeded:

     ```bash
     kubectl logs -l job-name=s13-failing-job -n kubexplain-eval
     ```

**Summary**

The root cause is the container’s `exit 1` combined with a `backoffLimit: 2`, which exhausted the retry count and left the Job in a Failed state. Fix the command to exit `0` (or adjust the backoff limit) and recreate the Job.
