# s13 | mode=dynamic | run=3 | 8.7s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What happened

| Item | Observation |
|------|--------------|
| **Job spec** | `backoffLimit: 2` – the controller will try the job at most **2 retries** after a failure. |
| **Pod template** | Runs `busybox:1.36` with the command `sh -c "echo 'processing batch...'; exit 1"`. |
| **Pod logs** | `processing batch...` – the container exits with **status 1** (non‑zero). |
| **Job status** | `0 Active / 0 Succeeded / 1 Failed`. The job has already been marked **Failed**. |
| **Events** | Only one `SuccessfulCreate` event – a single pod was created. No further “Backoff” events are shown, which means the controller has already exhausted its retry budget.

**Root cause** – The container deliberately exits with a non‑zero code (`exit 1`). The job controller treats that as a failure, increments the back‑off counter, and after the allowed number of retries (2) it marks the Job as **Failed** and stops creating new pods. Because the pod failed on its first attempt, the back‑off counter quickly reached the limit (the first failure counts as the first retry, the second failure would be the second retry, and then the job is considered failed).

### How to fix it

1. **Make the workload succeed**  
   * Change the command so it exits 0 (or runs a real workload that can complete successfully).  
   * Example edit: `command: ["sh","-c","echo 'processing batch...'; exit 0"]`.

2. **If you want the job to keep trying**  
   * Increase the retry budget: set a larger `backoffLimit` (e.g., `backoffLimit: 5`).  
   * Optionally set `restartPolicy: Never` so each retry creates a brand‑new pod (makes it easier to see each attempt in `kubectl get pods -w`).

3. **Apply the corrected manifest**  
   ```bash
   kubectl apply -f corrected-job.yaml
   ```
   (or `kubectl edit job s13-failing-job -n kubexplain-eval` and modify the fields in‑place).

4. **Verify the fix**  
   * Watch the job: `kubectl get job s13-failing-job -n kubexplain-eval -w`.  
   * When the pod finishes, `kubectl describe job s13-failing-job -n kubexplain-eval` should show `1 Succeeded` and no `Failed` count.  
   * Check the pod logs to confirm the expected output.

### Quick checklist

- [ ] **Command exits 0** (or does real work).  
- [ ] **Backoff limit** is high enough for the number of retries you expect.  
- [ ] **restartPolicy** is set appropriately (`OnFailure` is default; `Never` gives a new pod per retry).  

After correcting the command (or adjusting the back‑off limit), the Job will either complete successfully or keep retrying until it succeeds, eliminating the “Failed” state you observed.
