# s13 | mode=none | run=1 | 18.1s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons a Job ends **Failed** and stops creating new Pods  

| # | Likely cause (most → least probable) | Why it produces the symptom | How to verify (when you have cluster access) | How to fix |
|---|--------------------------------------|-----------------------------|---------------------------------------------|------------|
| 1 | **Back‑off limit reached** (`spec.backoffLimit`) | Each pod that exits with a non‑zero code is counted as a failure. When the number of failures equals `backoffLimit` the Job is marked **Failed** and no more Pods are created. The default limit is 6, so a job that crashes repeatedly will stop after a few attempts. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -A3 backoffLimit\nkubectl describe job s13-failing-job -n kubexplain-eval | grep -i \"BackOffLimit\"\n```<br>Check the `status.failed` field and the `conditions` array – you should see a `Failed` condition with reason `BackoffLimitExceeded`. | * Increase the limit (`spec.backoffLimit: <higher>`).<br>* Make the pod succeed (fix the application bug, add proper exit code, etc.).<br>* If the failures are transient, consider adding a `restartPolicy: OnFailure` and a higher `backoffLimit`. |
| 2 | **Active‑deadline exceeded** (`spec.activeDeadlineSeconds`) | The Job has a global time‑budget. When the elapsed time since the Job started exceeds this value, the controller marks the Job **Failed** and stops creating new Pods, regardless of how many retries remain. | ```bash\nkubectl describe job s13-failing-job -n kubexplain-eval | grep -i \"activeDeadline\"\n```<br>Look for a condition with reason `DeadlineExceeded`. | * Raise or remove `activeDeadlineSeconds` in the Job spec.<br>* Reduce the work done per pod (e.g., split the job into smaller chunks) so it can finish within the deadline. |
| 3 | **Pod container image pull error** (e.g., `ImagePullBackOff`, `ErrImagePull`) | If the pod cannot pull the image, it fails immediately. Each failure counts toward the back‑off limit, so after a few attempts the Job stops. | ```bash\nkubectl get pods -l job-name=s13-failing-job -n kubexplain-eval\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"ImagePull\"\n``` | * Verify the image name/tag and registry credentials.<br>* Fix the image reference or create a proper `imagePullSecret`.<br>* If the registry is temporarily unavailable, increase `backoffLimit` or add a `restartPolicy`. |
| 4 | **CrashLoopBackOff / non‑zero exit code** (application bug, missing config, OOM) | The container starts, crashes, and the pod is recreated. Each crash increments the failure count. After the back‑off limit is hit the Job stops. | ```bash\nkubectl get pods -l job-name=s13-failing-job -n kubexplain-eval\nkubectl logs <pod-name> -n kubexplain-eval --previous\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"CrashLoopBackOff\"\n``` | * Inspect the logs to locate the error (e.g., missing env var, exception, OOMKilled).<br>* Fix the code/configuration, or increase resources (`resources.requests/limits`).<br>* If the crash is expected after a certain condition, make the container exit with code 0 when the work is done. |
| 5 | **Insufficient resources / scheduling failures** (`Insufficient cpu/memory`, node selector, taints) | The pod cannot be scheduled, stays in `Pending`, and after a timeout the Job controller treats it as a failure (counts toward back‑off). No new pods are created once the limit is reached. | ```bash\nkubectl get pods -l job-name=s13-failing-job -n kubexplain-eval -o wide\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"FailedScheduling\"\n``` | * Adjust the pod’s `resources.requests` to fit the cluster capacity.<br>* Add or modify node selectors/affinity, or remove taints that block scheduling.<br>* Increase cluster capacity or enable cluster autoscaler. |
| 6 | **Node failure / pod eviction** (node went `NotReady`, network partition) | The pod is terminated on a dead node; the Job controller creates a replacement. If the node repeatedly fails, the replacement pods also fail, quickly exhausting the back‑off limit. | ```bash\nkubectl get events -n kubexplain-eval --field-selector involvedObject.kind=Pod,involvedObject.name=<pod-name>\nkubectl describe node <node-name> | grep -i \"NotReady\"\n``` | * Investigate node health; cordon/drain the problematic node.<br>* Ensure the cluster has enough healthy nodes to reschedule the pod.<br>* Optionally set `podAntiAffinity` to avoid concentrating pods on a single node. |
| 7 | **Job spec mis‑configuration** (e.g., `completions` > 1 but only one pod runs, `parallelism` set incorrectly) | The controller may think the required number of successful pods can never be reached, and after the back‑off limit it gives up. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -E \"completions|parallelism\"\n``` | * Align `completions` with the intended number of successful pods.<br>* If you only need a single run, set `completions: 1` and `parallelism: 1`.<br>* Remove unnecessary `restartPolicy` settings that conflict with Job semantics. |
| 8 | **External controller interference** (Argo, Tekton, custom controller) | A higher‑level controller may delete or suspend the Job after a failure, making it appear “stuck”. | ```bash\nkubectl get all -n kubexplain-eval | grep s13-failing-job\n```<br>Check for ownerReferences pointing to another CRD. | * Identify the owning controller (e.g., `Workflow`, `PipelineRun`).<br>* Adjust its retry policy or error handling.<br>* If you want the plain Kubernetes Job to run, delete the higher‑level object and recreate the Job. |

