# s06 | mode=static | run=1 | 7.3s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

### Root cause
The pod runs with the service account **`s06‑limited‑sa`** in the namespace **`kubexplain-eval`**.  
When the container executes `kubectl get secrets -n kubexplain-eval` the API server returns:

```
Error from server (Forbidden): secrets is forbidden:
User "system:serviceaccount:kubexplain-eval:s06-limited-sa"
cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

That response means the service account **does not have a RBAC rule that allows the `list` (or `get`) verb on the `secrets` resource** in that namespace.  
No other problem (network, token expiration, etc.) is shown in the pod description, so the failure is purely an authorization issue.

### How to fix it
Create (or extend) a **Role** that grants permission to read secrets and bind that role to the service account with a **RoleBinding**.

#### 1. Define a Role that can read secrets
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret-reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list", "watch"]
```

#### 2. Bind the Role to the service account
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06-secret-reader
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

#### 3. Apply the manifests
```bash
kubectl apply -f role-secret-reader.yaml
kubectl apply -f rolebinding-s06-secret-reader.yaml
```

*(Replace the file names with whatever you use.)*

#### 4. Verify
After the RoleBinding is created, the pod will automatically pick up the new permissions (the token is projected and refreshed automatically). You can test from inside the pod or from any client that uses the same service account:

```bash
kubectl auth can-i list secrets -n kubexplain-eval --as=system:serviceaccount:kubexplain-eval:s06-limited-sa
# Expected output: yes
```

Or simply re‑run the original command:

```bash
kubectl exec -ti s06-rbac -n kubexplain-eval -- kubectl get secrets -n kubexplain-eval
```

You should now see the list of secrets instead of the Forbidden error.

### Summary
* **Root cause:** `s06-limited-sa` lacks a RBAC rule permitting `list` (or `get`) on `secrets` in the `kubexplain-eval` namespace.  
* **Fix:** Create a Role that allows `get/list/watch` on `secrets` and bind it to the service account with a RoleBinding (or add the rule to an existing Role/ClusterRole that the SA already uses). Once the binding exists, the pod will be able to list secrets successfully.
