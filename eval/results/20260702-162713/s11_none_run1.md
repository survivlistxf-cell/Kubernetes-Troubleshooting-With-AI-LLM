# s11 | mode=none | run=1 | 20.5s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons the pod **`s11-psa-violator`** cannot be created in namespace **`kubexplain-eval-psa`**

| # | Likely cause | Why it’s probable | How to verify | How to fix |
|---|--------------|-------------------|---------------|------------|
| 1 | **Pod Security Admission (PSA) policy violation (enforced mode)** | The namespace name contains **`psa`** and the pod name includes **`violator`**, a classic pattern used in PSA‑related test cases. When a namespace is set to `enforce` a level that the pod does not satisfy, the API server rejects the creation request. | ```bash\nkubectl get namespace kubexplain-eval-psa -o jsonpath='{.metadata.labels}'\n``` Look for the three PSA labels: `pod-security.kubernetes.io/enforce`, `pod-security.kubernetes.io/audit`, `pod-security.kubernetes.io/warn`. Then compare the pod spec (e.g., privileged containers, hostPath volumes, runAsUser=0, etc.) with the required level (`restricted`, `baseline`, or `privileged`). | *If the pod really needs the disallowed feature:*<br>• Change the namespace’s **enforce** label to a less‑strict level (e.g., `baseline` or `privileged`).<br>```bash\nkubectl label namespace kubexplain-eval-psa pod-security.kubernetes.io/enforce=baseline --overwrite\n```<br>• Or add an exemption for that pod name:<br>```bash\nkubectl label namespace kubexplain-eval-psa pod-security.kubernetes.io/enforce-exemptions=s11-psa-violator --overwrite\n```<br>*If the pod should comply:*<br>• Edit the pod manifest to remove the offending fields (e.g., drop `privileged: true`, avoid `hostPath`, set a non‑root `runAsUser`, add required `readOnlyRootFilesystem`, etc.) and re‑apply. |
| 2 | **ResourceQuota exceeded** | Many clusters protect namespaces with a `ResourceQuota`. If the quota for pods, CPU, memory, or other resources is already exhausted, the API server returns a “exceeded quota” error on creation. | ```bash\nkubectl get quota -n kubexplain-eval-psa\n``` and inspect the `used` vs `hard` columns for `pods`, `requests.cpu`, `requests.memory`, etc. | • Reduce the resource requests/limits of the new pod.<br>• Delete or scale down other pods in the namespace.<br>• Increase the quota (requires admin rights):<br>```bash\nkubectl edit quota <quota-name> -n kubexplain-eval-psa\n``` and raise the `hard` values. |
| 3 | **LimitRange constraints** | A `LimitRange` can require every container to specify `cpu`/`memory` limits and/or enforce a minimum/maximum. If the pod omits these fields or asks for values outside the allowed range, creation fails. | ```bash\nkubectl get limitrange -n kubexplain-eval-psa -o yaml\n``` Look for `default`, `defaultRequest`, `min`, `max` sections. | • Add the missing `resources.limits`/`resources.requests` to the pod spec within the allowed range.<br>• Adjust the `LimitRange` (admin only) to broaden the limits. |
| 4 | **Admission webhook rejection (e.g., OPA Gatekeeper, Kyverno, custom webhook)** | Many clusters install policy‑enforcement webhooks that can block pods for reasons unrelated to PSA (e.g., required labels, image registries, disallowed host networking). The error message usually mentions the webhook name. | ```bash\nkubectl get validatingwebhookconfigurations\nkubectl get mutatingwebhookconfigurations\n``` Then check the logs of the webhook pod (if you have access) or ask the cluster admin for the webhook’s policy. | • Modify the pod manifest to satisfy the webhook’s rules (add required labels, use approved images, avoid hostNetwork, etc.).<br>• If the webhook is too strict for this test, ask the admin to add an exemption or temporarily disable the webhook for the namespace. |
| 5 | **RBAC – missing permission to create pods** | If the user/ServiceAccount you are using does not have `create` rights on `pods` in that namespace, the API server returns a “Forbidden” error. | ```bash\nkubectl auth can-i create pod -n kubexplain-eval-psa\n``` If the answer is `no`, you lack permission. | • Grant the appropriate role/rolebinding to the user or ServiceAccount:<br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: pod-creator\n  namespace: kubexplain-eval-psa\nsubjects:\n- kind: User\n  name: <your‑user>\nroleRef:\n  kind: Role\n  name: edit   # or a custom role that includes create pods\n  apiGroup: rbac.authorization.k8s.io\n``` Apply with `kubectl apply -f`. |
| 6 | **Namespace is terminating or in a bad state** | A namespace that is stuck in `Terminating` cannot accept new resources. This can happen if finalizers are not cleared. | ```bash\nkubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'\n``` If the phase is `Terminating`, the namespace is unusable. | • Remove the problematic finalizers (admin only) or delete the namespace and recreate it. |
| 7 | **Image pull secret / registry access issue** | If the pod spec references a private image and the namespace lacks a valid `imagePullSecret`, the API server may reject the pod creation (especially when `imagePullPolicy=IfNotPresent` and the image is not cached). | ```bash\nkubectl get secret -n kubexplain-eval-psa\n``` Look for a secret of type `kubernetes.io/dockerconfigjson` referenced in the pod’s `imagePullSecrets`. | • Create or update the secret with correct registry credentials:<br>```bash\nkubectl create secret docker-registry my-reg-secret \\\n  --docker-server=<registry> \\\n  --docker-username=<user> \\\n  --docker-password=<pass> \\\n  --docker-email=<email> -n kubexplain-eval-psa\n```<br>• Add the secret name to `imagePullSecrets` in the pod spec. |
| 8 | **Node selector / affinity mismatch (no suitable node)** | While a pod can still be created even if it cannot be scheduled, some admission controllers (e.g., `NodeRestriction`) reject pods that request a node that does not exist. | ```bash\nkubectl describe pod <pod-name> -n kubexplain-eval-psa\n``` (if the pod exists) or inspect the pod spec for `nodeSelector`, `nodeAffinity`. | • Adjust or remove the selector/affinity so that at least one node matches.<br>• Ensure the required node labels exist on the cluster. |
| 9 | **Taints/Tolerations conflict** | Similar to node selector, a pod that lacks required tolerations for a node’s taint may be rejected by certain admission controllers. | ```bash\nkubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}:{.spec.taints}\\n{end}'\n``` | • Add the needed tolerations to the pod spec, or remove the taint from the node (if appropriate). |
|10| **Pod name or other field violates DNS label rules** | Kubernetes requires pod names to be DNS‑1123 labels (lowercase alphanumeric + `-`). If the name contains illegal characters, creation fails. | Verify the name: `s11-psa-violator` is valid, so this is unlikely, but still worth checking if you changed it. | Rename the pod to a valid DNS‑1123 name. |

