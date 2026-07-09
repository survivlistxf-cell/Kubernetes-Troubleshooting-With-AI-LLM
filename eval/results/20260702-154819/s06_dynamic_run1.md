# s06 | mode=dynamic | run=1 | 80.7s | needs_search=1

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

**Root cause**

The pod is running as the ServiceAccount **`s06‑limited‑sa`** in the namespace **`kubexplain‑eval`**.  
When the container executes `kubectl get secrets -n kubexplain‑eval` the API server returns:

```
Error from server (Forbidden): secrets is forbidden:
User "system:serviceaccount:kubexplain-eval:s06-limited-sa"
cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

This is an RBAC (Role‑Based Access Control) denial. By default a ServiceAccount has no permissions beyond the minimal API‑discovery rights, so it cannot list `Secret` objects. The pod’s token is correctly projected (the volume `kube-api-access‑sn4j8` is present), but the token’s bearer identity is not authorized for the `list` verb on the `secrets` resource.

**How to fix it**

Create a Role that grants the needed permissions on `secrets` in the `kubexplain‑eval` namespace and bind that Role to the ServiceAccount with a RoleBinding.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret‑reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get","list","watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret‑reader‑binding
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret‑reader
  apiGroup: rbac.authorization.k8s.io
```

Apply the manifest:

```bash
kubectl apply -f <file>.yaml
```

After the RoleBinding is created, the pod’s next attempt to run `kubectl get secrets` will succeed, and the container will exit cleanly (or you can adjust the command logic as needed).

**Why this works**

* The ServiceAccount token is automatically mounted as a projected volume (`kube-api-access‑sn4j8`) and is valid for ~1 hour (`TokenExpirationSeconds: 3607`).  
* RBAC checks are performed on every request; granting the `list` (and optionally `get`/`watch`) verb on the `secrets` resource satisfies the request.  

**References**

- ServiceAccount concepts and token projection: https://kubernetes.io/docs/concepts/security/service-accounts/  
- Configuring ServiceAccounts for Pods (including RBAC binding): https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/
