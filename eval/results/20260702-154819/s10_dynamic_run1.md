# s10 | mode=dynamic | run=1 | 7.9s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

The namespace **kubexplain‑eval** has a single NetworkPolicy:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-deny-all-ingress
spec:
  podSelector:
    matchLabels:
      app: s10-server          # selects the server pod
  policyTypes:
  - Ingress
```

* It **only selects the pod `s10-server`** (label `app=s10-server`).
* It **does not contain any `ingress` rules** – it merely declares the policy type `Ingress`.

In Kubernetes, the moment a pod is selected by a NetworkPolicy that has an empty `ingress` list, **all inbound traffic to that pod is denied** (the default “allow‑everything” behavior is overridden).  

Therefore every connection attempt from `s10-client` (or any other pod) to the server’s IP `10.244.226.97:80` is dropped, which is why `wget` inside the client times out.

### How to fix it

You have two options:

1. **Remove the deny‑all policy** if you don’t need any restriction on the server pod.

   ```bash
   kubectl delete networkpolicy s10-deny-all-ingress -n kubexplain-eval
   ```

2. **Add an explicit allow rule** that permits traffic from the client (or from the whole namespace) and keep the policy in place for future tightening.

   Example – allow any pod in the same namespace:

   ```yaml
   apiVersion: networking.k8s.io/v1
   kind: NetworkPolicy
   metadata:
     name: s10-allow-from-namespace
     namespace: kubexplain-eval
   spec:
     podSelector:
       matchLabels:
         app: s10-server
     policyTypes:
     - Ingress
     ingress:
     - from:
       - namespaceSelector: {}   # matches the same namespace
       ports:
       - protocol: TCP
         port: 80
   ```

   Apply it:

   ```bash
   kubectl apply -f s10-allow-from-namespace.yaml
   ```

   Or, if you want to allow only the specific client pod:

   ```yaml
   ingress:
   - from:
     - podSelector:
         matchLabels:
           app: s10-client   # label on the client pod
   ```

   (Make sure the client pod actually has that label.)

### Verify the fix

1. **Check the policy list** – you should see the new allow rule (or no policy at all).

   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

2. **Test connectivity again** from the client pod:

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- wget -qO- http://s10-server
   ```

   The request should now succeed and return the Nginx default page.

---

**Root cause:** a NetworkPolicy (`s10-deny-all-ingress`) that selects the server pod but contains no ingress rules, which blocks all inbound traffic.  
**Fix:** delete the policy or add an appropriate ingress rule that permits traffic from the client (or from the namespace).
