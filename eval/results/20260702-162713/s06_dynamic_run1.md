# s06 | mode=dynamic | run=1 | 19.0s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

**Most likely root‑cause:** the service‑account **`s06‑limited‑sa`** does not have a RBAC rule that allows it to `list` `secrets` in the **`kubexplain‑eval`** namespace (or in whatever namespace it is trying to query).  
When a pod runs with that service‑account the API server checks the service‑account’s permissions; if no `Role`/`ClusterRole` (and corresponding `RoleBinding`/`ClusterRoleBinding`) grants the `list` verb on the `secrets` resource, the request is rejected with a *Forbidden* error.

Below are the most common reasons this can happen, ordered by how often they appear, together with the quick checks you can run (once you regain cluster access) and the fix for each.

---

## 1. Missing or incomplete RBAC rule for `secrets`

| Symptom | How to verify (when you have a cluster) | Fix |
|---------|------------------------------------------|-----|
| `Forbidden: User "<service‑account>" cannot list secrets` | ```bash\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval\n```<br>returns `no`.<br>Also inspect any `Role`/`ClusterRole` that mentions `secrets`:<br>`kubectl get role,clusterrole -n kubexplain-eval -o yaml | grep -A3 -B3 secrets` | Create (or edit) a `Role` that grants the needed verbs and bind it to the SA:<br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: Role\nmetadata:\n  name: secret‑reader\n  namespace: kubexplain-eval\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: secret‑reader‑binding\n  namespace: kubexplain-eval\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: Role\n  name: secret‑reader\n  apiGroup: rbac.authorization.k8s.io\n``` |
| | | Apply with `kubectl apply -f <file>.yaml`. |

---

## 2. RoleBinding points to the wrong ServiceAccount or wrong namespace

| Symptom | How to verify | Fix |
|---------|---------------|-----|
| The `Role` exists and looks correct, but `can-i` still says *no*. | List the bindings that reference the SA: <br>`kubectl get rolebinding,clusterrolebinding -n kubexplain-eval -o yaml | grep -B2 s06-limited-sa`<br>Check that `namespace` fields match the SA’s namespace (`kubexplain-eval`). | If the binding is in a different namespace or uses a typo in the SA name, recreate it with the correct `subjects` block (see the YAML above). |
| | | For a cluster‑wide binding (if you need to read secrets in other namespaces), use a `ClusterRole` + `ClusterRoleBinding` instead of a namespaced `RoleBinding`. |

---

## 3. Trying to list secrets in a *different* namespace without permission

| Symptom | How to verify | Fix |
|---------|---------------|-----|
| The pod calls the API with `?namespace=other-ns` and receives *Forbidden*. | Run `kubectl auth can-i list secrets -n other-ns --as=system:serviceaccount:kubexplain-eval:s06-limited-sa`. | Either grant the SA permission in the target namespace (create a `Role`/`RoleBinding` there) **or** limit the code to list secrets only in its own namespace. |
| | | Example of granting cross‑namespace access: create the same `Role` in the other namespace and bind the same SA. |

---

## 4. ServiceAccount token is missing or expired (authentication failure)

| Symptom | How to verify | Fix |
|---------|---------------|-----|
| Error message mentions *authentication* (e.g., `Unauthorized` or `certificate signed by unknown authority`). | Check the pod’s logs for the exact error. <br>Inspect the SA token mount: `kubectl exec <pod> -- cat /var/run/secrets/kubernetes.io/serviceaccount/token` (if you can exec). <br>Run `kubectl describe sa s06-limited-sa -n kubexplain-eval` to see the secret name. | If the token secret is missing, delete the pod so it is recreated (the kubelet will re‑issue a token). <br>If the cluster uses **BoundServiceAccountTokenVolume** (Kubernetes 1.22+), ensure the pod’s service‑account token projection is enabled, or add the `serviceAccountName` field correctly in the pod spec. |
| | | In most cases the token is automatically refreshed; a missing token usually points to a mis‑configured pod spec rather than RBAC. |

---

## 5. Admission controller or PodSecurityPolicy restricting secret access

| Symptom | How to verify | Fix |
|---------|---------------|-----|
| The pod runs with a *restricted* service‑account token (e.g., `TokenRequest` with limited audiences) and the API returns *Forbidden* even though a `Role` exists. | Look at the `TokenRequest` spec in the pod’s `serviceAccountToken` projection (if any). <br>Check the API server’s `--authorization-mode` and any `Admission` plugins that might limit secret access (e.g., `NodeRestriction`). | If the cluster enforces `Restricted` service‑account tokens, create a `TokenRequest` that includes the `secrets` audience, or switch the pod to use the classic long‑lived token (by disabling the `BoundServiceAccountTokenVolume` feature). |
| | | Usually this is a cluster‑wide policy; coordinate with the cluster admin to adjust the policy or grant the needed audience. |

---

## 6. Secret type is not visible to the service‑account (e.g., `kubernetes.io/service-account-token`)

| Symptom | How to verify | Fix |
|---------|---------------|-----|
| `list` succeeds but the returned items are empty or missing the expected secret. | Run `kubectl get secret -n kubexplain-eval -o yaml` with the SA’s credentials (`kubectl auth can-i get secret/<name> ...`). | Ensure the secret you need is not a *service‑account token* that is automatically created and hidden. If you need to read a custom secret, create it with a non‑token type (e.g., `Opaque`). |
| | | No RBAC change is required; just verify the secret exists and is of the right type. |

---

### Quick “when you’re back in the cluster” checklist

1. **Check the SA’s permissions**  
   ```bash
   kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval
   ```
2. **Inspect existing RBAC**  
   ```bash
   kubectl get role,clusterrole -n kubexplain-eval -o yaml | grep -A5 -B5 secrets
   kubectl get rolebinding,clusterrolebinding -n kubexplain-eval -o yaml | grep -B2 s06-limited-sa
   ```
3. **Confirm the namespace you are querying** – repeat the `can-i` check for any other namespace you need.
4. **Validate the token** (if you can exec into the pod).  
5. **Apply the missing Role/Binding** (see the YAML in cause 1) and re‑run the `can-i` test.

---

### TL;DR

| Rank | Likely cause | Why it happens | How to confirm | How to fix |
|------|--------------|----------------|----------------|------------|
| 1 | No RBAC rule granting `list` on `secrets` | Default SA has no secret‑read rights | `kubectl auth can-i list secrets …` returns `no` | Create a `Role` (or `ClusterRole`) with `resources: [secrets]` and `verbs: [get,list,watch]`, bind it to the SA |
| 2 | RoleBinding points to wrong SA/namespace | Typos or copy‑paste errors are common | `kubectl get rolebinding … -o yaml` shows mismatched `subjects` | Re‑create the binding with the correct SA name and namespace |
| 3 | Trying to list secrets in another namespace without permission | RBAC is namespace‑scoped unless a `ClusterRole` is used | `kubectl auth can-i list secrets -n other‑ns …` returns `no` | Add a `Role`/`RoleBinding` in the target namespace or use a `ClusterRole` |
| 4 | Missing/expired service‑account token | Pod cannot authenticate, so API rejects request | Pod logs show `Unauthorized` or token file is empty | Delete/re‑create the pod, ensure token projection is enabled |
| 5 | Admission controller / restricted token audience | Cluster enforces limited‑scope tokens | Inspect `serviceAccountToken` projection or API‑server flags | Request a token with the needed audience or disable the restriction |
| 6 | Secret type not visible (e.g., token secret) | Token secrets are auto‑generated and may be filtered | `kubectl get secret …` shows no matching secret | Create an `Opaque` secret or adjust code to look for the correct secret name |

Address the highest‑ranked cause first; in the vast majority of cases the problem is simply a missing `Role`/`RoleBinding` that grants the service‑account permission to list secrets. Once that rule is in place, the application should be able to read the secrets it needs.
