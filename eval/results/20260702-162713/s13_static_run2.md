# s13 | mode=static | run=2 | 15.1s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons why the Job stopped retrying

| # | Reason (probability ≈ high → low) | Why it stops the Job | How you can verify it (when you regain access) | How to fix it |
|---|-----------------------------------|----------------------|-----------------------------------------------|---------------|
| 1 | **Back‑off limit reached** (default = 6) | A Job counts each pod that terminates with a non‑zero exit code. When `.spec.backoffLimit` failures are recorded, the controller marks the Job **Failed** and stops creating new pods. | • `kubectl get job s13-failing-job -n kubexplain-eval -o yaml` → look at `.status.failed` and `.spec.backoffLimit`.<br>• `kubectl describe job s13-failing-job` → the “Failed” condition will show “BackoffLimitExceeded”. | • Raise the limit (`backoffLimit: <higher>`).<br>• Or make the pod succeed (fix the underlying error). |
| 2 | **Active‑deadline exceeded** (`activeDeadlineSeconds`) | If the Job runs longer than the deadline, the controller marks it **Failed** regardless of pod state and stops retries. | • Check `.spec.activeDeadlineSeconds` in the Job manifest.<br>• In the Job description you’ll see a condition “DeadlineExceeded”. | • Increase or remove `activeDeadlineSeconds`.<br>• Reduce the work the Job does (e.g., split into smaller Jobs). |
| 3 | **Pod crashes immediately (e.g., bad image, command error, missing entrypoint)** | The pod exits with a non‑zero code on start‑up, counting as a failure. After a few attempts the back‑off limit is hit. | • `kubectl describe pod <pod‑name>` → look for `State: Terminated` with `Reason: Error` or `CrashLoopBackOff`.<br>• `kubectl logs <pod‑name>` to see the container’s output. | • Verify the image name/tag exists and is reachable.<br>• Ensure the command/args are correct.<br>• Add a proper `restartPolicy: OnFailure` (default for Jobs) if you changed it. |
| 4 | **Scheduling failures (insufficient resources, node selectors, taints, hostPort, etc.)** | The pod stays in **Pending** and eventually the Job controller counts it as a failure (e.g., after `failed` count increments due to `FailedScheduling`). | • `kubectl describe pod <pod‑name>` → look for events like “FailedScheduling”.<br>• `kubectl get events -n kubexplain-eval --sort-by=.metadata.creationTimestamp` for recent scheduling errors. | • Adjust resource requests/limits to fit available nodes.<br>• Remove or relax node selectors/affinity or taint tolerations.<br>• If you used `hostPort`, consider using a Service instead. |
| 5 | **Cluster‑wide problems (node down, API server unreachable, kubelet issues)** | The Job controller cannot create pods or the kubelet cannot start them, leading to repeated failures until the back‑off limit is hit. | • Check node status: `kubectl get nodes` → any `NotReady`?<br>• Look at controller manager / scheduler logs for errors creating pods.<br>• Verify the API server is reachable (`kubectl cluster-info`). | • Bring affected nodes back online or fix kubelet/scheduler issues.<br>• If the control‑plane is down, restore it before expecting the Job to continue. |
| 6 | **Incorrect `restartPolicy` (e.g., `Never`)** | Jobs require `restartPolicy: OnFailure`. With `Never`, a pod that exits (even successfully) is not restarted, and the Job may be considered finished or failed. | • Inspect the pod spec (`.spec.restartPolicy`). | • Change the policy to `OnFailure` (or omit it, as `OnFailure` is the default for Jobs). |
| 7 | **TTL after finished** (TTLSecondsAfterFinished) | When the Job finishes (Succeeded or Failed), the TTL controller may delete the Job and its pods quickly, giving the impression that no retries are happening. | • Look for `.spec.ttlSecondsAfterFinished` in the Job manifest.<br>• `kubectl get job s13-failing-job -o yaml` after failure – the object may already be gone. | • Remove or increase the TTL if you need to keep the Job around for debugging. |

---

## How to verify each cause (once you can reach the cluster)

1. **Job status & conditions** – `kubectl get job … -o yaml` or `kubectl describe job …`.  
   - Check `.status.failed`, `.status.conditions` (type = Failed, Reason = BackoffLimitExceeded, DeadlineExceeded, etc.).

2. **Pod events & logs** – `kubectl describe pod <pod>` and `kubectl logs <pod>`.  
   - Look for `CrashLoopBackOff`, `ImagePullBackOff`, `FailedScheduling`, or termination reasons.

3. **Node health** – `kubectl get nodes` and `kubectl describe node <node>`.  
   - Any `NotReady` or resource pressure warnings?

4. **Cluster‑wide component logs** – `kubectl -n kube-system logs deployment/kube-scheduler`, `kube-controller-manager`, etc., if you have access to the control‑plane nodes.

5. **Resource quotas / limit ranges** – `kubectl get quota` and `kubectl get limitrange` in the namespace; they can cause pod creation to be rejected.

---

## Quick remediation checklist (apply when you regain access)

1. **If back‑off limit is the blocker**  
   - Edit the Job (`kubectl edit job s13-failing-job`) and raise `backoffLimit`.  
   - Or delete the failed Job and recreate it with a higher limit after fixing the underlying error.

2. **If deadline is the blocker**  
   - Increase or remove `activeDeadlineSeconds` in the Job spec.

3. **If the container image/command is wrong**  
   - Verify the image exists (`docker pull …` locally).  
   - Correct the `image`, `command`, or `args` fields.  
   - Re‑apply the Job.

4. **If scheduling is the issue**  
   - Reduce resource requests or add more nodes.  
   - Adjust or remove node selectors/affinities/taints.  
   - Avoid `hostPort` unless necessary.

5. **If nodes or control‑plane are unhealthy**  
   - Restart the affected node(s) or kubelet service.  
   - Restore the API server / scheduler if they are down.

6. **If restartPolicy is wrong**  
   - Set `restartPolicy: OnFailure` (or omit it) in the pod template.

7. **If TTL is deleting the Job too fast**  
   - Remove `ttlSecondsAfterFinished` or set it to a larger value to keep the Job for post‑mortem analysis.

---

### Bottom line

The most common reason a Job stops retrying is that it has **exhausted its back‑off limit**. Verify the Job’s `.status.failed` count against `.spec.backoffLimit` first. If that isn’t the case, walk through the list above in order—deadline, pod‑level failures, scheduling problems, cluster health, restart policy, and TTL—checking the corresponding fields and events. Fix the root cause, then either increase the relevant limits or recreate the Job with corrected specifications.
