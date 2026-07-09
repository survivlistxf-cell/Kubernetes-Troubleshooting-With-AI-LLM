# s11 | mode=dynamic | run=2 | 8.4s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

**Root cause**

The namespace `kubexplain-eval-psa` is being protected by the **PodSecurity admission controller** in **`restricted`** mode (the default for new clusters).  
When the API server receives the pod definition it checks the pod against the *restricted* policy and finds several mandatory fields missing or set to insecure values:

| Policy check | What the pod currently has | What the policy requires |
|--------------|---------------------------|--------------------------|
| **allowPrivilegeEscalation** | not set (defaults to `true`) | `securityContext.allowPrivilegeEscalation: false` |
| **capabilities** | none dropped (all capabilities are allowed) | `securityContext.capabilities.drop: ["ALL"]` |
| **runAsNonRoot** | not set (or `false`) | `securityContext.runAsNonRoot: true` |
| **runAsUser** | `0` (root) | must **not** be `0`; any non‑zero UID is acceptable (or omit and let the image’s default be used) |
| **seccompProfile** | not set (defaults to `unconfined`) | `securityContext.seccompProfile.type: RuntimeDefault` **or** `Localhost` |

Because any one of these checks fails, the admission controller rejects the pod with the “violates PodSecurity `restricted:latest`” error you see.

---

**How to fix it**

1. **Update the pod manifest** so that the required security settings are present.  
   You can add them at the **container level** (recommended) or at the **pod level** (some settings are pod‑wide).

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
       # ---- securityContext required by the restricted policy ----
       securityContext:
         allowPrivilegeEscalation: false
         capabilities:
           drop: ["ALL"]
         runAsNonRoot: true          # forces a non‑root UID
         # either omit runAsUser (let the image decide) or set a non‑zero UID, e.g. 1000
         # runAsUser: 1000
         seccompProfile:
           type: RuntimeDefault      # or type: Localhost and provide a profile file
   ```

   *If you prefer to set some fields at the pod level (they apply to every container), you can add a `securityContext` block under `spec:` as well.*

2. **Apply the corrected manifest**

   ```bash
   kubectl apply -f s11-podsecurity-restricted.yaml
   ```

   The pod should now be accepted.

3. **Alternative (less secure) approach – relax the namespace policy**  
   If you cannot change the pod (e.g., you are testing a third‑party workload), you can lower the enforcement level for this namespace:

   ```bash
   # Set the namespace to “baseline” (still enforces many checks but is less strict)
   kubectl label namespace kubexplain-eval-psa pod-security.kubernetes.io/enforce=baseline --overwrite

   # Or completely disable enforcement (not recommended for production)
   kubectl label namespace kubexplain-eval-psa pod-security.kubernetes.io/enforce=privileged --overwrite
   ```

   After relabeling, the same pod definition would be accepted, but you lose the security guarantees that the *restricted* policy provides.

---

### Quick checklist for the **restricted** policy

- **Container securityContext**
  - `allowPrivilegeEscalation: false`
  - `capabilities.drop: ["ALL"]`
  - `runAsNonRoot: true`
  - `runAsUser` **must not** be `0` (omit or set a non‑zero UID)
  - `seccompProfile.type: RuntimeDefault` (or `Localhost` with a custom profile)

- **Pod‑level securityContext** (optional, but can be used for `runAsNonRoot` and `runAsUser` if you want the same values for all containers).

Once those fields are present, the admission controller will let the pod be created, and the root cause (policy violation) will be resolved.
