# s11 | mode=dynamic | run=2 | 15.5s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons why the pod **s11‑psa‑violator** cannot be created in the namespace **kubexplain‑eval‑psa**

| # | Likely cause (ordered by probability) | How to verify when you regain access | How to fix |
|---|----------------------------------------|--------------------------------------|------------|
| 1 | **PodSecurityAdmission (PSA) policy violation** – the pod’s spec (e.g., privileged flag, hostPath, `runAsUser: 0`, disallowed seccomp profile, etc.) does not satisfy the PSA level that the namespace is enforced at (e.g., `restricted`). | • Look at the **AdmissionReview** error returned by the API server when you attempted the create (it will contain a message like “pod violates PodSecurity “restricted” policy”). <br>• Run `kubectl get namespace kubexplain-eval-psa -o yaml` and check the `metadata.annotations` for `pod-security.kubernetes.io/enforce` (or `audit`/`warn`). <br>• Run `kubectl describe pod s11-psa-violator` (if it exists) or `kubectl get events -n kubexplain-eval-psa` to see the PSA‑related event. | • Modify the pod spec so it complies with the enforced level (remove privileged escalation, drop `hostPath` volumes, set a non‑root user, use an allowed seccomp profile, etc.). <br>• If the workload truly needs higher privileges, request the namespace be set to a less‑strict level (`baseline` or `privileged`) by adding/updating the PSA annotations on the namespace. |
| 2 | **Namespace‑level ResourceQuota or LimitRange exceeded** – the namespace may have a quota on CPU, memory, number of pods, or on specific resource types, and the new pod would push the usage over the limit. | • Inspect the `ResourceQuota` objects in the namespace (`kubectl get resourcequota -n kubexplain-eval-psa -o yaml`). <br>• Check the current usage vs. hard limits shown in the quota status. <br>• Look at any `LimitRange` that could be rejecting the pod because of missing or out‑of‑range resource requests/limits. | • Reduce the pod’s resource requests/limits so they fit within the quota. <br>• Delete or scale down other pods to free quota. <br>• If the workload legitimately needs more resources, request an increase to the quota from the cluster admin. |
| 3 | **RBAC denial** – the user or service account you are using does not have permission to create pods in that namespace. | • After you can access the cluster, run an `auth can-i create pod -n kubexplain-eval-psa` check for the relevant identity. <br>• Review the `RoleBinding`/`ClusterRoleBinding` objects that grant pod‑creation rights in the namespace. | • Add or update a `Role`/`ClusterRole` that allows `create` on `pods` and bind it to the user/service‑account via a `RoleBinding`. |
| 4 | **Admission webhook rejection** – a validating webhook (e.g., a policy engine, OPA/Gatekeeper, custom security webhook) may be rejecting the pod for reasons unrelated to PSA (e.g., disallowed image registry, missing required labels, custom security checks). | • Examine the error message returned by the API server; it will usually contain the webhook name and a reason. <br>• List the `ValidatingWebhookConfiguration` objects in the cluster and identify any that target `pods` in the namespace. <br>• Check the webhook logs (if you have cluster‑admin access) for the specific rejection. | • Adjust the pod spec to satisfy the webhook’s policy (add required labels, use an allowed image, etc.). <br>• If the webhook is mis‑configured, work with the webhook owner to relax or correct the rule. |
| 5 | **PodSecurityPolicy (PSP) still in effect** – on clusters that still have the legacy PSP controller enabled, a PSP may be denying the pod (e.g., requiring a specific `fsGroup`, disallowing `hostNetwork`, etc.). | • List the `PodSecurityPolicy` objects and see which ones are bound to the service account via `ClusterRole`/`RoleBinding`. <br>• Look for a “Forbidden” error mentioning a PSP name in the API response. | • Update the PSP to allow the required capabilities, or bind the pod’s service account to a more permissive PSP. <br>• Prefer migrating to PSA and disabling PSP if possible. |
| 6 | **Namespace is in a terminating state or has a finalizer** – a namespace that is being deleted or has a stuck finalizer can reject new resources. | • Check the namespace status (`kubectl get namespace kubexplain-eval-psa -o yaml`). <br>• Look for `status.phase: Terminating` or any `finalizers` listed. | • If the namespace is stuck, remove the offending finalizer (after confirming it is safe) or wait for the deletion to complete. |
| 7 | **Node‑selector / affinity constraints** – the pod may specify a node selector, affinity, or tolerations that cannot be satisfied by any node, causing the API server to reject the creation (rare, but possible with certain admission controllers). | • Review the pod’s `nodeSelector`, `affinity`, and `tolerations`. <br>• Verify that at least one node in the cluster matches those constraints (`kubectl get nodes -o wide`). | • Adjust the selector/affinity to match available nodes, or add appropriate labels/tolerations to nodes. |
| 8 | **Image pull secret / registry access issue** – if the pod references a private image and the namespace lacks a valid `imagePullSecret`, some admission controllers (e.g., image‑policy webhook) may reject the pod upfront. | • Look for an error mentioning “failed to resolve image” or “image pull secret not found”. <br>• Verify the presence of the secret in the namespace. | • Create or update the `imagePullSecret` and reference it in the pod spec, or ensure the image is publicly accessible. |

### General verification workflow (when you can reconnect)

1. **Capture the exact error** – the API server returns a status object with a `message` field that usually tells you which admission controller rejected the pod.  
2. **Check namespace annotations** – PSA level is set via `pod-security.kubernetes.io/enforce`, `audit`, and `warn`.  
3. **Inspect quota/limit objects** – `ResourceQuota` and `LimitRange` can be the silent blockers.  
4. **Validate RBAC** – `kubectl auth can-i` for the identity you are using.  
5. **Review admission webhooks** – list `ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration` that target pods.  
6. **Look at events** – `kubectl get events -n kubexplain-eval-psa` will show the most recent denial events with timestamps and reasons.  
7. **Examine PSP (if present)** – legacy PSPs still block pod creation on some clusters.  

### Fix‑by‑example summary

- **PSA violation** → make the pod non‑privileged or lower the namespace’s PSA level.  
- **Quota exceeded** → reduce pod resources or request a higher quota.  
- **RBAC** → grant `create` on `pods` to the appropriate user/service‑account.  
- **Webhook** → adjust pod to meet the webhook’s policy or modify the webhook rule.  
- **PSP** → bind a permissive PSP or migrate to PSA.  
- **Namespace terminating** → clear finalizers or wait for deletion.  
- **Node constraints** → align selectors/affinity with actual node labels.  
- **Image pull secret** → add a valid secret or use a public image.

By following the verification steps in the order above, you’ll quickly pinpoint which of these common blockers is responsible for the failure and apply the corresponding remediation.