---

## How to verify each cause (step‑by‑step)

1. **Check the exact error message**  
   Run the create command (or ask the person who tried) and capture the full error. The message usually contains a clue (`Forbidden`, `exceeded quota`, `violates PodSecurity`, `admission webhook ... denied`, etc.).

2. **Inspect PSA settings**  
   ```bash
   kubectl get namespace kubexplain-eval-psa -o yaml | grep pod-security.kubernetes.io
   ```
   Compare the required level (`restricted`, `baseline`, `privileged`) with the pod’s spec.

3. **Look at quotas and limit ranges**  
   ```bash
   kubectl get quota -n kubexplain-eval-psa
   kubectl get limitrange -n kubexplain-eval-psa -o yaml
   ```

4. **Validate RBAC**  
   ```bash
   kubectl auth can-i create pod -n kubexplain-eval-psa
   ```

5. **Identify active admission webhooks**  
   ```bash
   kubectl get validatingwebhookconfigurations
   kubectl get mutatingwebhookconfigurations
   ```

6. **Confirm namespace health**  
   ```bash
   kubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'
   ```

7. **Check image pull secrets**  
   ```bash
   kubectl get secret -n kubexplain-eval-psa
   ```

8. **Review node‑related constraints**  
   ```bash
   kubectl get nodes -o wide
   kubectl describe node <node-name> | grep -i taint
   ```

---

## Quick “fix checklist” you can run once you have cluster access

- **If PSA is the blocker**: lower the enforce level, add an exemption, or modify the pod to comply.  
- **If quota/limitrange is the blocker**: reduce requests, delete other pods, or ask an admin to raise the limits.  
- **If RBAC is the blocker**: get a RoleBinding that grants `create` on pods.  
- **If a webhook is the blocker**: adjust the pod to meet the webhook’s policy or request an exemption.  
- **If image pull secret is missing**: create the secret and reference it.  
- **If the namespace is terminating**: clean up finalizers or recreate the namespace.  

By following the verification steps in the order above, you’ll quickly pinpoint the exact reason the pod cannot be created and apply the appropriate remediation. Good luck!