---

## How to systematically diagnose the problem (once you regain access)

1. **Inspect the Job status**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml
   ```
   * Look for `status.conditions` – especially a `Failed` condition and its `reason`.  
   * Note `status.failed`, `status.succeeded`, `spec.backoffLimit`, `spec.activeDeadlineSeconds`.

2. **Check the recent Pods created by the Job**  
   ```bash
   kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval
   ```
   * Identify the pod that last ran (usually the one with the highest `RESTARTS` or the most recent `AGE`).  

3. **Describe the failing pod** – this surface events such as image pull errors, scheduling failures, OOMKilled, etc.  
   ```bash
   kubectl describe pod <pod-name> -n kubexplain-eval
   ```

4. **Read the pod logs** (including the previous container if it crashed)  
   ```bash
   kubectl logs <pod-name> -n kubexplain-eval --previous
   ```

5. **Correlate with cluster‑wide events** (optional)  
   ```bash
   kubectl get events -n kubexplain-eval --sort-by=.metadata.creationTimestamp
   ```

6. **If the Job is owned by another controller**, check that controller’s status and retry policy.

---

## Quick “fix checklist” you can apply when you’re back in the cluster

| Issue | Immediate corrective action |
|-------|------------------------------|
| Back‑off limit hit | `kubectl edit job s13-failing-job -n kubexplain-eval` → raise `spec.backoffLimit` or delete the Job and recreate with a higher limit. |
| Deadline exceeded | Increase or remove `spec.activeDeadlineSeconds`. |
| Image pull error | Verify image name/tag, ensure registry credentials (`imagePullSecrets`) are correct, and that the node can reach the registry. |
| CrashLoopBackOff / non‑zero exit | Fix the application bug, add missing config/secret, or raise resource limits (`resources.limits`). |
| Scheduling failure | Adjust `resources.requests`, node selectors, or taints; add capacity or enable autoscaling. |
| Node problems | Drain the faulty node (`kubectl drain <node> --ignore-daemonsets`), then let the Job schedule elsewhere. |
| Mis‑configured completions/parallelism | Edit the Job to set `completions: 1` and `parallelism: 1` (or the intended values). |
| External controller aborts | Modify the owning controller’s retry policy or delete it if you want the plain Job to run. |

---

### Bottom line
The **most common** reason a Job stops retrying is that the **back‑off limit has been exhausted**. Verify the Job’s `status.failed` count and the `Failed` condition reason first. From there, follow the verification steps above to pinpoint whether the failures are due to image pull problems, crashes, resource constraints, deadlines, or an external controller, and apply the corresponding fix. Once the root cause is addressed, either delete the failed Job and recreate it, or patch the existing Job with the corrected spec. Good luck!
