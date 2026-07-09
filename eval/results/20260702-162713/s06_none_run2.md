# s06 | mode=none | run=2 | 16.1s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Most likely reasons the `s06‑limited‑sa` service account cannot list Secrets  

Below are the causes I see most often, ordered from **most probable** to **least probable**.  For each one I describe how you can verify it (once you have cluster access) and what you need to change to fix the problem.

---

### 1. Missing RBAC permission to `list` Secrets in the namespace  

**Why it happens**  
The ServiceAccount is bound to a Role/ClusterRole that does **not** include the `list` (or `get`) verb on the `secrets` resource.  The API therefore returns a *Forbidden* error such as  

```
secrets is forbidden: User "system:serviceaccount:kubexplain-eval:s06-limited-sa" cannot list secrets in the namespace "kubexplain-eval"
```

**How to verify**  

```bash
# Does the SA have the needed permission?
kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval
```

If the answer is `no`, the permission is missing.

You can also inspect the Role/ClusterRole that is bound to the SA:

```bash
kubectl get rolebinding,clusterrolebinding -n kubexplain-eval \
  -o yaml | grep -A5 s06-limited-sa
```

Look for a `rules:` block that mentions `resources: ["secrets"]`.

**Fix**  

Create (or edit) a Role that grants the needed verbs and bind it to the SA:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret‑viewer
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get","list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret‑viewer‑binding
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret‑viewer
  apiGroup: rbac.authorization.k8s.io
```

Apply the manifest and the SA will be able to list Secrets.

---

### 2. RoleBinding points to the wrong Role or the wrong namespace  

**Why it happens**  
A RoleBinding may reference a Role that lives in a different namespace, or the binding itself may be created in the wrong namespace.  In that case the SA gets no permissions in `kubexplain-eval`.

**How to verify**  

```bash
kubectl get rolebinding -n kubexplain-eval -o yaml | grep -A5 s06-limited-sa
```

Check that:

* `metadata.namespace` of the RoleBinding is `kubexplain-eval`.
* `roleRef.name` refers to a Role **also** in `kubexplain-eval` (or to a ClusterRole).

**Fix**  

If the binding is mis‑scoped, recreate it in the correct namespace or point it to a Role that exists there.  Example correction:

```bash
kubectl delete rolebinding <bad‑binding> -n kubexplain-eval
kubectl apply -f corrected‑binding.yaml
```

---

### 3. The Secret you are trying to list lives in a different namespace  

**Why it happens**  
Secrets are namespace‑scoped.  Even with permission to list Secrets in `kubexplain-eval`, the SA cannot see Secrets that belong to another namespace (e.g., `default`).

**How to verify**  

```bash
kubectl get secret -n <other‑ns>
```

If the secret you need is not in `kubexplain-eval`, you’ll get a *Forbidden* error when you try to list it from the SA’s perspective.

**Fix**  

* Move the secret to the same namespace (or recreate it there), **or**
* Grant the SA permission to list Secrets in the other namespace by creating a RoleBinding in that namespace.

```yaml
# Example: grant access in the "default" namespace
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret‑viewer‑default
  namespace: default
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret‑viewer   # same Role defined in "default"
  apiGroup: rbac.authorization.k8s.io
```

---

### 4. An admission controller (OPA/Gatekeeper, Kyverno, etc.) blocks secret access  

**Why it happens**  
Policy engines can be configured to deny reads of Secrets for certain service accounts, even if RBAC would otherwise allow it.

**How to verify**  

* Look for `AdmissionReview` denials in the API server audit log (if you have access).  
* Check the policies applied by the controller:

```bash
kubectl get constraint -A -o yaml | grep -i s06-limited-sa
```

If a constraint explicitly denies `secrets` for this SA, you’ll see it here.

**Fix**  

Modify or remove the offending policy.  For Gatekeeper, you might edit the `Constraint`:

```yaml
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: K8sAllowedSecrets
metadata:
  name: allow‑s06‑limited‑sa
spec:
  match:
    kinds:
    - apiGroups: [""]
      kinds: ["Secret"]
  parameters:
    allowedServiceAccounts: ["s06-limited-sa"]
```

Apply the updated constraint, then the SA will be permitted.

---

### 5. ServiceAccount token is missing, expired, or not mounted  

**Why it happens**  
If the pod’s token volume is not present (e.g., `automountServiceAccountToken: false`) or the token has been revoked, the API server cannot authenticate the request, resulting in a *Unauthorized* error rather than a *Forbidden* one.

**How to verify**  

* Inside the pod, check for the token file:

```bash
cat /var/run/secrets/kubernetes.io/serviceaccount/token
```

* Verify the token is still valid:

```bash
kubectl describe secret $(kubectl get secret -n kubexplain-eval | grep s06-limited-sa-token | awk '{print $1}')
```

If the token secret is missing or the file is empty, the SA cannot authenticate.

**Fix**  

* Ensure `automountServiceAccountToken: true` (or omit the field) in the pod spec.  
* If you deliberately disabled auto‑mount, manually mount the token via a projected volume.  
* Re‑create the pod so a fresh token is generated.

---

### 6. API server feature gate or configuration disables secret reads for non‑privileged users  

**Why it happens**  
Rare, but some clusters enable the `SecretRestriction` feature (or similar) that limits secret visibility to owners only.

**How to verify**  

* Check the API server flags (requires cluster‑admin access) for `--feature-gates=SecretRestriction=true`.  
* Look at the `apiserver` ConfigMap (`kube-apiserver`) for such flags.

**Fix**  

If this feature is not required, disable it by removing the flag and restarting the API server.  Otherwise, grant the SA explicit ownership of the secret (e.g., by creating the secret with `metadata.ownerReferences` pointing to a resource the SA controls).

---

### 7. Namespace is in a terminating state or has finalizers that block secret operations  

**Why it happens**  
When a namespace is being deleted, most API operations are rejected, including listing Secrets.

**How to verify**  

```bash
kubectl get namespace kubexplain-eval -o yaml | grep -i phase
```

If `status.phase: Terminating`, the namespace is being torn down.

**Fix**  

* Wait for the namespace deletion to complete, or  
* Remove the blocking finalizers (requires admin rights) to finish deletion, then recreate the namespace and the needed resources.

---

## Quick verification checklist (run when you regain access)

1. **RBAC check** – `kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval`
2. **RoleBinding inspection** – ensure the binding and referenced Role are in the same namespace.
3. **Secret location** – confirm the secret you need lives in `kubexplain-eval`.
4. **Admission policies** – search for constraints that mention the SA.
5. **Token presence** – look for the service‑account token file inside the pod.
6. **API server flags** – verify no restrictive feature gates are enabled.
7. **Namespace health** – `kubectl get ns kubexplain-eval -o jsonpath='{.status.phase}'`.

Address the first failing step; in most clusters the root cause is **missing RBAC permission** (item 1). Once you add a Role that allows `list`/`get` on `secrets` and bind it to `s06-limited-sa`, the error should disappear.
