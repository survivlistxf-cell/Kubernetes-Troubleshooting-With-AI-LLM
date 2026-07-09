# s11 | mode=static | run=3 | 16.6s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Most likely reasons you cannot create pod **s11‑psa‑violator** in namespace **kubexplain‑eval‑psa**

Below the causes are ordered from the most common to the least common for a failure that mentions “psa‑violator”.  For each one you’ll see:

* **Why it happens** – the underlying rule that blocks the pod.  
* **How to verify it** – the kubectl checks you can run when you regain access.  
* **How to fix it** – the change you need to make.

---

### 1. Pod Security Admission (PSA) policy violation  

**Why**  
The namespace is probably labeled with a restrictive PSA level (e.g. `restricted` or `baseline`).  
A pod named *psa‑violator* almost always contains a security‑context that the current PSA level disallows – for example:

* running as root (`runAsUser: 0`)  
* privileged container (`securityContext.privileged: true`)  
* hostPath or hostNetwork usage  
* adding disallowed Linux capabilities  

**How to verify**  

```bash
# Show the PSA level applied to the namespace
kubectl get namespace kubexplain-eval-psa -o jsonpath='{.metadata.labels}' | grep pod-security.kubernetes.io

# Inspect the pod spec you tried to apply (if you have the yaml)
cat s11-psa-violator.yaml

# Attempt a dry‑run create to see the exact denial message
kubectl create -f s11-psa-violator.yaml --dry-run=client -n kubexplain-eval-psa
```

The error message will contain something like:

```
pod "s11-psa-violator" is forbidden: violates PodSecurity "restricted": runAsUser 0 must be >= 1000
```

**How to fix**  

* **Make the pod compliant** – remove the offending fields (run as non‑root, drop privileged flag, avoid hostPath, etc.).  
* **Relax the namespace PSA level** (if policy permits) by adding a less‑strict label, e.g.:

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  --overwrite
```

* **Add a pod‑specific exemption** – label the pod with `pod-security.kubernetes.io/warn` or `audit` to bypass enforcement, but only if your cluster allows per‑pod overrides.

---

### 2. Insufficient RBAC permission to create Pods  

**Why**  
Your user/service‑account may lack the `create` verb on `pods` in that namespace.

**How to verify**  

```bash
kubectl auth can-i create pod -n kubexplain-eval-psa
```

If the output is `no`, you’re blocked by RBAC.

**How to fix**  

* Grant the needed role, for example:

```bash
kubectl create role pod-creator \
  --verb=create --resource=pods -n kubexplain-eval-psa

kubectl create rolebinding pod-creator-binding \
  --role=pod-creator --user=<your‑user> -n kubexplain-eval-psa
```

* Or ask a cluster admin to add the permission to an existing role.

---

### 3. Namespace‑wide ResourceQuota or LimitRange prevents the pod  

**Why**  
The pod’s resource requests/limits may exceed what the namespace’s `ResourceQuota` allows, or a `LimitRange` may require fields you omitted.

**How to verify**  

```bash
kubectl get quota -n kubexplain-eval-psa
kubectl describe quota <quota‑name> -n kubexplain-eval-psa

kubectl get limitrange -n kubexplain-eval-psa
kubectl describe limitrange <limitrange‑name> -n kubexplain-eval-psa
```

Look for messages such as “exceeded quota: pods” or “must specify cpu limits”.

**How to fix**  

* Reduce the pod’s `requests`/`limits` to fit within the quota.  
* Increase the quota (requires admin rights) or create a new quota with higher limits.  
* Add the missing fields required by the `LimitRange`.

---

### 4. Namespace is in a **Terminating** state  

**Why**  
If the namespace is being deleted, the API server rejects new creations.

**How to verify**  

```bash
kubectl get namespace kubexplain-eval-psa -o jsonpath='{.status.phase}'
```

If the phase is `Terminating`, the namespace is in the process of being removed.

**How to fix**  

* Wait for the deletion to finish, or  
* Remove any finalizers that are blocking the namespace (admin‑level operation):

```bash
kubectl get namespace kubexplain-eval-psa -o json > ns.json
# edit ns.json, delete .spec.finalizers, then:
kubectl replace --raw "/api/v1/namespaces/kubexplain-eval-psa/finalize" -f ./ns.json
```

---

### 5. Admission webhook (e.g., OPA Gatekeeper, Kyverno) rejecting the pod  

**Why**  
Cluster‑wide or namespace‑scoped policies may block certain images, labels, or configurations.

**How to verify**  

```bash
kubectl get validatingwebhookconfigurations
kubectl get mutatingwebhookconfigurations

# After a dry‑run create, the error will mention the webhook name:
kubectl create -f s11-psa-violator.yaml --dry-run=client -n kubexplain-eval-psa
```

**How to fix**  

* Adjust the pod spec to satisfy the policy (e.g., use an allowed image, add required labels).  
* If you control the webhook, modify or disable the rule that is too strict.  
* Ask the policy owner to create an exemption for this workload.

---

### 6. Other less common reasons  

| Reason | Why it can block creation | How to verify | Fix |
|---|---|---|---|
| **Invalid pod YAML** (syntax error, unknown fields) | API server rejects the object before admission | `kubectl apply -f <file> --dry-run=client` shows schema errors | Correct the YAML (e.g., fix misspelled `command` → `command`) |
| **Missing ServiceAccount** (pod references a non‑existent SA) | Admission may reject if `serviceAccountName` does not exist | `kubectl get serviceaccount -n kubexplain-eval-psa` | Create the ServiceAccount or remove the reference |
| **ImagePullSecret not present** (only matters after creation, but some clusters pre‑validate) | Pod creation may be denied by a webhook that checks image pullability | Check the secret existence: `kubectl get secret -n kubexplain-eval-psa` | Create the secret or reference a public image |

---

## Quick checklist you can run when you get back into the cluster

1. **Dry‑run the pod** – see the exact error message.  
2. **Check PSA labels on the namespace** – `kubectl get ns … -L pod-security.kubernetes.io/*`.  
3. **Validate RBAC** – `kubectl auth can-i create pod -n …`.  
4. **Inspect quotas & limit ranges** – `kubectl get quota,limitrange -n …`.  
5. **Look at recent events** – `kubectl get events -n … --sort-by=.metadata.creationTimestamp`.  
6. **Identify any blocking webhooks** – `kubectl get validatingwebhookconfigurations` and read the error message for the webhook name.

Address the first failing check you encounter; in most clusters where the pod name includes “psa‑violator”, the PSA violation will be the root cause. Adjust the pod spec or the namespace’s PSA level, and the creation should succeed.
