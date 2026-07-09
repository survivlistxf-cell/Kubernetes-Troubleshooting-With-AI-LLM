# s11 | mode=static | run=2 | 15.2s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons why the pod **s11‑psa‑violator** cannot be created  
*(ordered from highest to lowest probability)*  

### 1. Pod Security Admission (PSA) policy violation  
**Why it’s likely** – The pod name itself contains “psa‑violator”, which usually indicates the manifest uses a privileged setting (e.g., `privileged: true`, `hostPID`, `hostNetwork`, `runAsUser: 0`, `allowPrivilegeEscalation`, etc.) that is blocked by the namespace’s PSA enforcement level (`restricted` or `baseline`).  

**How to verify (when you have cluster access)**  

```bash
# Show the PSA settings that apply to the namespace
kubectl get namespace kubexplain-eval-psa -o yaml | grep -i pod-security.kubernetes.io
```

```bash
# Try to create the pod with dry‑run to see the exact error
kubectl apply -f s11-psa-violator.yaml --dry-run=client -n kubexplain-eval-psa
```

The error will contain a message such as *“pod violates PodSecurity “restricted” policy”*.

**How to fix**  

- **Option A – Adjust the pod spec**: Remove or downgrade the offending fields (e.g., drop `privileged: true`, set a non‑root `runAsUser`, avoid `hostPath` volumes, etc.).  
- **Option B – Lower the PSA enforcement level** (if you control the namespace and it is safe):  

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/audit=baseline \
  pod-security.kubernetes.io/warn=baseline
```

  *or* set it to `privileged` for testing only.  

- **Option C – Create a separate namespace** with a more permissive PSA level and run the pod there.

---

### 2. RBAC permission denial (you lack `create` rights for pods)  
**Why it’s likely** – If you are using a limited service account or a user without the `pods/create` verb, the API server will reject the request before any policy checks.  

**How to verify**  

```bash
kubectl auth can-i create pod -n kubexplain-eval-psa --as <your‑user-or‑sa>
```

If the output is `no`, you lack permission.

**How to fix**  

- Ask a cluster admin to grant the needed role, e.g.:

```yaml
# Role granting pod creation in the target namespace
kind: Role
apiVersion: rbac.authorization.k8s.io/v1
metadata:
  name: pod-creator
  namespace: kubexplain-eval-psa
rules:
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["create"]
```

```bash
kubectl apply -f role.yaml
kubectl create rolebinding pod-creator-binding \
  --role=pod-creator \
  --serviceaccount=<your‑sa-namespace>:<your‑sa-name> \
  -n kubexplain-eval-psa
```

---

### 3. ResourceQuota or LimitRange preventing the pod from being admitted  
**Why it’s likely** – The namespace may have a `ResourceQuota` that caps CPU, memory, or pod count, and the pod’s requests/limits exceed the remaining quota.  

**How to verify**  

```bash
kubectl get quota -n kubexplain-eval-psa
kubectl describe quota <quota‑name> -n kubexplain-eval-psa
```

Look for “hard” vs. “used” values that are already maxed out.

```bash
kubectl get limitrange -n kubexplain-eval-psa
```

**How to fix**  

- Reduce the pod’s resource requests/limits to fit within the quota.  
- If you control the quota, increase the limits or delete unused pods to free resources.  
- Request the cluster admin to raise the quota for the namespace.

---

### 4. Admission webhook (e.g., OPA Gatekeeper, Kyverno) rejecting the pod  
**Why it’s likely** – Many clusters enforce custom policies via webhooks that can block pods for naming conventions, image registries, required labels, etc.  

**How to verify**  

```bash
kubectl get validatingwebhookconfigurations
kubectl get mutatingwebhookconfigurations
```

Then inspect the logs of the webhook pod (or the API‑server events) for a message like *“denied by <webhook‑name>: <reason>”*.

**How to fix**  

- Adjust the pod manifest to satisfy the webhook’s policy (add required labels, use approved images, etc.).  
- If the policy is too strict for this test, ask the webhook owner to whitelist the pod or temporarily disable the rule.

---

### 5. Namespace is in a terminating state or does not exist  
**Why it’s likely** – A namespace that is being deleted cannot accept new resources, and a typo in the namespace name will cause a “not found” error.  

**How to verify**  

```bash
kubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'
```

If the phase is `Terminating`, the namespace is being removed.

**How to fix**  

- Wait for the namespace to finish terminating, or delete the finalizers that are blocking it (requires admin rights).  
- If the namespace never existed, create it first:

```bash
kubectl create namespace kubexplain-eval-psa
```

---

### 6. Invalid pod spec (syntax error, unknown fields)  
**Why it’s likely** – A malformed YAML (e.g., misspelled `command` as `commnd`) will be accepted by the API server but the fields will be ignored, possibly causing the pod to fail creation or start in a waiting state.  

**How to verify**  

```bash
kubectl apply -f s11-psa-violator.yaml --dry-run=client -n kubexplain-eval-psa
```

The dry‑run will surface schema validation errors.

**How to fix**  

- Run the manifest through a YAML validator or `kubectl apply --validate=true`.  
- Correct any misspelled keys or misplaced sections, then re‑apply.

---

## Quick checklist you can run when you regain access

1. **Check the exact error message** from `kubectl apply` or `kubectl create`.  
2. **Inspect PSA settings** on the namespace.  
3. **Validate RBAC** with `kubectl auth can-i`.  
4. **Review ResourceQuota / LimitRange** usage.  
5. **Look for webhook denials** in the API‑server events.  
6. **Confirm the namespace status** and existence.  
7. **Validate the pod YAML** with `--dry-run=client --validate=true`.

Address the first failing check you encounter; in most cases the root cause will be the PSA policy violation, followed by permission or quota issues. Once the offending condition is corrected, the pod should be created successfully.
