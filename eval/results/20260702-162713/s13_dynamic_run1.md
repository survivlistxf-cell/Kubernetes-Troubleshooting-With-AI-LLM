# s13 | mode=dynamic | run=1 | 15.9s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

## Most likely reasons why a Kubernetes **Job** ends in **Failed** and stops creating new Pods  

| # | Probable cause | Why it leads to a “Failed” Job with no retries | How you can verify it (once you have cluster access) | Typical fix |
|---|----------------|-----------------------------------------------|------------------------------------------------------|-------------|
| 1 | **Back‑off limit reached** – the Job’s `spec.backoffLimit` (default = 6) was exhausted because the Pods kept failing. | Each time a Pod terminates with a non‑zero exit code the Job controller creates a new Pod, up to `backoffLimit`. When the limit is hit the Job status is set to **Failed** and no more Pods are launched. | • Look at `job.status.failed` – it should equal the back‑off limit.<br>• Examine the Job’s **Events**; you’ll see messages like “BackOffLimitExceeded”.<br>• Inspect the failed Pods (status, logs, `reason` such as `ImagePullBackOff`, `CrashLoopBackOff`, `Error`). | • Increase `spec.backoffLimit` if you simply want more retries.<br>• Fix the underlying Pod failure (wrong image, missing command, missing secret, insufficient resources, etc.) so that the Pod can start successfully. |
| 2 | **Active‑deadline exceeded** – the Job’s `spec.activeDeadlineSeconds` elapsed before the required completions were reached. | The controller aborts the Job once the wall‑clock deadline is hit, marking it **Failed** and stopping any further Pod creation. | • Check `job.status.conditions` for a `Failed` condition with `reason: DeadlineExceeded`.<br>• Verify the value of `spec.activeDeadlineSeconds` in the Job manifest. | • Raise the deadline value or remove it if it isn’t needed.<br>• Reduce the work the Job does (e.g., split into smaller Jobs) so it can finish within the deadline. |
| 3 | **Pod template mis‑configuration** – e.g., `restartPolicy` not set to `Never` (Jobs require `Never` or `OnFailure`), missing command/args, or an invalid image reference. | A mis‑configured Pod may start, immediately exit with error, or never be scheduled, causing rapid failures that hit the back‑off limit. | • `kubectl describe job …` → look at the **Pod template** section.<br>• Review the **Events** for messages like “FailedCreate” or “Invalid value: …”.<br>• Check the generated Pods’ `status.reason` (e.g., `InvalidImageName`, `CrashLoopBackOff`). | • Correct the Pod spec: set `restartPolicy: Never`, provide a valid `image`, `command`/`args`, and any required env vars or secrets. |
| 4 | **Insufficient cluster resources / scheduling failures** – no node has enough CPU, memory, or required `hostPort`, `nodeSelector`, `affinity`, `tolerations`, etc. | The scheduler cannot place a new Pod, so the Job never gets a running Pod. After repeated “FailedScheduling” events the back‑off limit is reached and the Job fails. | • Look at the **Events** for the Job and for any Pods that were created – you’ll see `FailedScheduling` with a reason (e.g., `Insufficient cpu`, `node(s) had taints`).<br>• Run `kubectl get nodes` to see node readiness and resource usage. | • Add more nodes or increase node capacity.<br>• Reduce the Pod’s resource requests/limits.<br>• Adjust scheduling constraints (remove unnecessary `nodeSelector`, `affinity`, `hostPort`, etc.). |
| 5 | **Job was manually suspended or TTL‑cleaned** – `spec.suspend: true` or a `ttlSecondsAfterFinished` caused the controller to stop retries. | A suspended Job never creates Pods; a TTL controller may delete the Job (and its Pods) after it finishes, leaving no further retries. | • Check `job.spec.suspend` – if `true`, the Job is paused.<br>• Look for a `ttlSecondsAfterFinished` field and any `Deleted` events. | • Set `spec.suspend: false` to resume.<br>• Remove or increase the TTL if you need the Job to stay alive longer. |
| 6 | **Cluster‑wide control‑plane problems** – API server, scheduler, or kubelet failures that prevented pod creation or status updates. | If the control plane cannot schedule or report pod status, the Job controller may think the back‑off limit has been reached or may never see a successful pod, eventually marking the Job as Failed. | • Examine cluster‑wide events (`kubectl get events --all-namespaces`).<br>• Check the health of control‑plane components (apiserver, scheduler, controller‑manager) in the cluster‑info dump. | • Resolve the underlying control‑plane issue (restart components, fix networking, restore etcd, etc.). |
| 7 | **Finalizers or admission webhooks blocking deletion** – a finalizer on the Job or a mutating/validating webhook that rejects updates. | The Job may stay in a terminating state, and the controller may give up on creating new Pods, eventually marking it Failed. | • Look for `finalizers` in `job.metadata.finalizers`.<br>• Review webhook configurations that target `jobs` resources. | • Remove the problematic finalizer or adjust the webhook to allow the update. |

