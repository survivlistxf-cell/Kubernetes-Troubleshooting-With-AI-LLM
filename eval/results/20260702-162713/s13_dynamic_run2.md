# s13 | mode=dynamic | run=2 | 16.1s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons why the Job stopped retrying  

| # | Reason (probability ≈) | Why it stops the Job | How to verify (when you can reach the cluster) | How to fix |
|---|------------------------|----------------------|-----------------------------------------------|------------|
| 1 | **`backoffLimit` reached** (≈ 70 %) | Each time a pod finishes with a non‑zero exit code the Job increments `status.failed`. When the count reaches the value of `spec.backoffLimit` (default = 6) the Job is marked **Failed** and no new pods are created. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -A3 \"status:\" \n```\nLook for `status.failed: <n>` and `spec.backoffLimit: <m>`. If `failed >= backoffLimit` the Job is the culprit. Also check `status.conditions` for `type: Failed`. | • Fix the underlying pod problem (see rows 3‑4). <br>• Or raise the limit, e.g. add `backoffLimit: 10` (or a higher value) to the Job spec and re‑apply. |
| 2 | **`activeDeadlineSeconds` exceeded** (≈ 15 %) | The Job has a hard wall‑clock limit. When the total wall‑clock time of the Job exceeds this value the controller marks the Job **Failed** and stops creating pods, regardless of how many retries are left. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -A2 \"activeDeadlineSeconds\"\n```\nIf the field is present, compare the elapsed time (`status.startTime` → now) with the deadline. | • Increase or remove `activeDeadlineSeconds` in the spec. <br>• If the job really needs more time, adjust the value and re‑apply. |
| 3 | **Pod repeatedly fails (image‑pull, command error, OOM, etc.)** (≈ 10 %) | Each pod that exits with a non‑zero code counts toward `failed`. If the failure is deterministic (e.g. wrong image name, missing entrypoint, insufficient resources) the Job quickly hits the back‑off limit. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job -o wide\nkubectl describe pod <pod-name> -n kubexplain-eval\nkubectl logs <pod-name> -n kubexplain-eval --previous\n```\nLook for `ImagePullBackOff`, `CrashLoopBackOff`, `OOMKilled`, or a non‑zero exit code in the container status. | • Correct the container image name / tag or ensure the registry is reachable. <br>• Add the missing command/args or fix the entrypoint. <br>• Adjust resource requests/limits so the container does not OOM. <br>• Re‑create the Job (or delete the failed Job and apply a fixed manifest). |
| 4 | **Insufficient cluster resources → pods stay Pending** (≈ 3 %) | Pods that never start are **not** counted as failures, so the Job keeps creating new pods until the back‑off limit is hit. If the cluster is out of CPU/memory or the pod uses a `hostPort`, the scheduler will keep rejecting it. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job -o wide\nkubectl describe pod <pending-pod> -n kubexplain-eval\n```\nCheck the `Events` section for messages like `FailedScheduling` and the reason (e.g. `Insufficient cpu`). | • Free up resources (delete unused workloads, scale down other Deployments). <br>• Add more nodes or increase node capacity. <br>• Remove unnecessary `hostPort` or lower resource requests. |
| 5 | **`completions` / `parallelism` mis‑configuration** (≈ 1 %) | If `spec.completions` is > 1 but only a subset of pods ever succeed, the Job will keep failing until the back‑off limit is reached. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -E \"completions|parallelism\"\n```\nVerify the intended number of successful pods. | • Adjust `completions` to the correct value, or make the pod logic idempotent so each attempt can succeed. |
| 6 | **Cluster‑wide control‑plane issue (API server down, etc.)** (≈ < 1 %) | If the API server was unavailable while the Job was running, the controller may have missed updates and marked the Job as Failed. This is rare and usually accompanied by other cluster‑wide symptoms. | ```bash\nkubectl get componentstatuses\nkubectl get events -A | grep \"apiserver\"\n```\nLook for errors about the control plane. | • Restore the control‑plane components (restart the API server, etc.). <br>• Once the control plane is healthy, delete the failed Job and recreate it. |

---

## General verification checklist (run when you regain access)

1. **Inspect the Job status**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml
   ```
   - `status.failed` vs `spec.backoffLimit`  
   - `status.conditions` for `type: Failed` and `reason`  
   - `spec.activeDeadlineSeconds` (if present)

2. **Look at the most recent pod(s) created by the Job**  
   ```bash
   kubectl get pods -n kubexplain-eval -l job-name=s13-failing-job
   kubectl describe pod <pod-name> -n kubexplain-eval
   kubectl logs <pod-name> -n kubexplain-eval --previous   # if container restarted
   ```

3. **Check scheduler events** (if pods are stuck Pending)  
   ```bash
   kubectl describe pod <pending-pod> -n kubexplain-eval | grep -i "FailedScheduling"
   ```

4. **Confirm cluster resource health** (optional)  
   ```bash
   kubectl top nodes
   kubectl get nodes
   ```

---

## Typical remediation flow

1. **Identify the root cause** using the steps above.  
2. **Fix the underlying pod problem** (image, command, resources, etc.).  
3. **If the failure count is the blocker**, either:  
   - Delete the failed Job (`kubectl delete job s13-failing-job -n kubexplain-eval`) and recreate it with the corrected pod spec, **or**  
   - Patch the existing Job to raise `backoffLimit` (e.g. `kubectl patch job s13-failing-job -n kubexplain-eval -p '{"spec":{"backoffLimit":10}}'`).  
4. **If a deadline is the issue**, increase or remove `activeDeadlineSeconds`.  
5. **If scheduling is the issue**, free resources, add nodes, or adjust the pod spec (remove `hostPort`, lower requests).  
6. **Re‑run the Job** and monitor until `status.succeeded` reaches the desired `completions`.

Following this checklist should let you pinpoint why `s13-failing-job` stopped retrying and apply the appropriate fix once you can reach the cluster again.
