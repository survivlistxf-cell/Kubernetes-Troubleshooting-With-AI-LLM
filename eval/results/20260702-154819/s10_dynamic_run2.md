# s10 | mode=dynamic | run=2 | 7.7s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### Root cause
The namespace **kubexplain-eval** has a NetworkPolicy named **s10‑deny‑all‑ingress**:

```yaml
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
```

* It selects the pod `s10-server` (label `app=s10-server`).
* It declares only the `Ingress` policy type **without any ingress rules**.

In Kubernetes, an Ingress NetworkPolicy that matches a pod and has **no** ingress rules **denies all inbound traffic** to that pod.  
Therefore the client pod `s10-client` cannot establish a connection to `s10-server`; the request simply times out, as shown in the client logs.

### How to fix it
You need to allow the desired traffic by either:

1. **Adding an explicit allow rule** to the existing policy, or  
2. **Creating a separate allow‑only policy**, or  
3. **Removing the deny‑all policy** if you don’t need it.

#### Option 1 – Update the existing policy to allow traffic from the client pod (or the whole namespace)

```bash
kubectl -n kubexplain-eval patch networkpolicy s10-deny-all-ingress --type='json' -p='[
  {
    "op": "add",
    "path": "/spec/ingress",
    "value": [
      {
        "from": [
          {
            "podSelector": {
              "matchLabels": { "app": "s10-client" }
            }
          },
          {
            "namespaceSelector": {}
          }
        ],
        "ports": [
          { "protocol": "TCP", "port": 80 }
        ]
      }
    ]
  }
]'
```

*The rule above permits TCP 80 traffic from any pod in the same namespace (or just from pods labeled `app=s10-client`). Adjust the selector/port as needed.*

#### Option 2 – Create a separate allow‑only policy and keep the deny‑all for clarity

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client
  namespace: kubexplain-eval
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
```

Apply it:

```bash
kubectl apply -f s10-allow-client.yaml
```

#### Option 3 – Delete the deny‑all policy (if you want unrestricted access)

```bash
kubectl -n kubexplain-eval delete networkpolicy s10-deny-all-ingress
```

### Verify the fix
After applying one of the changes, test connectivity again from the client pod:

```bash
kubectl -n kubexplain-eval exec s10-client -- wget -qO- http://s10-server
```

You should receive the Nginx default page (or whatever the server serves) instead of a timeout. If it still fails, double‑check:

* The pod labels (`app=s10-server`, `app=s10-client`) match the selectors.
* The service `s10-server` exists and points to port 80.
* No other NetworkPolicies in the namespace affect the traffic.
