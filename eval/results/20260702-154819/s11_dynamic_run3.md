# s11 | mode=dynamic | run=3 | 9.4s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

**Root cause**

The namespace `kubexplain-eval-psa` is protected by the **PodSecurity admission controller** in the **`restricted`** profile (the default when a namespace is labeled `pod-security.kubernetes.io/enforce=restricted`).  
When you try to create the pod **`s11-psa-violator`**, the admission controller checks the pod’s security settings and rejects it because the pod does not satisfy the mandatory requirements of the *restricted* profile:

| Requirement | What the pod is doing | What the profile expects |
|-------------|----------------------|--------------------------|
| `allowPrivilegeEscalation` must be **false** | Not set (defaults to `true`) | `securityContext.allowPrivilegeEscalation: false` |
| All Linux capabilities must be dropped | No `capabilities.drop` defined | `securityContext.capabilities.drop: ["ALL"]` |
| Container must run as **non‑root** | `runAsUser: 0` (root) or not set | `securityContext.runAsNonRoot: true` (and avoid `runAsUser: 0`) |
| `seccompProfile.type` must be **RuntimeDefault** or **Localhost** | Not set (defaults to `Unconfined`) | `securityContext.seccompProfile.type: RuntimeDefault` |

Because one (actually several) of these checks fails, the API server returns:

```
pods "s11-psa-violator" is forbidden: violates PodSecurity "restricted:latest": ...
```

---

**How to fix it**

You have two main options:

### 1. Make the pod compliant with the *restricted* profile  
Edit the pod manifest (`s11-podsecurity-restricted.yaml`) and add the required `securityContext` fields. A minimal compliant example:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: s11-psa-violator
  namespace: kubexplain-eval-psa
spec:
  containers:
  - name: app
    image: <your‑image>
    # ---- security settings required by the restricted profile ----
    securityContext:
      allowPrivilegeEscalation: false
      runAsNonRoot: true               # ensures the container does not run as root
      # (optional) you can also set a specific non‑zero UID, e.g. runAsUser: 1000
      capabilities:
        drop: ["ALL"]
      seccompProfile:
        type: RuntimeDefault          # or type: Localhost and provide a profile file
    # ---------------------------------------------------------------
```

After saving the file, apply it again:

```bash
kubectl apply -f s11-podsecurity-restricted.yaml
```

### 2. Relax the namespace’s PodSecurity level (if you really need the original pod spec)  
If the workload truly requires privileged settings, you can lower the enforcement level for the namespace to **`baseline`** (still enforces many best‑practices) or **`privileged`** (no restrictions).

```bash
# Set to baseline (allows some privileged features)
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  --overwrite

# Or, to remove restrictions completely:
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=privileged \
  --overwrite
```

> **Caution:** Reducing the security level affects *all* pods created in that namespace. Use it only when you understand the security implications.

After relabeling, you can re‑apply the original pod manifest (which may still contain `runAsUser: 0`, `allowPrivilegeEscalation: true`, etc.) and it will be accepted.

---

### Which approach should you choose?

* **Prefer option 1** – keep the namespace in `restricted` mode and make the pod conform. This maintains the strong security posture that the cluster administrator intended.
* Use **option 2** only for short‑lived testing or when the workload cannot be made compliant (e.g., it truly needs to run as root or needs extra capabilities). In production, consider creating a separate namespace with a less‑strict profile for such workloads.

---

**Summary**

- The pod is rejected because the namespace enforces the **restricted** PodSecurity profile.
- The pod must set `allowPrivilegeEscalation: false`, drop all capabilities, run as non‑root, avoid `runAsUser: 0`, and use a `RuntimeDefault` (or `Localhost`) seccomp profile.
- Fix it by updating the pod’s `securityContext` (recommended) **or** by lowering the namespace’s PodSecurity enforcement level.