---

### How to verify the root cause (once you can reach the cluster)

1. **Inspect the Job object**  
   * Look at `status.conditions`, `status.failed`, `status.succeeded`, and `spec.backoffLimit`.  
   * Check for a `Failed` condition with reasons such as `BackoffLimitExceeded` or `DeadlineExceeded`.

2. **Review Job events**  
   * Events give the exact reason why the controller stopped creating Pods (e.g., “BackOffLimitExceeded”, “FailedCreate”, “FailedScheduling”).

3. **Examine the Pods that were created**  
   * For each failed Pod, note `status.reason` and `status.message`.  
   * Pull the pod logs (if any) to see application‑level errors.

4. **Check scheduling constraints**  
   * Verify node readiness, resource availability, and any taints/tolerations or affinity rules that might block placement.

5. **Confirm Job spec fields**  
   * `backoffLimit`, `activeDeadlineSeconds`, `restartPolicy`, `suspend`, `ttlSecondsAfterFinished`, resource requests/limits.

6. **Look for cluster‑wide issues**  
   * Control‑plane component health, network partitions, or API‑server errors that could affect scheduling.

---

### General remediation checklist

1. **If back‑off limit is the culprit**  
   * Fix the underlying pod failure (image, command, secrets, resources).  
   * Optionally raise `backoffLimit` if you need more retries.

2. **If deadline exceeded**  
   * Increase `activeDeadlineSeconds` or remove it.  
   * Optimize the job workload to finish faster.

3. **If pod spec is wrong**  
   * Set `restartPolicy: Never`.  
   * Provide a valid image and correct command/args.  
   * Add any missing env vars, ConfigMaps, or Secrets.

4. **If scheduling fails**  
   * Add capacity (more nodes or larger nodes).  
   * Reduce resource requests or relax node selectors/affinities.  
   * Remove unnecessary `hostPort` bindings.

5. **If the job is suspended or TTL‑cleaned**  
   * Set `spec.suspend: false`.  
   * Remove or increase `ttlSecondsAfterFinished`.

6. **If control‑plane problems exist**  
   * Restart or repair the affected component(s).  
   * Ensure network connectivity between nodes and the API server.

7. **If finalizers/webhooks block progress**  
   * Delete or patch out the finalizer.  
   * Adjust the webhook to allow the Job update.

---

### Quick “first‑look” steps (when you regain access)

1. `kubectl get job s13-failing-job -n kubexplain-eval -o yaml` – glance at `status` and `spec`.  
2. `kubectl describe job s13-failing-job -n kubexplain-eval` – read the **Events** section.  
3. List the Pods that belong to the Job (`kubectl get pods -l job-name=s13-failing-job -n kubexplain-eval`).  
4. For each failed Pod, `kubectl describe pod <pod>` and `kubectl logs <pod>` to see why it exited.  

Following the verification steps above will let you pinpoint which of the listed causes is responsible, and the corresponding fix will get the Job back to a successful run.
