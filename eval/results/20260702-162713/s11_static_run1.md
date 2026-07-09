# s11 | mode=static | run=1 | 19.9s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons you can’t create **`s11-psa-violator`** in the **`kubexplain-eval-psa`** namespace  

| # | Likely cause (ordered by probability) | Why it blocks pod creation | How to verify (when you regain access) | How to fix |
|---|---------------------------------------|----------------------------|----------------------------------------|------------|
| 1 | **Pod‑Security‑Admission (PSA) policy violation** – the namespace is set to a restrictive level (`restricted` or `baseline`) and the pod spec breaks one of the required rules (e.g., runs as root, uses a privileged container, mounts hostPath, sets `hostNetwork`, `hostPID`, `allowPrivilegeEscalation`, etc.) | PSA is an **admission controller**; it rejects the `CREATE` request before the pod object is persisted, returning a message such as `pod "s11-psa-violator" is forbidden: violates PodSecurity "restricted"` | ```bash\n# Show the PSA enforcement mode for the namespace\nkubectl get namespace kubexplain-eval-psa -o jsonpath='{.metadata.labels}'\n# Look for the three PSA labels (enforce, audit, warn)\n# Examine the pod spec you tried to apply and compare it to the PSA rules\n# If the pod was rejected, the error message will be in the API response or in the events of the namespace\nkubectl get events -n kubexplain-eval-psa --field-selector reason=FailedCreate\n``` | * **Option A – adapt the pod**: remove the disallowed fields (run as non‑root, drop `privileged: true`, avoid hostPath, etc.). <br>* **Option B – relax the namespace**: change the PSA level to a less‑strict one (e.g., `baseline`) or add an exemption label to the namespace or the pod: <br>```bash\nkubectl label namespace kubexplain-eval-psa pod-security.kubernetes.io/enforce=baseline --overwrite\n# or, for a single pod exemption\nkubectl label pod s11-psa-violator pod-security.kubernetes.io/audit=privileged --overwrite\n``` |
| 2 | **Namespace‑wide ResourceQuota or LimitRange** – the pod would exceed the quota for CPU, memory, number of pods, or would request resources outside the allowed range | The API server checks quotas **before** persisting the object. If the pod’s `requests/limits` push the namespace over its quota, the creation is rejected with `exceeded quota` errors. | ```bash\nkubectl get resourcequota -n kubexplain-eval-psa -o yaml\nkubectl get limitrange -n kubexplain-eval-psa -o yaml\n# Look at the pod spec you tried to create and sum its requests/limits; compare to the “used” values shown in the quota status\n``` | * Reduce the pod’s resource requests/limits so they fit within the remaining quota. <br>* Increase the quota (if you have permission) or create a new namespace with a larger quota. <br>* Adjust the LimitRange to allow the requested values. |
| 3 | **RBAC – you lack permission to create pods** in that namespace (e.g., missing `create` verb on `pods` resource) | The API server returns a `Forbidden` error (`User "bob" cannot create resource "pods" in API group "" in the namespace "kubexplain-eval-psa"`). | ```bash\nkubectl auth can-i create pod -n kubexplain-eval-psa\n# If you get “no” the binding is missing\nkubectl describe rolebinding,clusterrolebinding -n kubexplain-eval-psa | grep <your‑user>\n``` | * Ask a cluster admin to grant you the needed role, e.g.: <br>```yaml\nkind: RoleBinding\napiVersion: rbac.authorization.k8s.io/v1\nmetadata:\n  name: pod‑creator‑binding\n  namespace: kubexplain-eval-psa\nsubjects:\n- kind: User\n  name: <your‑user>\nroleRef:\n  kind: Role\n  name: pod‑creator\n  apiGroup: rbac.authorization.k8s.io\n``` <br>* Or use a ServiceAccount that already has the permission and run `kubectl` with `--serviceaccount`. |
| 4 | **Validating Admission Webhook rejection** (e.g., OPA/Gatekeeper, custom policy) | Webhooks can reject a pod for any custom rule (e.g., require specific labels, disallow certain images, enforce naming conventions). The error will contain the webhook name. | ```bash\nkubectl get validatingwebhookconfigurations\nkubectl get mutatingwebhookconfigurations\n# If you have the pod‑creation error message, note the webhook name and check its config\nkubectl describe validatingwebhookconfiguration <name>\n``` | * Modify the pod to satisfy the webhook’s rule (add required labels, change image, etc.). <br>* If you control the webhook, adjust its policy or add an exemption for this namespace/pod. <br>* Ask the webhook owner to whitelist the pod or namespace. |
| 5 | **Namespace does not exist or is terminating** | If the namespace is missing, the API returns `namespaces "kubexplain-eval-psa" not found`. If it is in `Terminating` state, creation is blocked. | ```bash\nkubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'\n``` | * Re‑create the namespace (if it was deleted). <br>* If it is stuck in `Terminating`, clean up finalizers: <br>```bash\nkubectl get namespace kubexplain-eval-psa -o json | jq '.spec.finalizers=[]' | kubectl replace --raw /api/v1/namespaces/kubexplain-eval-psa/finalize -f -\n``` |
| 6 | **Pod name already exists** in that namespace | The API returns `AlreadyExists: pods "s11-psa-violator" already exists`. | ```bash\nkubectl get pod s11-psa-violator -n kubexplain-eval-psa\n``` | * Delete the existing pod (if it is stale) or choose a different name. |
| 7 | **ImagePullSecret / registry access issue** (rarely blocks creation) | Some clusters have admission controllers that reject pods whose images cannot be pulled (e.g., `ImagePullBackOff` is usually a runtime issue, but a custom webhook could block it). | ```bash\nkubectl get pod s11-psa-violator -n kubexplain-eval-psa -o yaml | grep imagePullSecrets\n``` | * Ensure the referenced secret exists in the namespace and contains valid Docker credentials. <br>* Add the secret or use a public image. |

