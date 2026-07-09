# s11 | mode=dynamic | run=1 | 16.4s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons why the pod **`s11-psa-violator`** cannot be created in namespace **`kubexplain-eval-psa`**

Below the causes are ordered from the most common to the least common.  
For each one I show:

* **Why it would block creation**  
* **How to verify it (when you regain cluster access)**  
* **What to change to fix it**

---

### 1. Pod‑Security‑Admission (PSA) policy violation  
**Why it blocks:**  
Kubernetes 1.25+ can enforce the built‑in *Pod Security Standards* (restricted, baseline, privileged) via the **PodSecurity** admission controller. If the namespace is labeled with a stricter level (e.g., `restricted`) and the pod spec contains a disallowed feature (privileged container, hostPath, `runAsRoot`, `allowPrivilegeEscalation:true`, etc.), the API server rejects the creation with a **`Forbidden`** error that mentions “PodSecurityPolicy” or “PodSecurity”.

**How to verify:**  

```bash
# 1. See the PSA level applied to the namespace
kubectl get namespace kubexplain-eval-psa -o jsonpath='{.metadata.labels.pod-security.kubernetes.io/enforce}'

# 2. Look at the exact error returned when you tried to create the pod
kubectl apply -f pod.yaml 2>&1 | grep -i "forbidden"

# 3. Compare the pod spec with the rules for the enforced level
#   - restricted: no privileged, no hostPath, must drop all capabilities, runAsNonRoot, etc.
#   - baseline: allows some privileged features but still blocks hostPath, etc.
```

**Fix:**  

* **Option A – Adjust the pod spec** so it complies with the enforced level (e.g., remove `privileged: true`, add `runAsNonRoot: true`, drop capabilities, avoid hostPath volumes).  
* **Option B – Relax the PSA level for the namespace** (only if you control the policy and it’s safe):  

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  --overwrite
```

* **Option C – Create a separate namespace** with a less‑strict PSA label and deploy the pod there.

---

### 2. Namespace does not exist or is miss‑spelled  
**Why it blocks:**  
If the namespace `kubexplain-eval-psa` is absent, the API server returns **`Error from server (NotFound): namespaces "kubexplain-eval-psa" not found`**. A typo in the namespace name in the manifest or command will have the same effect.

**How to verify:**  

```bash
kubectl get namespace kubexplain-eval-psa
```

If the command returns *No resources found*, the namespace is missing.

**Fix:**  

* Create the namespace (or correct the name) before applying the pod:

```bash
kubectl create namespace kubexplain-eval-psa
```

* Update the pod manifest to reference the correct namespace.

---

### 3. RBAC – you lack permission to create pods in that namespace  
**Why it blocks:**  
The API server checks the `create` verb on the `pods` resource. If your user/service‑account is not bound to a role that allows this, you get a **`Forbidden`** error such as “User \"bob\" cannot create resource \"pods\" in API group \"\" in the namespace \"kubexplain-eval-psa\"”.

**How to verify:**  

```bash
# Check the current user
kubectl config view --minify -o jsonpath='{.contexts[0].context.user}'

# Test the permission directly
kubectl auth can-i create pods -n kubexplain-eval-psa
```

If the answer is **`no`**, you need a role binding.

**Fix:**  

* Grant the needed permission (example for a user `bob`):

```bash
kubectl create role pod-writer \
  --verb=create --resource=pods -n kubexplain-eval-psa

kubectl create rolebinding bob-pod-writer \
  --role=pod-writer --user=bob -n kubexplain-eval-psa
```

* If you are using a service‑account, bind it similarly.

---

### 4. ResourceQuota or LimitRange in the namespace blocks the pod  
**Why it blocks:**  
A `ResourceQuota` may limit total CPU, memory, number of pods, etc. If the pod’s resource requests/limits would exceed the quota, the API server rejects creation with **`Forbidden`** and a message like “exceeded quota: pods, requested: 1, used: 5, limited: 5”. A `LimitRange` can also enforce minimum/maximum request/limit values; violating those results in a similar error.

**How to verify:**  

```bash
kubectl get resourcequota -n kubexplain-eval-psa
kubectl describe resourcequota <quota-name> -n kubexplain-eval-psa

