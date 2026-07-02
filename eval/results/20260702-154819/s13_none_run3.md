# s13 | mode=none | run=3 | 7.5s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What happened?

| Observation | Meaning |
|-------------|---------|
| **Pod logs** – `processing batch...` then the container exits | The container’s command (`sh -c "echo 'processing batch...'; exit 1"`) deliberately returns exit code **1**. |
| **Job spec** – `Backoff Limit: 2` | The Job controller will try the pod **up to 3 times** (initial try + 2 retries). After the third failure it marks the Job **Failed** and stops creating new pods. |
| **Events** – only `SuccessfulCreate` | The pod was created successfully; the failure is not a scheduling problem but a container‑exit problem. |
| **Job status** – `1 Failed, 0 Succeeded` | All allowed attempts have been exhausted, so the Job is now in a terminal **Failed** state. |

**Root cause:** The container’s command exits with a non‑zero status (`exit 1`). Because the Job’s `backoffLimit` is 2, the controller gave up after three failed attempts, leaving the Job in a Failed state with no further retries.

---

### How to fix it

1. **Correct the command (or script) so it exits with `0` on success.**  
   Example corrected manifest fragment:

   ```yaml
   spec:
     template:
       spec:
         containers:
         - name: task
           image: busybox:1.36
           command: ["sh", "-c", "echo 'processing batch...'; exit 0"]
   ```

2. **If the failure is expected but you want more retries**, raise the `backoffLimit` (e.g., to `5`).  

   ```yaml
   spec:
     backoffLimit: 5
   ```

3. **Apply the corrected Job** (or delete the failed one and recreate it):

   ```bash
   # Option 1 – edit in‑place
   kubectl edit job s13-failing-job -n kubexplain-eval
   # (modify the command or backoffLimit, then save)

   # Option 2 – delete & recreate with a new manifest
   kubectl delete job s13-failing-job -n kubexplain-eval
   kubectl apply -f corrected-job.yaml
   ```

4. **Verify the fix**:

   ```bash
   # Watch the new pod(s) and Job status
   kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval -w
   kubectl get job s13-failing-job -n kubexplain-eval
   ```

   When the container finishes with exit code 0, the Job will move to **Succeeded**.

---

### Quick checklist

- [ ] Does the container command/script end with `exit 0` (or no explicit non‑zero exit)?
- [ ] Is the `backoffLimit` appropriate for the expected transient failures?
- [ ] After fixing, delete the old failed Job (or let the controller create a new pod) and confirm the Job reaches `Succeeded`.

Once the command returns a successful exit code, the Job will complete normally and no further “Failed” state will appear.
