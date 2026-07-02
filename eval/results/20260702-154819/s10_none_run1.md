# s10 | mode=none | run=1 | 7.6s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Root cause  

The namespace **kubexplain‑eval** has a NetworkPolicy named **s10‑deny‑all‑ingress**:

```yaml
spec:
  podSelector:
    matchLabels:
      app: s10-server          # selects the server pod
  policyTypes:
  - Ingress
  # no ingress rules
```

When a NetworkPolicy includes an `Ingress` type **without any `ingress` rules**, it **denies all inbound traffic** to the selected pods.  
Therefore every connection attempt from `s10-client` (or any other pod) to the `s10-server` pod is dropped by the CNI (Calico), which is why the `wget` commands in the client pod time out even though the server pod is running and its container is ready.

## How to fix it  

You have three typical options:

### 1. Remove the deny‑all policy (if you don’t need any restriction)

```bash
kubectl -n kubexplain-eval delete networkpolicy s10-deny-all-ingress
```

### 2. Modify the existing policy to allow the client pod

Edit the policy and add an `ingress` rule that permits traffic from the client (or from the whole namespace).

```bash
kubectl -n kubexplain-eval edit networkpolicy s10-deny-all-ingress
```

Add the following under `spec`:

```yaml
ingress:
- from:
  - podSelector:
      matchLabels:
        app: s10-client          # allow pods labelled as the client
  ports:
  - protocol: TCP
    port: 80
```

Save and exit; the policy will now allow TCP 80 traffic from `s10-client` to `s10-server`.

### 3. Keep the deny‑all policy and create a separate “allow” policy

Create a new NetworkPolicy that explicitly permits the client’s traffic while leaving the original deny‑all policy in place.

```bash
cat <<'EOF' | kubectl -n kubexplain-eval apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client
    ports:
    - protocol: TCP
      port: 80
EOF
```

## Verification  

After applying any of the fixes, test connectivity again from the client pod:

```bash
kubectl -n kubexplain-eval exec s10-client -- wget -qO- http://s10-server
```

You should receive the default Nginx page (or whatever content the server serves) instead of a timeout.

---

**Summary:** The `s10-deny-all-ingress` NetworkPolicy blocks all inbound traffic to the `s10-server` pod, causing the timeout. Remove the policy, or add an ingress rule that permits traffic from the client (or the whole namespace), and the communication will succeed.
