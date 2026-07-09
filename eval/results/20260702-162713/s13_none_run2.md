# s13 | mode=none | run=2 | 15.6s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons why a Job ends **Failed** and no new Pods are started  

| # | Likely cause (probability ≈ high → low) | Why it stops retries | How to verify (when you have cluster access) | Typical fix |
|---|----------------------------------------|----------------------|---------------------------------------------|-------------|
| 1 | **Back‑off limit reached** (`spec.backoffLimit`) | The Job controller will create new Pods only while the number of failed attempts is **< backoffLimit**. Once the limit is hit the Job is marked *Failed* and no more Pods are created. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -A3 backoffLimit\nkubectl describe job s13-failing-job -n kubexplain-eval | grep -i \"BackoffLimit\"\n```<br>Check `.status.failed` (should equal the backoff limit) and the `Failed` condition message. | • Raise the limit (`spec.backoffLimit: <bigger‑number>`). <br>• Fix the underlying pod failure so the Job succeeds before hitting the limit. |
| 2 | **Active‑deadline exceeded** (`spec.activeDeadlineSeconds`) | If the total wall‑clock time that the Job has been running exceeds `activeDeadlineSeconds`, the controller aborts the Job and marks it *Failed*; no further Pods are created. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep activeDeadlineSeconds\nkubectl describe job s13-failing-job -n kubexplain-eval | grep -i \"Deadline\"\n``` | • Increase or remove the deadline. <br>• Ensure the workload can finish within the allotted time (e.g., optimise code, give more resources). |
| 3 | **Pod failures that are not retryable** (e.g., `ImagePullBackOff`, `CrashLoopBackOff` with `restartPolicy: Never`) | The Job creates a Pod, the Pod fails immediately, the controller counts it as a failure. If the failure reason is permanent (e.g., image not found) the same failure repeats until the back‑off limit is hit. | ```bash\nkubectl get pods -l job-name=s13-failing-job -n kubexplain-eval\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"Failed\" -A5\nkubectl logs <pod-name> -n kubexplain-eval\n``` | • Fix the container image (correct repository/tag, ensure registry is reachable). <br>• Add an `imagePullPolicy: Always` if you expect updates. <br>• If the container crashes due to code, correct the bug. |
| 4 | **Insufficient resources / scheduling failures** | The Pod is created but never starts because the scheduler cannot find a node that satisfies the pod’s resource requests, node selectors, taints/tolerations, or affinity rules. The Pod stays in `Pending` and eventually counts as a failure (depending on `activeDeadlineSeconds` or back‑off). | ```bash\nkubectl get pods -l job-name=s13-failing-job -n kubexplain-eval -o wide\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"FailedScheduling\"\n``` | • Reduce resource requests or add appropriate node selectors/taints. <br>• Scale the cluster or add nodes that match the requirements. |
| 5 | **Job spec mis‑configuration** (e.g., `completions` set to a value that can never be reached, `parallelism` > 1 with a non‑idempotent command) | The controller may think the Job has already satisfied its `completions` count (or will never be able to) and mark it *Failed* without spawning more Pods. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep -E \"completions|parallelism\"\n``` | • Adjust `completions`/`parallelism` to match the intended workload. <br>• Ensure the command exits with status 0 on success. |
| 6 | **TTL controller removed the Job before you could see it** | If the Job has a `ttlSecondsAfterFinished` and the TTL expired, the Job object (and its Pods) are deleted. You would see the Job in a *Failed* state only briefly. | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml | grep ttlSecondsAfterFinished\n``` | • Increase the TTL or remove it while debugging. |
| 7 | **Cluster‑wide issues** (API server outage, controller‑manager crash) | The Job controller may have stopped processing updates, leaving the Job stuck in *Failed* with no new Pods. | ```bash\nkubectl get componentstatuses\nkubectl logs -n kube-system deployment/kube-controller-manager\n``` | • Resolve the control‑plane problem; once the controller restarts it will respect the Job spec again. |

---

## How to verify each cause (once you regain access)

1. **Inspect the Job object**  
   ```bash
   kubectl get job s13-failing-job -n kubexplain-eval -o yaml
   ```
   * Look for `spec.backoffLimit`, `spec.activeDeadlineSeconds`, `spec.completions`, `spec.parallelism`, `spec.ttlSecondsAfterFinished`.  
   * In the `status` section, note `failed`, `succeeded`, and any `conditions` (e.g., `Failed` with a reason).

2. **Check the Pods created by the Job**  
   ```bash
   kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval
   ```
   * Identify the most recent Pod name(s).  

3. **Describe a failing Pod**  
   ```bash
   kubectl describe pod <pod-name> -n kubexplain-eval
   ```
   * The *Events* section will show reasons such as `FailedScheduling`, `Failed`, `BackOff`, `ImagePullBackOff`, etc.  

4. **Look at container exit codes and logs**  
   ```bash
   kubectl logs <pod-name> -n kubexplain-eval --previous   # if container already terminated
   ```
   * An exit code ≠ 0 indicates a runtime failure; a `0` with no logs may point to a mis‑configured command.  

5. **Check cluster‑wide health (if the problem seems systemic)**  
   ```bash
   kubectl get componentstatuses
   kubectl get pods -n kube-system
   ```

---

## Typical remediation steps

| Issue | Quick fix | Permanent fix |
|-------|-----------|---------------|
| Back‑off limit hit | `kubectl delete job s13-failing-job -n kubexplain-eval && kubectl apply -f job.yaml` (after fixing the pod problem) | Raise `spec.backoffLimit` or set it to a high value; ensure the underlying error is resolved. |
| Active‑deadline exceeded | Increase `spec.activeDeadlineSeconds` or remove it. | Tune the workload (parallelism, resources) so it finishes within the deadline. |
| Image pull / container crash | Correct the image name/tag, ensure registry access, add image pull secret if needed. | Pin a known‑good image version; add health checks to surface failures early. |
| Scheduling failure | Reduce `resources.requests`, adjust node selectors/affinity, add tolerations for taints. | Scale the cluster or use a node pool that matches the pod’s requirements. |
| Mis‑configured completions/parallelism | Set `completions: 1` (or the intended count) and `parallelism` ≤ `completions`. | Review the Job spec against the intended workload; add `restartPolicy: OnFailure` if you need retries per pod. |
| TTL removal too early | Remove `ttlSecondsAfterFinished` while debugging. | Set a longer TTL (e.g., 3600 s) or disable it for critical Jobs. |
| Control‑plane outage | Restart the controller‑manager or fix the API server. | Ensure high‑availability control plane; monitor component health. |

---

### TL;DR checklist for when you can log back in

1. `kubectl describe job s13-failing-job -n kubexplain-eval` → look for **BackoffLimitExceeded** or **DeadlineExceeded** conditions.  
2. If the condition is *BackoffLimitExceeded*: increase `backoffLimit` **or** fix the pod failure that caused the retries.  
3. If the condition is *DeadlineExceeded*: raise or remove `activeDeadlineSeconds`.  
4. If the Job’s Pods show `ImagePullBackOff`, `CrashLoopBackOff`, or `FailedScheduling`: correct image, code, or scheduling constraints.  
5. After the root cause is addressed, delete the failed Job (or `kubectl delete pod --all -l job-name=s13-failing-job`) and re‑create it to let the controller start fresh.  

These steps cover > 90 % of cases where a Kubernetes Job ends in *Failed* and no further Pods are launched. If none of the above explains the situation, gather the full `kubectl get events -n kubexplain-eval` output and the pod logs; that information will point to more exotic issues (e.g., admission‑controller rejections, custom controllers interfering).
