# s06 | mode=static | run=3 | 15.6s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Most likely reasons the pod can’t list Secrets  

| # | Likely cause | Why it shows up as an “cannot list secrets” error | How you can verify it (when you have cluster access) | What you need to change to fix it |
|---|--------------|---------------------------------------------------|------------------------------------------------------|-----------------------------------|
| 1 | **Missing RBAC permission** – the ServiceAccount `s06‑limited‑sa` does not have a `list` (or `get`) rule for the `secrets` resource in the namespace `kubexplain‑eval`. | The API server checks the ServiceAccount’s Role/ClusterRole bindings before allowing a `GET /api/v1/namespaces/kubexplain‑eval/secrets`. If no rule matches, the request is rejected with `Forbidden` (or `Unauthorized`). | ```bash\nkubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval\n```<br>or inspect the Role/ClusterRole bindings: `kubectl get rolebinding,clusterrolebinding -n kubexplain-eval -o yaml | grep s06-limited-sa`. | Create (or update) a Role that grants the needed verbs and bind it to the ServiceAccount: <br>```yaml\napiVersion: rbac.authorization.k8s.io/v1\nkind: Role\nmetadata:\n  name: secret‑reader\n  namespace: kubexplain-eval\nrules:\n- apiGroups: [\"\"]\n  resources: [\"secrets\"]\n  verbs: [\"get\",\"list\",\"watch\"]\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: secret‑reader‑binding\n  namespace: kubexplain-eval\nsubjects:\n- kind: ServiceAccount\n  name: s06-limited-sa\n  namespace: kubexplain-eval\nroleRef:\n  kind: Role\n  name: secret‑reader\n  apiGroup: rbac.authorization.k8s.io\n``` |
| 2 | **ServiceAccount token not mounted** – the pod is running without a valid JWT because `automountServiceAccountToken: false` (or the pod’s `serviceAccountName` is miss‑spelled). Without a token the API request is unauthenticated, resulting in a `401 Unauthorized` that often surfaces as “cannot list secrets”. | The kubelet will not inject the token volume, so the pod’s client library cannot present credentials to the API server. | ```bash\nkubectl get sa s06-limited-sa -n kubexplain-eval -o yaml | grep automountServiceAccountToken\nkubectl get pod <pod-name> -n kubexplain-eval -o yaml | grep serviceAccountName\n``` | Either remove the `automountServiceAccountToken: false` flag from the ServiceAccount (or set it to `true`), or explicitly add a projected service‑account token volume to the pod spec. |
| 3 | **Attempting to list Secrets in a different namespace** – the code asks for `secrets` in another namespace (e.g., `default`) while the ServiceAccount only has rights in `kubexplain‑eval`. | RBAC rules are namespace‑scoped; a Role that permits `list secrets` in `kubexplain‑eval` does **not** grant the same permission elsewhere. The API server returns `Forbidden` for the other namespace. | Check the request URL in the application logs or by enabling audit logging. Then verify the RBAC for that target namespace: `kubectl auth can-i list secrets -n <other-ns> --as=system:serviceaccount:kubexplain-eval:s06-limited-sa`. | Either modify the code to request secrets from the correct namespace, or create a Role (or ClusterRole) that grants `list` on `secrets` in the additional namespace(s) and bind it to the ServiceAccount. |
| 4 | **NetworkPolicy blocks egress to the API server** – a namespace‑wide NetworkPolicy denies outbound traffic from the pod to the control‑plane IP/port. | The pod can reach the API server only if egress is allowed. When blocked, the client sees a connection error that may be reported as “cannot list secrets”. | ```bash\nkubectl get networkpolicy -n kubexplain-eval -o yaml\nkubectl describe pod <pod-name> -n kubexplain-eval | grep -i \"NetworkPolicy\"\n``` | Add or adjust a NetworkPolicy that permits egress to the API server (typically `to: - ipBlock: {cidr: <master‑svc‑cidr>/32}` on port 443) or remove the restrictive policy. |
| 5 | **Admission controller (e.g., PodSecurityPolicy, OPA/Gatekeeper) denies secret access** – a policy explicitly forbids pods from reading Secrets. | The request passes authentication and RBAC, but the admission webhook returns a denial, which appears as a generic “forbidden” error. | Look at the pod’s `kubectl describe pod <pod-name>` events for messages like “admission webhook denied”. Also inspect the relevant policy objects (`PodSecurityPolicy`, `ConstraintTemplate`, `Constraint`). | Update the policy to allow the ServiceAccount (or the pod’s label set) to read Secrets, or add an exemption. This is usually a last‑resort change because it widens security posture. |

---

## How to verify each cause (once you can reach the cluster)

1. **RBAC** – `kubectl auth can-i …` and inspect Role/ClusterRole bindings.  
2. **ServiceAccount token** – check the ServiceAccount definition and the pod spec for `automountServiceAccountToken` and `serviceAccountName`.  
3. **Namespace mismatch** – review the code or logs for the namespace used in the API call; confirm the RBAC for that namespace.  
4. **NetworkPolicy** – list NetworkPolicies in the namespace and look for egress rules that might block `kubernetes.default.svc` (port 443).  
5. **Admission webhook** – `kubectl describe pod` events and the logs of any OPA/Gatekeeper or PSP controller.

---

## Quick “fix checklist” you can apply when you regain access

1. **Add a Role that lists Secrets** (or extend an existing one) and bind it to `s06‑limited‑sa`.  
2. **Ensure the ServiceAccount token is mounted** – either remove `automountServiceAccountToken: false` or add an explicit projected token volume.  
3. **Confirm the code is targeting the correct namespace**; if cross‑namespace access is required, grant it via a Role in the other namespace or a ClusterRole.  
4. **Adjust NetworkPolicy** to allow egress to the API server.  
5. **Review any admission‑controller policies** that might be denying secret reads and relax them as needed.

Apply the items in the order above; in most clusters the missing RBAC rule (cause #1) resolves the problem 80‑90 % of the time. If that doesn’t help, move down the list.
