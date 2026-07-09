# s06 | mode=none | run=1 | 17.2s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Most likely reasons the **`s06‑limited‑sa`** service account cannot list Secrets  

| # | Likely cause | Why it shows up as a “cannot list secrets” error | How to verify (when you have cluster access) | How to fix |
|---|--------------|--------------------------------------------------|---------------------------------------------|------------|
| 1 | **Missing RBAC permission** – no Role/ClusterRole that grants `list` (or `get`) on `secrets` in the `kubexplain-eval` namespace. | The API server checks the service‑account’s permissions first; if the verb is not allowed it returns a `Forbidden` error that looks like “cannot list secrets”. | ```bash\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval\n```<br>If the result is `no`, the permission is missing. | Create (or edit) a Role that includes `list`/`get` on `secrets` and bind it to the SA: <br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: Role\nmetadata:\n  name: secret‑reader\n  namespace: kubexplain-eval\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: secret‑reader‑binding\n  namespace: kubexplain-eval\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: Role\n  name: secret‑reader\n  apiGroup: rbac.authorization.k8s.io\n``` |
| 2 | **RoleBinding points to the wrong ServiceAccount or wrong namespace** – the Role exists but the binding references a different SA or a SA in another namespace. | The SA ends up with no effective permissions, so the same `Forbidden` error appears. | ```bash\nkubectl get rolebinding -n kubexplain-eval -o yaml | grep -A3 \"subjects:\" -B3\n```<br>Check that `kind: ServiceAccount`, `name: s06-limited-sa`, and `namespace: kubexplain-eval` are present. | Edit the existing RoleBinding (or create a new one) so that the `subjects` block correctly references `s06-limited-sa` in the `kubexplain-eval` namespace (see the RoleBinding example above). |
| 3 | **Secret lives in a different namespace** – the code is trying to list secrets in another namespace (e.g., `default`) while the SA only has rights in its own namespace. | RBAC rules are namespace‑scoped; a Role that grants `list` in `kubexplain-eval` does **not** allow listing in `default`. The API returns `Forbidden` for the target namespace. | ```bash\n# Replace <target‑ns> with the namespace you think the code is querying\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n <target‑ns>\n``` | Either: <br>• Grant the SA permission in the other namespace (create a Role/RoleBinding there), **or** <br>• Change the application to request secrets from its own namespace. |
| 4 | **ServiceAccount token not mounted or expired** – the pod cannot authenticate as the SA, so the request is unauthenticated and the API returns `Forbidden` (or `Unauthorized`). | Without a valid token the API treats the request as coming from an anonymous user, which has no rights to list secrets. | ```bash\n# Verify the pod’s token file exists and is readable\nkubectl exec <pod> -n kubexplain-eval -- cat /var/run/secrets/kubernetes.io/serviceaccount/token\n```<br>If the file is missing or empty, the token isn’t mounted. | Ensure the pod spec does **not** set `automountServiceAccountToken: false`. If the token is expired (rare for in‑cluster tokens), delete the pod so a fresh token is projected. |
| 5 | **Admission controller / PodSecurityPolicy restricting secret access** – a policy (e.g., `PodSecurityPolicy`, `RestrictedSecrets` admission plugin) blocks pods that use a “limited” SA from reading secrets. | Even with RBAC, the admission layer can deny the request, resulting in a `Forbidden` error that mentions “secret”. | ```bash\nkubectl get psp -o yaml | grep -A5 \"s06-limited-sa\"\n# or check the AdmissionConfiguration if you have access to the API server config\n``` | Adjust the policy to allow the SA to read secrets, or move the pod to a less‑restricted PSP/Policy. |
| 6 | **Cluster‑wide `ClusterRole` that explicitly denies secret access** – a `ClusterRole` bound to the SA (or to a group the SA belongs to) may have a `resourceNames` restriction that excludes the secret(s) you need. | The deny‑by‑name rule overrides a broader allow, causing a `Forbidden` error for the specific secret. | ```bash\nkubectl get clusterrolebinding -o yaml | grep -A5 \"s06-limited-sa\"\n```<br>Look for a `ClusterRole` with `resourceNames` that omit the secret. | Remove or modify the restrictive `ClusterRole`, or create a more specific Role that grants the needed access and bind it with higher precedence. |
| 7 | **Namespace is in terminating state or has a finalizer that blocks secret reads** – while the namespace is being deleted, the API may reject secret operations. | The error surface can be a generic `Forbidden` or a more specific “namespace is terminating”. | ```bash\nkubectl get namespace kubexplain-eval -o yaml | grep phase\n``` | Wait for the namespace deletion to finish, or remove the finalizer manually if it’s stuck. |

---

## How to verify the root cause (once you regain access)

1. **Check the exact error message** – `kubectl get secret` or the application logs will show either `Forbidden` (RBAC) or `Unauthorized` (authentication) or a more specific message.
2. **Run an auth‑check as the SA** (replace `<ns>` if you suspect another namespace):  
   ```bash
   kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n <ns>
   ```
   - `yes` → RBAC is fine for that namespace; look at other causes.
   - `no` → RBAC is the problem; inspect Roles/RoleBindings (steps 1‑3 above).
3. **Inspect the Role/ClusterRole that should grant the permission**:  
   ```bash
   kubectl get role -n kubexplain-eval -o yaml | grep -A5 secrets
   kubectl get clusterrole <name> -o yaml | grep -A5 secrets
   ```
4. **Inspect the RoleBinding/ClusterRoleBinding** to ensure the SA is correctly referenced (step 2 above).  
5. **Confirm the pod’s token** (step 4) – `cat /var/run/secrets/kubernetes.io/serviceaccount/token` inside the pod.  
6. **Check any admission policies** that might be applied to the namespace or the pod (PodSecurityPolicy, OPA/Gatekeeper, etc.).  

---

## Quick “fix‑it” checklist (apply after you can run `kubectl`)

1. **Add a Role that allows secret reads** (if missing).  
2. **Create/patch a RoleBinding** that points to `s06-limited-sa` in the correct namespace.  
3. **If the secret is in another namespace**, repeat steps 1‑2 in that namespace or change the code to query the correct namespace.  
4. **Ensure the pod mounts its service‑account token** (`automountServiceAccountToken: true`).  
5. **Review and adjust any restrictive PSP or admission plugins** that might block secret access.  
6. **Remove any deny‑by‑name `ClusterRole`** that unintentionally blocks the secret.  
7. **If the namespace is terminating**, wait for cleanup or delete the finalizer.

Follow the verification steps in the order above; the first one that returns a problem is usually the root cause. Once the appropriate RBAC (or token) issue is resolved, the service account will be able to list the secrets again.
