# s13 | mode=none | run=1 | 6.2s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What happened  

| Item | Observation |
|------|--------------|
| **Job spec** | `backoffLimit: 2` – the controller will try the pod **up to 3 times** (initial try + 2 retries). |
| **Pod template** | The container runs `sh -c "echo 'processing batch...'; exit 1"`. |
| **Pod logs** | `processing batch...` – the command printed the message and then **exited with status 1**. |
| **Job status** | `0 Active / 0 Succeeded / 1 Failed` – the pod has terminated unsuccessfully and the job has given up. |
| **Events** | Only one `SuccessfulCreate` event is shown, which is normal; the controller does not emit a separate event for each retry. |

**Root cause** – The container deliberately exits with a non‑zero code (`exit 1`). Because the exit is considered a failure, the Job controller retries the pod. After the allowed number of retries (the back‑off limit) the Job is marked **Failed** and no further pods are created.  

### How to fix it  

1. **Make the workload succeed**  
   * Change the command (or the script it runs) so that it ends with exit code 0 when the work is done.  
   * Example change in the Job manifest:  

   ```yaml
   containers:
   - name: task
     image: busybox:1.36
     command: ["sh", "-c", "echo 'processing batch...'; exit 0"]
   ```

2. **If the failure is expected but you want more retries**  
   * Increase `backoffLimit` (e.g., to `5`) so the controller will keep trying longer before giving up.  

   ```yaml
   spec:
     backoffLimit: 5
   ```

3. **If you want the job to stop after the first failure**  
   * Set `backoffLimit: 0` (or remove the field, which defaults to 6) to control the exact retry behavior.  

4. **Apply the corrected manifest**  

   ```bash
   kubectl apply -f <your‑job‑manifest>.yaml
   ```

5. **Verify the fix**  

   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o wide
   kubectl logs job/s13-failing-job -n kubexplain-eval
   ```

   You should see the pod finish with `Succeeded` and the Job status change to `Complete`.

### Quick checklist  

- ✅ **Command exits with 0** (or a script that returns 0 on success).  
- ✅ **`backoffLimit` matches the retry policy you need**.  
- ✅ **Re‑apply the Job** and watch the status until it becomes `Complete`.  

Once the container exits cleanly (or you adjust the back‑off policy), the Job will no longer stay in a permanent `Failed` state and will stop creating new pods only when the configured retry limit is reached.
