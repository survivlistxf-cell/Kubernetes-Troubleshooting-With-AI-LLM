# s11 | mode=none | run=2 | 12.7s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most Likely reasons you cannot create the pod  

| # | Likely cause | Why it is the top candidate | How to verify (when you have access) | How to fix (when you have access) |
|---|--------------|----------------------------|--------------------------------------|-----------------------------------|
| 1 | **PodSecurityAdmission (PSA) violation** | The pod name contains *psa‑violator* and the namespace is called *kubexplain‑eval‑psa*. That strongly hints the cluster is deliberately testing PSA enforcement. | • Look at the namespace’s `pod-security.kubernetes.io/enforce` (or `audit`/`warn`) label/annotation. <br>• Check the API‑server audit or admission‑controller logs for a message like *“denied by pod security admission”*. <br>• The error returned by `kubectl` will usually contain “PodSecurityPolicy” or “PodSecurity” in the message. | • Adjust the pod spec so it complies with the enforced level (e.g., add a non‑root user, drop privileged flag, set a read‑only root filesystem, avoid hostPath, etc.). <br>• If you control the namespace, relax the PSA level (e.g., change the label to `baseline` or `privileged`). <br>• Alternatively, create the pod in a different namespace that is not subject to the restrictive PSA. |
| 2 | **Namespace‑level ResourceQuota exceeded** | Many test clusters put a quota on the evaluation namespace to keep resource usage low. If the quota is already full, any new pod creation is rejected. | • Run `kubectl get quota -n kubexplain-eval-psa` and compare `used` vs `hard`. <br>• The error message will contain “exceeded quota”. | • Delete or scale down other workloads in the namespace. <br>• Request a higher quota from the cluster admin. |
| 3 | **LimitRange preventing the pod’s resource requests/limits** | A `LimitRange` can reject pods that do not specify required CPU/memory limits or that request values outside the allowed range. | • Inspect the `LimitRange` objects in the namespace (`kubectl get limitrange -n …`). <br>• The error will mention “must specify limits” or “exceeds limit range”. | • Add appropriate `resources.requests` and `resources.limits` to the pod spec, staying within the allowed min/max. |
| 4 | **Missing or disabled ServiceAccount** | If the pod spec references a ServiceAccount that does not exist (or is disabled by a policy), creation fails. | • Check the pod spec’s `serviceAccountName`. <br>• Verify the ServiceAccount exists (`kubectl get sa <name> -n …`). <br>• Look for an error like “serviceaccount … not found”. | • Create the missing ServiceAccount, or remove the reference. |
| 5 | **Image pull secret / registry access problem** | When the image is in a private registry and the pod does not have a valid pull secret, the API may reject creation (especially if `imagePullPolicy=IfNotPresent`). | • Examine the pod spec for `imagePullSecrets`. <br>• Review events for the pod creation attempt; they often say “failed to resolve image …”. | • Add a correct `imagePullSecret` to the pod (or to the default ServiceAccount). <br>• Ensure the secret contains valid credentials for the registry. |
| 6 | **Node selector / taints & tolerations mismatch** | If the pod specifies a `nodeSelector` or `affinity` that cannot be satisfied, the scheduler may still allow creation, but some clusters have admission controllers that reject unschedulable pods. | • Look at `nodeSelector`, `nodeAffinity`, and `tolerations` in the pod spec. <br>• Check the node pool for matching labels/taints. | • Adjust the selector/affinity to match available nodes, or add appropriate tolerations. |
| 7 | **Mutating/Validating Admission Webhook rejection** | Clusters often run OPA Gatekeeper, Kyverno, or custom webhooks that enforce policies (e.g., “no privileged containers”). The webhook will return a clear denial message. | • Review the error returned by `kubectl`; it will contain the webhook name and reason. <br>• Look at the webhook configuration (`kubectl get validatingwebhookconfiguration`). | • Modify the pod to satisfy the webhook rule, or request an exemption/adjust the policy if you have authority. |
| 8 | **Namespace is terminating or being deleted** | A namespace stuck in `Terminating` will reject new resource creation. | • `kubectl get namespace kubexplain-eval-psa -o yaml` will show `status.phase: Terminating`. | • Wait for the namespace deletion to finish, or clean up finalizers if you have admin rights. |
| 9 | **RBAC denial (lack of create permission)** | If your user/service‑account does not have `create` permission on pods in that namespace, the API returns a *Forbidden* error. | • The error will say “User … cannot create pods in namespace …”. | • Ask a cluster admin to grant the appropriate `Role`/`ClusterRole` binding (e.g., `rolebinding` with `pods/create`). |
| 10 | **Pod name conflict with an existing resource** | A pod with the same name already exists (perhaps in a different phase) and the API rejects a duplicate. | • `kubectl get pod s11-psa-violator -n kubexplain-eval-psa`. <br>• Error will mention “already exists”. | • Delete or rename the existing pod, or choose a different name. |

---

### How to verify each cause (once you regain cluster access)

1. **Check the exact error message** – `kubectl apply -f …` or `kubectl create pod …` will return a concise reason (e.g., *“denied by pod security admission”*). That usually points directly to the root cause.  
2. **Inspect namespace labels/annotations** – they reveal PSA mode, quotas, and limit ranges.  
3. **List quota and limitrange objects** – `kubectl get quota,limitrange -n …`.  
4. **Review ServiceAccount and imagePullSecret existence** – `kubectl get sa,secret -n …`.  
5. **Look at admission‑controller logs** (if you have cluster‑admin rights) – they contain the webhook name and policy that rejected the pod.  
6. **Examine events for the pod creation attempt** – `kubectl get events -n … --field-selector involvedObject.kind=Pod,involvedObject.name=s11-psa-violator`.  

---

### General remediation steps (when you can run commands)

- **If PSA is the blocker**: either make the pod conform to the enforced level or relax the namespace’s PSA label (`pod-security.kubernetes.io/enforce=privileged` or `baseline`).  
- **If quota/limitrange is the blocker**: free up resources, delete unused pods, or request a higher quota.  
- **If ServiceAccount or image pull secret is missing**: create the missing objects or correct the pod spec.  
- **If a webhook is rejecting the pod**: adjust the pod to satisfy the policy or coordinate with the policy owner for an exemption.  
- **If RBAC is the issue**: obtain the necessary `pods/create` permission via a RoleBinding.  

By checking the error message first, you can quickly narrow the list to the single actual cause and apply the corresponding fix. If the message is ambiguous, walk through the list in the order above – PSA violations are by far the most common reason in a namespace explicitly named for PSA testing.
