# s11 | mode=dynamic | run=3 | 17.3s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons you can’t create the pod **s11‑psa‑violator** in namespace **kubexplain‑eval‑psa**

| # | Likely cause | Why it’s the most common culprit | How you would verify it (when you have cluster access) | How to fix it |
|---|--------------|----------------------------------|--------------------------------------------------------|---------------|
| 1 | **PodSecurityAdmission (PSA) violation** – the pod’s spec breaks the namespace’s PSA level (e.g., it runs privileged, uses hostPath, allows privilege escalation, or is missing required labels). | The pod name itself contains “psa‑violator”, which strongly suggests the failure is intentional to demonstrate a PSA block. PSA is now the default admission controller for most clusters, so a mismatch is the first thing the API server will reject. | • Look at the namespace’s PSA mode (`restricted`, `baseline`, or `privileged`). <br>• Examine the pod’s `securityContext` and any volume types. <br>• Check the API‑server admission‑controller logs for a message like *“pod violates PodSecurity “restricted” policy”*. | • Adjust the pod spec to meet the required PSA level (remove privileged flag, drop hostPath, add required labels, set `allowPrivilegeEscalation: false`, etc.). <br>• If the workload truly needs higher privileges, raise the namespace’s PSA level to `baseline` or `privileged` (or create a separate namespace with a looser PSA). |
| 2 | **Insufficient RBAC permission** – the user or service‑account you are using does not have `create` rights on pods in that namespace. | Even if the pod spec is valid, the API server will reject the request with a *Forbidden* error before any admission checks. This is a common “cannot create pod” symptom. | • Run `kubectl auth can-i create pod -n kubexplain-eval-psa` (or check the `RoleBinding`/`ClusterRoleBinding` that grants the permission). <br>• Look for a *Forbidden* response in the API‑server audit log. | • Grant the appropriate `Role`/`ClusterRole` (e.g., `edit` or a custom role with `pods/create`) and bind it to the user/service‑account. |
| 3 | **Namespace‑level ResourceQuota exceeded** – the namespace has a quota that is already full (CPU, memory, pod count, etc.). | When a quota is exhausted the API server returns a *Forbidden* error mentioning the quota name. This is a frequent blocker in evaluation or demo namespaces. | • Inspect the `ResourceQuota` objects in the namespace and compare their `used` vs. `hard` values. <br>• Look for an error like *“exceeded quota: pods, requested: 1, used: 10, limited: 10”*. | • Delete unused resources, increase the quota (if you have permission), or create the pod in a different namespace without that quota. |
| 4 | **LimitRange restrictions** – the pod’s resource requests/limits fall outside the allowed range for the namespace. | A `LimitRange` can reject a pod that asks for too much or too little CPU/memory, or that omits required limits. | • Review the `LimitRange` objects in the namespace and see the min/max/default values. <br>• Check the pod spec for `resources.requests`/`resources.limits` that violate those bounds. | • Adjust the pod’s resource requests/limits to fit within the defined range, or modify the `LimitRange` (if you control it). |
| 5 | **Admission webhook rejection** – a validating webhook (e.g., a policy engine, OPA/Gatekeeper, custom security check) denies the pod. | Many clusters install additional policy enforcement via webhooks; they can reject pods for naming conventions, image registries, required annotations, etc. | • Look at the API‑server response; it will contain a message from the webhook (e.g., *“denied by gatekeeper: disallowed image”*). <br>• Check the webhook configuration (`ValidatingWebhookConfiguration`) for rules that match the pod. | • Modify the pod to satisfy the webhook’s policy (e.g., use an allowed image, add required annotations). <br>• If the webhook is mis‑configured, adjust its policy or temporarily disable it (requires admin rights). |
| 6 | **Node selector / affinity mismatch** – the pod specifies a node selector, node affinity, or tolerations that no node satisfies. | The scheduler will reject the pod creation with a *FailedScheduling* event if the pod cannot be placed on any node. | • Examine the pod’s `nodeSelector`, `affinity`, and `tolerations`. <br>• Verify that at least one node in the cluster has matching labels and is not tainted in a way that blocks the pod. | • Remove or relax the selector/affinity, add matching labels to a node, or add appropriate tolerations. |
| 7 | **Insufficient cluster resources** – the cluster simply does not have enough free CPU/memory to schedule the pod. | Even with a valid spec, the scheduler may reject the pod if no node can satisfy its resource request. | • Check the cluster’s node capacity vs. current usage (e.g., `kubectl top nodes`). <br>• Look for a *FailedScheduling* event mentioning *“Insufficient cpu”* or *“Insufficient memory”*. | • Reduce the pod’s resource requests, add more nodes, or free up resources by deleting other workloads. |
| 8 | **Image pull secret / registry access problem** – the pod references a private image and the namespace lacks a valid `imagePullSecret`. | The pod will enter `ImagePullBackOff` after creation, but the API server still allows creation. However, some admission controllers reject pods that reference images they cannot verify. | • Verify that the image name is correct and that a valid `imagePullSecret` exists in the namespace. <br>• Check the pod events for *“Failed to pull image”*. | • Create or update the `imagePullSecret`, or use a publicly accessible image. |
| 9 | **PodSecurityPolicy (PSP) still enabled** – the cluster uses the older PSP mechanism and the pod violates a PSP. | Some clusters still have PSP enabled alongside PSA; a PSP can block privileged pods. | • List the PSPs bound to the service account and see which one applies. <br>• Look for a *“failed pod security admission”* message referencing a PSP. | • Adjust the pod to satisfy the PSP, or modify the PSP/role binding to allow the needed capabilities. |
| 10 | **Namespace is terminating or being deleted** – the namespace is in a `Terminating` state, so new resources cannot be created. | A terminating namespace blocks creation of any new objects. | • Check the namespace status (`kubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'`). | • Wait for the namespace to finish deletion, or recreate it if it was unintentionally terminated. |

