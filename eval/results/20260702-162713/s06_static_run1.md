# s06 | mode=static | run=1 | 18.5s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Most likely reasons the `s06‑limited‑sa` service account cannot list Secrets  

| # | Likely cause | Why it produces a “cannot list secrets” error | How to verify (when you have cluster access) | How to fix |
|---|--------------|-----------------------------------------------|---------------------------------------------|-----------|
| 1 | **Missing RBAC rule** – no Role/ClusterRole that grants `list` (or `get/watch`) on `secrets` in the `kubexplain‑eval` namespace. | The API server checks the service‑account’s permissions; without a rule it returns *“User \"system:serviceaccount:kubexplain‑eval:s06‑limited‑sa\" cannot list secrets …”*. | ```bash\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval\n```<br>If the result is `no`, the rule is missing. | Create a Role that allows the needed verbs and bind it to the SA: <br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: Role\nmetadata:\n  name: secret‑reader\n  namespace: kubexplain-eval\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: secret‑reader‑binding\n  namespace: kubexplain-eval\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: Role\n  name: secret‑reader\n  apiGroup: rbac.authorization.k8s.io\n``` |
| 2 | **RoleBinding points to the wrong ServiceAccount or wrong namespace** – a Role exists but the binding references a different SA name or a different namespace. | The SA you are using never receives the permissions, so the check still fails. | ```bash\nkubectl get rolebinding -n kubexplain-eval -o yaml | grep -A5 subjects\n```<br>Confirm that `name: s06-limited-sa` and `namespace: kubexplain-eval` appear. | Edit the existing RoleBinding (or create a new one) so that the `subjects` field correctly references `s06-limited-sa` in the `kubexplain-eval` namespace. |
| 3 | **Verb list is omitted** – the Role grants `get`/`watch` but not `list`. | `list` is a separate verb; without it the API returns a *forbidden* error for a plain `kubectl get secrets` (which uses `list`). | ```bash\nkubectl describe role secret‑reader -n kubexplain-eval\n```<br>Check the `verbs:` line. | Add `list` to the `verbs` array in the Role and re‑apply the manifest. |
| 4 | **Secret‑access restriction (RestrictedSecrets admission plugin / ServiceAccount token volume)** – the cluster is configured to hide all Secrets except those explicitly referenced by a Pod. | Even with RBAC, the admission controller strips the response, causing a *forbidden* error when the SA tries to list arbitrary Secrets. | ```bash\nkubectl get --raw='/api/v1/namespaces/kubexplain-eval/secrets' -H 'Authorization: Bearer <token>'\n```<br>or ask the cluster admin to check the API‑server flag `--enable-admission-plugins=...,RestrictedSecrets`. | Either: <br>• Disable the `RestrictedSecrets` plugin (requires cluster‑admin change). <br>• Grant the SA the `secrets` “view” permission **and** add the annotation `kubernetes.io/service-account.name: s06-limited-sa` to the Secrets you want to expose, or use a `Secret` of type `kubernetes.io/service-account-token` that the SA can read. |
| 5 | **Attempting to list Secrets in a different namespace** – the code asks for secrets cluster‑wide or in another namespace, but the SA only has rights in `kubexplain-eval`. | RBAC is namespace‑scoped; without a ClusterRole the SA cannot list secrets outside its own namespace. | ```bash\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n other-namespace\n``` | If cross‑namespace access is required, create a **ClusterRole** with `secrets` verbs and bind it with a **ClusterRoleBinding** (or a RoleBinding in each target namespace). |
| 6 | **ServiceAccount token not mounted / using a projected token with limited audience** – the pod is sending an unauthenticated request, which the API server treats as “forbidden”. | The error message will usually say *“authentication required”* rather than *“cannot list secrets”*, but a mis‑configured projected token can surface as a generic forbidden error. | ```bash\nkubectl logs <pod> -n kubexplain-eval | grep -i 'authentication'\n```<br>or inspect the pod spec for `serviceAccountToken` projection. | Ensure the pod uses the default service‑account token (`automountServiceAccountToken: true`) or, if using projected tokens, set `audiences` to include the API server and give the token sufficient expiration. |

---

### Quick verification checklist (run when you regain access)

1. **Check the SA’s effective permissions**  
   ```bash
   kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval
   ```

2. **Inspect any Role/ClusterRole that mentions `secrets`**  
   ```bash
   kubectl get role,clusterrole -A -o yaml | grep -A5 -B2 '"secrets"'
   ```

3. **Confirm the binding**  
   ```bash
   kubectl get rolebinding,clusterrolebinding -A -o yaml | grep -A5 s06-limited-sa
   ```

4. **Look for the `RestrictedSecrets` admission plugin** (needs admin rights)  
   ```bash
   kubectl get --raw='/api/v1/nodes' | jq '.items[].status.nodeInfo.kubeletVersion'
   # then ask the admin to check the API‑server startup flags
   ```

5. **If cross‑namespace is needed, verify ClusterRoleBinding**  
   ```bash
   kubectl get clusterrolebinding -o yaml | grep -A5 s06-limited-sa
   ```

---

### How to apply the most common fix (RBAC)

If the problem is simply “no permission”, the minimal fix is:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret-reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get","list","watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret-reader-binding
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret-reader
  apiGroup: rbac.authorization.k8s.io
```

Apply with `kubectl apply -f <file>.yaml` once you’re back in the cluster.

---

### TL;DR

1. **Most probable:** missing or mis‑configured RBAC rule for `list` on `secrets`.  
2. **Next likely:** RoleBinding points to the wrong SA/namespace or omits the `list` verb.  
3. **Cluster‑wide restrictions** (RestrictedSecrets) can also block the call.  
4. **Cross‑namespace** or **token‑mount** issues are less common but worth checking.

Follow the verification steps above, adjust the RBAC objects accordingly, and the service account will be able to list the Secrets it needs.