kubectl get limitrange -n kubexplain-eval-psa
kubectl describe limitrange <limitrange-name> -n kubexplain-eval-psa
```

Check the pod’s `resources:` section against the reported limits.

**Fix:**  

* Reduce the pod’s `requests`/`limits` to fit within the quota, or  
* Increase or delete the offending `ResourceQuota`/`LimitRange` (requires admin rights).

```bash
# Example: edit the quota
kubectl edit resourcequota <quota-name> -n kubexplain-eval-psa
```

---

### 5. Admission webhook (OPA/Gatekeeper, custom mutating/validating webhook) rejecting the pod  
**Why it blocks:**  
Clusters often run policy engines (e.g., Gatekeeper) that enforce custom constraints (e.g., “no privileged containers”, “image must be from approved registry”). If the pod violates one of those constraints, the webhook returns a **`Forbidden`** error with the policy name.

**How to verify:**  

```bash
# Re‑run the creation and capture the full error
kubectl apply -f pod.yaml 2>&1 | tail -n 20

# List the validating webhooks in the cluster
kubectl get validatingwebhookconfiguration

# Look for Gatekeeper constraints that target the namespace
kubectl get constraint -A | grep kubexplain-eval-psa
```

**Fix:**  

* Adjust the pod spec to satisfy the constraint (e.g., use an approved image, remove privileged flag).  
* If you control the policy and the pod should be allowed, modify or delete the offending constraint.  
* Ask the cluster admin to whitelist the namespace or pod if appropriate.

---

### 6. Invalid pod manifest (syntax or schema errors)  
**Why it blocks:**  
A typo in the YAML (e.g., `commnd` instead of `command`, wrong indentation) causes the API server to reject the object with a schema validation error such as “unknown field: commnd”.

**How to verify:**  

```bash
kubectl apply -f pod.yaml --dry-run=client -o yaml
```

The dry‑run will surface schema errors before contacting the server.

**Fix:**  

* Correct the YAML (run it through a linter like `kubeval` or `kubectl apply --dry-run=client`).  
* Use `kubectl apply --validate -f pod.yaml` to catch errors early.

---

### 7. Image‑pull‑secret or registry access problem (rarely blocks creation)  
**Why it blocks:**  
If the pod spec references a private image and the namespace lacks a valid `imagePullSecret`, the pod may still be created but will enter **`ImagePullBackOff`** after scheduling. However, some admission controllers (e.g., image‑policy webhook) reject the pod outright.

**How to verify:**  

```bash
kubectl get secret -n kubexplain-eval-psa
kubectl describe pod s11-psa-violator  # after creation attempt, look for events
```

**Fix:**  

* Create or update the secret and reference it in the pod spec (`imagePullSecrets:`).  
* Ensure the registry credentials are correct.

---

## Quick checklist you can run when you get back to the cluster

1. **Read the exact error message** – it usually tells you the component that rejected the pod.  
2. **Check PSA label on the namespace** (`pod-security.kubernetes.io/enforce`).  
3. **Confirm the namespace exists** (`kubectl get ns`).  
4. **Validate RBAC** (`kubectl auth can-i create pods -n …`).  
5. **Inspect quotas & limit ranges** (`kubectl get resourcequota,limitrange`).  
6. **Look for validating webhooks** (`kubectl get validatingwebhookconfiguration`).  
7. **Run a dry‑run validation** (`kubectl apply -f pod.yaml --dry-run=client`).  

Address the first failing check you encounter, and the pod should be creatable. If you still hit a wall after those steps, the error text itself will point to the remaining cause. Good luck!
