# s13 | mode=static | run=1 | 16.2s | needs_search=0

**Q:** My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state and no pods are retrying anymore. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit

---

**Most likely reasons why a Kubernetes Job ends in **`Failed`** and stops creating new Pods**

| # | Probable cause | Why it makes the Job stop | How you would verify it (when you can reach the cluster) | How to fix it |
|---|----------------|---------------------------|----------------------------------------------------------|---------------|
| 1 | **Back‑off limit reached** (`spec.backoffLimit`) | Jobs use a *restartPolicy: Never*. Each time a Pod finishes with a non‑zero exit code the Job controller counts a failure. When the number of failures equals `backoffLimit` (default = 6) the Job is marked **Failed** and no more Pods are created. | ```bash\nkubectl describe job s13-failing-job -n kubexplain-eval\n``` Look for `Failed` count in the status section and a condition like `BackoffLimitExceeded`. | • Increase the limit (`spec.backoffLimit: <higher>`). <br>• Fix the underlying pod failure (see rows 2‑4). <br>• Re‑apply the Job after the change. |
| 2 | **Active‑deadline exceeded** (`spec.activeDeadlineSeconds`) | If the total wall‑clock time that the Job has been running exceeds `activeDeadlineSeconds`, the controller marks the Job **Failed** with reason `DeadlineExceeded` and stops retries. | ```bash\nkubectl describe job s13-failing-job -n kubexplain-eval\n``` Look for a condition `type: Failed` `reason: DeadlineExceeded`. | • Raise or remove `activeDeadlineSeconds`. <br>• Optimize the workload so it finishes sooner. |
| 3 | **Container exits with a non‑zero code (application error, bad command, missing binary, etc.)** | Each Pod that finishes with an error counts toward the back‑off limit. If the container never starts successfully, the Job will quickly hit the limit. | ```bash\nkubectl get pods -n kubexplain-eval -l job-name=s13-failing-job -o name\nkubectl logs <pod> -n kubexplain-eval\n``` Examine the exit code and the log output. | • Correct the image name / tag. <br>• Fix the command/args in the pod template. <br>• Add needed binaries or change the base image. |
| 4 | **Image‑pull failures** (wrong registry, missing credentials, rate‑limit) | An image‑pull error puts the Pod into `ImagePullBackOff`. Each failed attempt is counted as a failure; after the back‑off limit the Job stops. | ```bash\nkubectl describe pod <failed-pod> -n kubexplain-eval\n``` Check the `Events` section for `Failed to pull image` or `ImagePullBackOff`. | • Verify the image name and tag. <br>• Ensure the node can reach the registry (network, proxy). <br>• Create or update an `ImagePullSecret` and reference it in the Job. |
| 5 | **Scheduling / resource constraints** (insufficient CPU/memory, node selector, taints, hostPort) | If a Pod cannot be scheduled, it stays `Pending`. Each unschedulable attempt still counts as a failure for a Job, and after the back‑off limit the Job is marked Failed. | ```bash\nkubectl describe pod <pending-pod> -n kubexplain-eval\n``` Look for events like `FailedScheduling` with reasons such as `Insufficient cpu`, `node(s) had taints`, or `hostPort` conflicts. | • Reduce the pod’s resource requests/limits. <br>• Add or adjust node selectors / tolerations. <br>• Remove unnecessary `hostPort` usage. |
| 6 | **Job spec mis‑configuration** (wrong `completions`/`parallelism`, missing `restartPolicy`) | If the Job never reaches the required number of successful completions, the controller may eventually give up (especially when combined with a low `backoffLimit`). | ```bash\nkubectl get job s13-failing-job -n kubexplain-eval -o yaml\n``` Verify `spec.completions`, `spec.parallelism`, and that `restartPolicy` is `Never`. | • Adjust `completions`/`parallelism` to match the intended workload. <br>• Ensure `restartPolicy: Never` (required for Jobs). |
| 7 | **Cluster‑wide problems** (API‑server down, controller‑manager unavailable, node failure) | If the control plane cannot reconcile the Job, it may be left in a `Failed` state without further retries. This is rarer but possible in a disrupted cluster. | ```bash\nkubectl get componentstatuses\nkubectl get nodes\n``` Look for `NotReady` nodes or unhealthy control‑plane components. | • Restore the affected control‑plane component or node. <br>• Re‑create the Job after the cluster is healthy. |

---

### How to verify the root cause (once you regain access)

1. **Inspect the Job object**  
   ```bash
   kubectl describe job s13-failing-job -n kubexplain-eval
   ```
   *Key things to read*: `Status.Failed`, `BackoffLimitExceeded` condition, `DeadlineExceeded` condition, `spec.backoffLimit`, `spec.activeDeadlineSeconds`.

2. **Look at the Pods that the Job created**  
   ```bash
   kubectl get pods -n kubexplain-eval -l job-name=s13-failing-job
   ```
   Pick the most recent pod (usually the one with the highest suffix) and:

   - **Describe the pod** for events (image pull, scheduling, termination reason).  
   - **Fetch the logs** to see the application error or crash.  

3. **Check cluster‑wide events** (optional, helps with scheduling or node problems)  
   ```bash
   kubectl get events -n kubexplain-eval --sort-by=.metadata.creationTimestamp
   ```

4. **Validate resource availability** (if scheduling looks suspicious)  
   ```bash
   kubectl describe node <node-name>
   ```

---

### Typical remediation workflow

1. **If the failure is due to back‑off limit** – either raise `backoffLimit` **or** fix the underlying pod error (steps 3‑5).  
2. **If the deadline was hit** – increase `activeDeadlineSeconds` or make the job faster (e.g., parallelize work).  
3. **If the container exits badly** – correct the command, image, or code; test locally with `docker run` before re‑applying.  
4. **If image‑pull fails** – verify registry access, credentials, and image name; create/update an `ImagePullSecret`.  
5. **If scheduling fails** – adjust resource requests, add appropriate tolerations/affinities, or free up a node.  
6. **If the Job spec is wrong** – edit the manifest to set the correct `completions`/`parallelism` and re‑apply.  
7. **If the cluster is unhealthy** – bring the control‑plane or nodes back online, then re‑run the Job.

---

**Bottom line:** In most clusters the *first* thing to check is whether the Job has simply exhausted its `backoffLimit` because the Pods keep failing (often due to a bad image, command, or runtime error). Once you confirm that, focus on fixing the pod‑level problem; if the pod itself is fine, then look at deadline, scheduling, or cluster health.
