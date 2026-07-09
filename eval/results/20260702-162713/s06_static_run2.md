# s06 | mode=static | run=2 | 18.2s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Most likely reasons the **s06‑limited‑sa** service account cannot list Secrets  

| # | Likely cause | Why it’s the most common | How to verify (run when you have cluster access) | How to fix |
|---|--------------|--------------------------|---------------------------------------------------|------------|
| 1 | **Missing RBAC permission** (no Role/ClusterRole that grants `list`/`watch` on `secrets` in the `kubexplain‑eval` namespace) | By default a ServiceAccount has **no** rights to read Secrets. The first thing to check is whether a Role/ClusterRole and a RoleBinding/ClusterRoleBinding exist for this SA. | ```bash\n# Does the SA have the needed permission?\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval\n```<br>If the answer is **no**, inspect the RBAC objects that reference the SA: <br>```bash\nkubectl get rolebinding,clusterrolebinding -n kubexplain-eval -o yaml | grep -A3 s06-limited-sa\n``` | Create (or update) a Role that allows the secret verbs and bind it to the SA. Example: <br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: Role\nmetadata:\n  name: secret‑reader\n  namespace: kubexplain-eval\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: secret‑reader‑bind\n  namespace: kubexplain-eval\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: Role\n  name: secret‑reader\n  apiGroup: rbac.authorization.k8s.io\n```<br>Apply with `kubectl apply -f <file>.yaml`. |
| 2 | **ServiceAccount token not mounted** (`automountServiceAccountToken: false` on the Pod or the SA) | If the pod cannot present a valid token, the API request is unauthenticated and is rejected before RBAC is even consulted. | ```bash\n# Look at the pod spec that runs the application\nkubectl get pod <pod-name> -n kubexplain-eval -o yaml | grep -i automountServiceAccountToken -A2\n```<br>Also check the SA itself: <br>```bash\nkubectl get serviceaccount s06-limited-sa -n kubexplain-eval -o yaml | grep -i automountServiceAccountToken\n``` | Ensure the pod (or the SA) has `automountServiceAccountToken: true` (or simply omit the field). If it was disabled, edit the pod/deployment: <br>```bash\nkubectl edit deployment <deployment-name> -n kubexplain-eval\n```<br>Remove `automountServiceAccountToken: false` or set it to `true`. Then redeploy/restart the pod. |
| 3 | **Secret lives in a different namespace** (the code tries to list Secrets in another namespace) | A Role scoped to `kubexplain-eval` cannot see Secrets outside that namespace. If the application requests a different namespace, the request will be denied. | ```bash\n# Check the code / configuration that builds the request – which namespace is used?\n# If you can’t see the code, test the SA against another namespace:\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n other-namespace\n``` | Either: <br>• Change the application to request Secrets only from `kubexplain-eval`, **or** <br>• Grant cross‑namespace permission by creating a **ClusterRole** with secret access and a **ClusterRoleBinding** (or a RoleBinding in the target namespace) that references the SA. Example ClusterRole: <br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRole\nmetadata:\n  name: secret‑reader‑cluster\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRoleBinding\nmetadata:\n  name: s06‑limited‑sa‑secret‑reader\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: ClusterRole\n  name: secret‑reader‑cluster\n  apiGroup: rbac.authorization.k8s.io\n``` |
| 4 | **Admission‑controller or API‑server policy that blocks secret enumeration** (e.g., `--runtime-config=api/authorization.k8s.io/v1beta1=true` with a restrictive `NodeRestriction`, or a custom `ValidatingAdmissionWebhook` that denies secret list) | Some clusters tighten secret visibility for non‑privileged accounts (e.g., the `RestrictedSecrets` admission plugin). This would cause a “Forbidden” error even if RBAC looks correct. | ```bash\n# Look at the error message returned by the API call – does it say “Forbidden” with a reason like \"restricted secrets\"?\n# If you can, list the admission plugins enabled on the API server:\nkubectl -n kube-system get configmap kube-apiserver -o yaml | grep -i admission\n``` | If such a plugin is enabled, you need to either: <br>• Add the SA to a group that the plugin whitelists (e.g., `system:serviceaccounts:kubexplain-eval`), or <br>• Adjust the plugin configuration to allow secret listing for this SA. This typically requires cluster‑admin access to edit the API‑server manifest or ConfigMap. |
| 5 | **NetworkPolicy / Egress restriction** preventing the pod from reaching the API server** | Rare, but a `NetworkPolicy` that blocks outbound traffic to the API server’s IP/port would make the request fail with a timeout or connection‑refused, which can be mis‑interpreted as a permission error. | ```bash\n# List NetworkPolicies that affect the pod’s namespace\nkubectl get networkpolicy -n kubexplain-eval\n```<br>Check the pod’s labels against the policy’s `podSelector`. | If a restrictive policy exists, add an egress rule that allows traffic to the API server (usually `kubernetes.default.svc` on port 443) or relax the policy for the pod. |
| 6 | **API‑server misconfiguration or outage** (e.g., the API server is down or the RBAC webhook is failing) | This is the least common cause for a single SA error; usually it would affect many pods. | ```bash\nkubectl get componentstatuses\nkubectl get --raw='/healthz'\n``` | Resolve the underlying API‑server issue (restart the control‑plane component, fix the webhook, etc.). |

---

### How to verify the problem when you regain access  

1. **Check the exact error** – capture the message returned by the application (e.g., `Forbidden: User "system:serviceaccount:kubexplain-eval:s06-limited-sa" cannot list secrets in the namespace "kubexplain-eval"`). The wording points directly to RBAC vs other causes.  

2. **Run a “can‑i” test** (as shown in row 1). If it returns **no**, the issue is RBAC.  

3. **Inspect the ServiceAccount and Pod** for `automountServiceAccountToken`.  

4. **Confirm the namespace used in the request** – try the same `can‑i` test against another namespace.  

5. **List RBAC objects** that reference the SA (`kubectl get rolebinding,clusterrolebinding -A -o yaml | grep s06-limited-sa`).  

6. **Look at admission‑controller configuration** if RBAC looks correct but the request is still denied.  

7. **Check NetworkPolicies** and component health if the error is a connection problem rather than a “Forbidden”.  

---

### Quick “fix‑it” checklist (apply in order)

1. **Add a Role + RoleBinding** that grants `get/list/watch` on `secrets` in `kubexplain-eval`.  
2. **Ensure the pod’s token is mounted** (`automountServiceAccountToken: true`).  
3. **If cross‑namespace access is needed**, create a ClusterRole + ClusterRoleBinding (or a RoleBinding in the target namespace).  
4. **If a restrictive admission plugin is present**, adjust its config or add the SA to an allowed group.  
5. **Modify any blocking NetworkPolicy** to allow egress to the API server.  
6. **Address any API‑server health issues** if they surface.  

Following this ordered list should resolve the “cannot list secrets” error for the `s06‑limited‑sa` service account. Good luck!