---

### How to verify each cause (once you regain access)

1. **PSA violation** – Examine the namespace’s PSA mode (`kubectl get namespace kubexplain-eval-psa -o yaml`) and the pod’s security settings. Look for API‑server error messages that mention “PodSecurity” or “restricted”.  
2. **RBAC** – Use `kubectl auth can-i create pod -n kubexplain-eval-psa` for the current user/service‑account. Review `RoleBinding`/`ClusterRoleBinding` objects.  
3. **ResourceQuota** – List `ResourceQuota` objects in the namespace and compare `used` vs. `hard`.  
4. **LimitRange** – List `LimitRange` objects and check the pod’s `resources` fields against the defined min/max.  
5. **Admission webhook** – Inspect the error returned by the API server; it will include the webhook name and reason. Review the corresponding `ValidatingWebhookConfiguration`.  
6. **Node selector / affinity** – Look at the pod spec’s `nodeSelector`, `affinity`, and `tolerations`. Verify node labels and taints.  
7. **Cluster resources** – Check node capacity and current usage (e.g., via metrics server) to see if the pod’s requests can be satisfied.  
8. **Image pull** – Confirm the image name and that a valid `imagePullSecret` exists in the namespace.  
9. **PSP** – If PSPs are still enabled, list the PSPs bound to the service account and see which one applies.  
10. **Namespace state** – Verify the namespace’s `status.phase` is `Active` and not `Terminating`.

---

### General remediation workflow (when you can run commands)

1. **Identify the exact error** – The API server’s response when you try to create the pod will contain the most specific reason (PSA, RBAC, quota, etc.).  
2. **Address the specific blocker** – Follow the “How to fix it” column for the matching cause.  
3. **Retry pod creation** – After the fix, attempt to create the pod again.  
4. **Confirm success** – Ensure the pod reaches `Running` and that no further admission or scheduling events appear.

By checking the items in the order above, you’ll most likely pinpoint the root cause quickly—especially given the pod’s name suggests a deliberate PSA test. Once you have cluster access, the verification steps will confirm which of these policies is actually blocking the creation, and the corresponding fix will get the pod running.