---

## How to verify each cause (once you can run `kubectl`)

1. **Check the exact error message** you received when you tried to create the pod.  
   ```bash
   kubectl create -f s11-psa-violator.yaml -n kubexplain-eval-psa
   ```
   The message will usually indicate the blocker (`Forbidden`, `exceeded quota`, `violates PodSecurity`, `webhook <name> denied`, etc.).

2. **Pod‑Security‑Admission**  
   - Look for the three PSA labels on the namespace:  
     ```bash
     kubectl get namespace kubexplain-eval-psa -L pod-security.kubernetes.io/enforce,pod-security.kubernetes.io/audit,pod-security.kubernetes.io/warn
     ```
   - Compare the pod spec against the PSA rules for the current enforcement level (see the PSA docs for the list of prohibited fields).

3. **ResourceQuota / LimitRange**  
   - Show quota usage:  
     ```bash
     kubectl get resourcequota -n kubexplain-eval-psa
     kubectl describe resourcequota <quota-name> -n kubexplain-eval-psa
     ```
   - Show limit ranges:  
     ```bash
     kubectl get limitrange -n kubexplain-eval-psa
     ```

4. **RBAC**  
   - Test your own rights:  
     ```bash
     kubectl auth can-i create pod -n kubexplain-eval-psa
     ```
   - List bindings that involve you:  
     ```bash
     kubectl get rolebinding,clusterrolebinding -n kubexplain-eval-psa -o wide | grep <your‑user>
     ```

5. **Admission Webhooks**  
   - List all validating webhooks:  
     ```bash
     kubectl get validatingwebhookconfigurations
     ```
   - Find the one that rejected the pod (the error message includes the webhook name) and inspect its policy.

6. **Namespace status**  
   ```bash
   kubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'
   ```

7. **Existing pod**  
   ```bash
   kubectl get pod s11-psa-violator -n kubexplain-eval-psa
   ```

---

## Quick “first‑fix” checklist (run when you have cluster access)

1. **Read the error** – it tells you which of the above categories is responsible.  
2. If it mentions **PodSecurity** → edit the pod spec to comply *or* relax the namespace’s PSA level.  
3. If it mentions **quota** → lower the pod’s requests/limits or request a larger quota.  
4. If it mentions **Forbidden** → ask an admin to grant you a `RoleBinding` that includes `create` on `pods`.  
5. If it mentions a **webhook** → locate the webhook config and either adjust the policy or add an exemption.  
6. If the namespace is **Terminating** → clean up finalizers or wait for it to finish.  
7. If the pod **already exists** → delete it (`kubectl delete pod s11-psa-violator -n kubexplain-eval-psa`) or rename the new one.

Following this order will resolve the vast majority of pod‑creation failures in a namespace that is protected by Pod Security Admission. Good luck!
