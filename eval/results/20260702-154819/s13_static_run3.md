# s13 | mode=static | run=3 | 8.7s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it?

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

### What happened?

| Item | Observation |
|------|--------------|
| **Job spec** | `backoffLimit: 2` – the job will try **at most 3** pod executions (initial try + 2 retries). |
| **Pod command** | `sh -c "echo 'processing batch...'; exit 1"` – the container deliberately exits with status **1** (non‑zero). |
| **Pod logs** | `processing batch...` – the only output, then the container terminates. |
| **Job status** | `Pods Statuses: 0 Active / 0 Succeeded / 1 Failed` – the single pod has failed and the job has exhausted its back‑off retries, so the job is now in **Failed** state and no new pods are created. |
| **Events** | Only a `SuccessfulCreate` event for the first pod; no further events because the back‑off limit stopped the controller. |

**Root cause:** the container’s command exits with a non‑zero exit code, causing the pod to fail. After the allowed number of retries (2), the `Job` controller marks the job as **Failed** and stops creating new pods.

---

### How to fix it

1. **Correct the command (most common fix)**  
   If the job is supposed to succeed, change the command so it exits with status 0, e.g.:

   ```yaml
   containers:
   - name: task
     image: busybox:1.36
     command: ["sh", "-c", "echo 'processing batch...'; exit 0"]
   ```

2. **Increase the retry allowance (if occasional failures are expected)**  
   Raise `backoffLimit` so the job will keep trying longer:

   ```yaml
   spec:
     backoffLimit: 5   # or any number that fits your tolerance
   ```

3. **Add a `restartPolicy` that matches your intent**  
   For a Job the only valid values are `OnFailure` (default) or `Never`.  
   - `OnFailure` lets the Job controller create a **new pod** on each failure (subject to `backoffLimit`).  
   - `Never` will keep the same pod running; the pod will still be marked Failed if the container exits non‑zero, but the Job controller will not create a replacement pod.  
   Choose the policy that matches the desired behaviour.

4. **Re‑create or patch the Job**  

   *Patch the existing Job (quick fix):*

   ```bash
   kubectl patch job s13-failing-job -n kubexplain-eval \
     --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/command","value":["sh","-c","echo \"processing batch...\"; exit 0"]}]'
   ```

   *Or delete the failed Job and apply a corrected manifest:*

   ```bash
   kubectl delete job s13-failing-job -n kubexplain-eval
   kubectl apply -f corrected-job.yaml
   ```

5. **Verify the fix**  

   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -w
   ```

   You should see the job reach `Complete` (Succeeded = 1) and no further failures.

---

### Quick diagnostic checklist (useful for future failures)

1. **Inspect the pod that the job created**  

   ```bash
   POD=$(kubectl get pods -n kubexplain-eval -l job-name=s13-failing-job -o jsonpath='{.items[0].metadata.name}')
   kubectl describe pod $POD -n kubexplain-eval
   kubectl logs $POD -n kubexplain-eval
   ```

2. **Check the job’s back‑off status**  

   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep backoffLimit -A2
   ```

3. **Confirm the exit code** (if you need to see it explicitly)

   ```bash
   kubectl get pod $POD -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}'
   ```

If the exit code is non‑zero, the job will keep failing until `backoffLimit` is reached.

---

**Bottom line:** the job failed because its container deliberately exited with status 1. Fix the command (or adjust `backoffLimit`/restart policy) and re‑run the job; it will then complete successfully.
